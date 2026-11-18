import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { Miniflare } from 'miniflare';

const root = fileURLToPath(new URL('..', import.meta.url));
const api = '/api/v2/states';
const bundled = name => readFile(new URL(`../../app/src/main/assets/filters/${name}.txt`, import.meta.url), 'utf8');
const state = (description = 'Fixture state', override = {}) => ({
  version: 2, description, files: {
    hosts: '# preserved comment\r\ntracker.example.invalid\r\n',
    network: '||example.invalid/Case%2FPath?ID=12^\n@@||example.invalid/Case%2FPath?ID=123\n',
    cosmetic: '##.ad, [data-slot="with, comma"]\n',
    user_filters: 'www.example.invalid, m.example.invalid##:is(.sponsor, .ad)\n',
    ...override,
  },
});

function fixture({ publish = true, failure = false, storage } = {}) {
  const worker = new Miniflare({
    modules: true,
    modulesRules: [{ type: "ESModule", include: ["**/*.js"] }],
    modulesRoot: root,
    scriptPath: join(root, failure ? 'test/worker-fixture.js' : 'worker.js'),
    compatibilityDate: '2026-07-30',
    durableObjects: { RULE_STATES: { className: 'RuleStateCatalog', useSQLite: true } },
    durableObjectsPersist: storage,
    bindings: { ALLOW_RULE_PUBLISH: String(publish) },
  });
  const request = (route, init) => worker.dispatchFetch(new URL(route, 'https://catalog.example.invalid').href, init);
  return {
    request,
    list: async (query = '') => (await request(api + query)).json(),
    publish: data => request(api, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) }),
    stop: () => worker.dispose(),
  };
}

test('v1 diffs are retired without a legacy storage dependency; v2 publishing is off by default', async t => {
  const app = fixture({ publish: false });
  t.after(app.stop);
  const health = await app.request('/api/health');
  assert.equal((await health.json()).version, 2);
  const preflight = await app.request(api, { method: 'OPTIONS' });
  assert.equal(preflight.status, 200);
  assert.match(preflight.headers.get('Access-Control-Allow-Methods'), /\bPOST\b/);
  assert.equal((await app.request('/api/states')).status, 410);
  assert.equal((await app.request('/api/states/old')).status, 410);
  assert.equal((await app.request('/api/states', { method: 'POST' })).status, 410);
  assert.equal((await app.request(`${api}/missing`)).status, 404);
  assert.equal((await app.request('/missing')).status, 404);
  assert.equal((await app.publish(state())).status, 403);
  assert.deepEqual(await app.list(), { version: 2, states: [], next_before: null });
});

test('rejects malformed, oversized, executable, and non-snapshot payloads without publishing', async t => {
  const app = fixture();
  t.after(app.stop);
  const valid = state();
  for (const invalid of [
    { diffs: { 'hosts.txt': { added: ['tracker.example.invalid'] } } },
    { ...valid, files: { hosts: valid.files.hosts } },
    { ...valid, files: { ...valid.files, '../scriptlets/code.js': 'alert(1)' } },
    { ...valid, provider: 'spoof' },
    state('scriptlet', { cosmetic: 'example.invalid##+js(set, test, true)' }),
    state('css at-rule', { user_filters: '##.foo { color:red; }' }),
    state('foreign syntax', { network: '||example.invalid/banner$script' }),
    state('incomplete selector', { cosmetic: '##.ad,' }),
    state('invalid exception', { cosmetic: '#@#.ad' }),
    state('invalid Unicode', { hosts: '# \udfff\n' }),
  ]) assert.equal((await app.publish(invalid)).status, 400);
  const malformed = await app.request(api, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{' });
  assert.equal(malformed.status, 400);
  const oversized = state('oversize', { network: '!'.repeat(512 * 1024 + 1) });
  assert.equal((await app.publish(oversized)).status, 413);
  assert.deepEqual((await app.list()).states, []);
});

test('round-trips the bundled 100k hosts and preserves exact comments, path case and selector lists on restart', async t => {
  const storage = await mkdtemp(join(tmpdir(), 'rule-catalog-test-'));
  t.after(() => rm(storage, { recursive: true, force: true }));
  const input = state('Bundled rules', { hosts: await bundled('hosts'), cosmetic: await bundled('cosmetic_rules') });
  const first = fixture({ storage });
  let published;
  try {
    const response = await first.publish(input);
    assert.equal(response.status, 201, await response.clone().text());
    published = await response.json();
  } finally { await first.stop(); }
  const restarted = fixture({ storage });
  try {
    const [summary] = (await restarted.list()).states;
    assert.equal(summary.state_id, published.state_id);
    const response = await restarted.request(`${api}/${published.state_id}`);
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { ...input, state_id: published.state_id, timestamp: summary.timestamp });
    const retry = await restarted.publish(input);
    assert.equal(retry.status, 200);
    assert.deepEqual(await retry.json(), { ...published, duplicate: true });
  } finally { await restarted.stop(); }
});

test('concurrent distinct and duplicate publishes are atomic and idempotent', async t => {
  const app = fixture();
  t.after(app.stop);
  const a = state('Alpha'), b = state('Beta', { hosts: 'different.example.invalid' });
  const [left, right, duplicate] = await Promise.all([app.publish(a), app.publish(b), app.publish({
    files: Object.fromEntries(Object.entries(a.files).reverse()), description: a.description, version: 2,
  })]);
  assert.deepEqual([left.status, duplicate.status].sort(), [200, 201]);
  assert.equal(right.status, 201);
  const [one, two, retry] = await Promise.all([left.json(), right.json(), duplicate.json()]);
  assert.equal(one.state_id, retry.state_id);
  assert.notEqual(one.state_id, two.state_id);
  const summaries = (await app.list()).states;
  assert.deepEqual(summaries.map(item => item.state_id).sort(), [one.state_id, two.state_id].sort());
  for (const summary of summaries) {
    const fetched = await app.request(`${api}/${summary.state_id}`);
    assert.equal(fetched.status, 200);
    assert.deepEqual((await fetched.json()).files, summary.state_id === one.state_id ? a.files : b.files);
  }
});

test('a failure after staging the metadata and first chunk leaves no dangling listing', async t => {
  const app = fixture({ failure: true });
  t.after(app.stop);
  const input = state('inject commit failure', { hosts: await bundled('hosts') });
  const okay = await app.publish(state());
  assert.equal(okay.status, 201);
  const original = (await okay.json()).state_id;
  for (let attempt = 0; attempt < 2; attempt++) {
    assert.equal((await app.publish(input)).status, 500);
    assert.deepEqual((await app.list()).states.map(s => s.state_id), [original]);
    assert.equal((await app.request(`${api}/${original}`)).status, 200);
  }
});

test('catalog cursors page without duplicates when a newer state is published', async t => {
  const app = fixture();
  t.after(app.stop);
  for (let i = 0; i < 101; i++) assert.equal((await app.publish(state(`Row ${i}`))).status, 201);
  const first = await app.list();
  assert.equal(first.states.length, 100);
  assert.ok(first.next_before > 0);
  assert.equal((await app.publish(state('Newest'))).status, 201);
  const second = await app.list(`?before=${first.next_before}`);
  assert.deepEqual(second.states.map(s => s.description), ['Row 0']);
  assert.equal(second.next_before, null);
  assert.equal((await app.request(`${api}?before=0`)).status, 400);
});

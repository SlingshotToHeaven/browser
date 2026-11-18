import test from 'node:test';
import assert from 'node:assert/strict';
import { runInNewContext } from 'node:vm';
import { javaTextBlock } from './fixtures/java_text_block.mjs';

const file = '../app/src/main/java/com/privacy/browser/media/MediaPageScripts.java';
const options = javaTextBlock(file, 'OPTIONS_JS'), links = javaTextBlock(file, 'LINKS_JS');

function snapshot(script, nodes) {
  const visits = [];
  const document = {
    getElementsByTagName(tag) {
      visits.push(tag);
      return nodes[tag] || [];
    },
  };
  const disallowed = () => assert.fail('Snapshot must not read the network, listen or schedule anything');
  const env = { document, fetch: disallowed, XMLHttpRequest: disallowed, setInterval: disallowed,
    setTimeout: disallowed, addEventListener: disallowed, MutationObserver: disallowed,
  };
  const result = runInNewContext(script, env, { timeout: 1000 });
  assert.equal(env.fetch, disallowed);
  assert.equal(env.XMLHttpRequest, disallowed);
  return { visits, rows: JSON.parse(JSON.stringify(result)) };
}

const origin = 'https://media.invalid';

test('download options inspect only media and embeds, preserve signed spellings and dedupe', () => {
  const clip = `${origin}/Clip.mp4?SIG=A%2f&k=1`;
  const option = snapshot(options, {
    video: [{ currentSrc: clip, getElementsByTagName: () => [
      { src: clip, type: 'video/mp4' }, { src: `${origin}/Master?SIG=B%2F`, type: 'application/vnd.apple.mpegurl' },
      { src: `${origin}/Dash?SIG=D`, type: 'application/dash+xml' },
      { src: 'data:video/mp4,ignored', type: 'video/mp4' },
    ] }],
    audio: [{ src: `${origin}/Music`, getElementsByTagName: () => [] }],
    iframe: [{ src: 'https://player.vimeo.com/video/123?h=ab' }],
    img: 'never use this', a: 'never use this',
  });
  assert.deepEqual(option.visits, ['video', 'audio', 'iframe']);
  assert.deepEqual(option.rows, [
    { url: clip, type: 'video' }, { url: `${origin}/Master?SIG=B%2F`, type: 'hls' },
    { url: `${origin}/Dash?SIG=D`, type: 'dash' }, { url: `${origin}/Music`, type: 'audio' },
    { url: 'https://player.vimeo.com/video/123?h=ab', type: 'embed' },
  ]);
});

test('option snapshot visits at most 32 elements per media tag, eight sources each and 16 frames', () => {
  function cap(items, limit) {
    return new Proxy(items, { get(value, key) {
      assert.ok(Number(key) < limit, `Position ${key} past ${limit}`);
      return value[key];
    } });
  }
  const many = Array.from({ length: 500 }, (_, i) => ({
    src: `${origin}/video-${i}`,
    getElementsByTagName: () => cap(Array.from({ length: 100 }, (_, j) => ({ src: `${origin}/video-${i}/source-${j}` })), 8),
  }));
  const result = snapshot(options, { audio: cap(many, 32), video: cap(many, 32), iframe: cap(many, 16) });
  assert.equal(result.rows.length, 64);
});

test('explicit links keep major types without scraping text or growing the bridge reply unboundedly', () => {
  const names = ['Pack.zip', 'Paper.pdf', 'cover.png', 'clip.webm', 'song.mp3', 'web-page'];
  const rows = snapshot(links, { a: names.map(name => ({ href: `${origin}/${name}?tag=X`, getAttribute: () => 'T'.repeat(400) })) }).rows;
  assert.deepEqual(rows.map(r => r.type), ['archive', 'document', 'image', 'video', 'audio', 'link']);
  assert.ok(rows.every(r => r.url.endsWith('?tag=X') && r.title.length === 100));
  const heavy = snapshot(links, { a: Array.from({ length: 400 }, (_, i) => ({
    href: `${origin}/long-${i}?data=${'Z'.repeat(8000)}`, getAttribute: () => '',
  })) }).rows;
  assert.ok(heavy.length < 12);
  assert.ok(JSON.stringify(heavy).length <= 80 * 1024 + 2);
});

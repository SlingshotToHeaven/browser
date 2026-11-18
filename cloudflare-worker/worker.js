import { ruleError } from "./rules.js";

// Versioned, data-only filter snapshots. The Durable Object owns both listing and payload.
const PATH = '/api/v2/states';
const ID = /^v2_[0-9a-f]{64}$/;
const KiB = 1024, MiB = KiB * KiB;
const LIMITS = { hosts: 8 * MiB, network: 512 * KiB, cosmetic: 512 * KiB, user_filters: 512 * KiB };
const MAX_BODY = 14 * MiB;
const CHUNK = 256 * KiB; // well below the per-row SQLite limit
const MAX_STATES = 1000;
const MAX_BYTES = 256 * MiB;
const headers = {
  'Content-Type': 'application/json; charset=utf-8',
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type',
  'Cache-Control': 'no-store',
  'X-Content-Type-Options': 'nosniff',
};
const encoder = new TextEncoder();
const json = (data, status = 200) => new Response(JSON.stringify(data), { status, headers });
const fail = (status, error) => json({ error }, status);
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const wellFormed = value => typeof value === 'string' && !/[\u0000\uD800-\uDFFF]/u.test(value);

class InvalidInput extends Error {
  constructor(message, status = 400) { super(message); this.status = status; }
}

async function readSnapshot(request) {
  if (!/^application\/json(?:\s*;|$)/i.test(request.headers.get('Content-Type') || '')) {
    throw new InvalidInput('Expected application/json.', 415);
  }
  const advertised = request.headers.get('Content-Length');
  if (advertised !== null && Number(advertised) > MAX_BODY) throw new InvalidInput('Snapshot is too large.', 413);
  if (!request.body) throw new InvalidInput('Missing snapshot.');
  const reader = request.body.getReader();
  const decoder = new TextDecoder('utf-8', { fatal: true });
  let text = '', size = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_BODY) throw new InvalidInput('Snapshot is too large.', 413);
      text += decoder.decode(value, { stream: true });
    }
    text += decoder.decode();
  } catch (error) {
    await reader.cancel().catch(() => {});
    if (error instanceof InvalidInput) throw error;
    throw new InvalidInput('Invalid UTF-8 request body.');
  } finally {
    reader.releaseLock();
  }
  let input;
  try { input = JSON.parse(text); } catch { throw new InvalidInput('Malformed snapshot JSON.'); }
  if (!object(input) || Object.keys(input).length !== 3 || input.version !== 2
      || !wellFormed(input.description) || /[\p{Cc}\p{Cf}]/u.test(input.description) || input.description.length > 160 || !object(input.files)) {
    throw new InvalidInput('Expected a version 2 rule snapshot.');
  }
  const files = {};
  if (Object.keys(input.files).length !== Object.keys(LIMITS).length) {
    throw new InvalidInput('Expected all four rule sections and no executable files.');
  }
  for (const [key, limit] of Object.entries(LIMITS)) {
    const value = input.files[key];
    if (!wellFormed(value)) throw new InvalidInput('Unknown or invalid rule sections.');
    if (encoder.encode(value).length > limit) throw new InvalidInput(`${key} exceeds the section size limit.`, 413);
    const error = ruleError(key, value);
    if (error) throw new InvalidInput(error);
    files[key] = value;
  }
  return { version: 2, description: input.description.trim() || 'AdBlock state', files };
}

export class RuleStateCatalog {
  constructor(state) {
    this.storage = state.storage;
    this.sql = state.storage.sql;
    this.sql.exec(`CREATE TABLE IF NOT EXISTS states (
      seq INTEGER PRIMARY KEY AUTOINCREMENT,
      state_id TEXT UNIQUE NOT NULL,
      description TEXT NOT NULL,
      timestamp INTEGER NOT NULL,
      length INTEGER NOT NULL
    )`);
    this.sql.exec(`CREATE TABLE IF NOT EXISTS state_chunks (
      seq INTEGER NOT NULL REFERENCES states(seq),
      part INTEGER NOT NULL,
      data BLOB NOT NULL,
      PRIMARY KEY (seq, part)
    )`);
  }

  async fetch(request) {
    try {
      const url = new URL(request.url);
      if (request.method === 'POST' && url.pathname === PATH) {
        const snapshot = await readSnapshot(request);
        const hash = new Uint8Array(await crypto.subtle.digest('SHA-256', encoder.encode(JSON.stringify(snapshot))));
        const stateId = 'v2_' + Array.from(hash, byte => byte.toString(16).padStart(2, '0')).join('');
        const timestamp = Date.now();
        const bytes = encoder.encode(JSON.stringify({ ...snapshot, state_id: stateId, timestamp }));
        if (bytes.length > MAX_BODY) return fail(413, 'Encoded snapshot exceeds the storage limit.');
        const outcome = this.storage.transactionSync(() => {
          const prior = [...this.sql.exec('SELECT seq FROM states WHERE state_id = ?', stateId)];
          if (prior.length) return 'duplicate';
          const [totals] = this.sql.exec('SELECT COUNT(*) AS count, COALESCE(SUM(length), 0) AS size FROM states');
          if (totals.count >= MAX_STATES || totals.size + bytes.length > MAX_BYTES) return 'full';
          const [row] = this.sql.exec('INSERT INTO states(state_id, description, timestamp, length) VALUES (?, ?, ?, ?) RETURNING seq',
            stateId, snapshot.description, timestamp, bytes.length);
          for (let offset = 0, part = 0; offset < bytes.length; offset += CHUNK, part++) {
            this.sql.exec('INSERT INTO state_chunks(seq, part, data) VALUES (?, ?, ?)', row.seq, part,
              bytes.slice(offset, offset + CHUNK).buffer);
          }
          return 'stored';
        });
        if (outcome === 'full') return fail(507, 'Rule catalog is full; nothing was published.');
        return json({ version: 2, success: true, state_id: stateId, duplicate: outcome === 'duplicate' }, outcome === 'stored' ? 201 : 200);
      }
      if (request.method === 'GET' && url.pathname === PATH) {
        const cursor = url.searchParams.get('before');
        const before = cursor === null ? Number.MAX_SAFE_INTEGER : Number(cursor);
        if (!Number.isSafeInteger(before) || before < 1) return fail(400, 'Invalid list cursor.');
        const entries = [...this.sql.exec('SELECT seq, state_id, description, timestamp FROM states WHERE seq < ? ORDER BY seq DESC LIMIT 101', before)];
        const states = entries.slice(0, 100).map(({ state_id, description, timestamp }) => ({ state_id, description, timestamp }));
        return json({ version: 2, states, next_before: entries.length > 100 ? entries[99].seq : null });
      }
      if (request.method === 'GET' && url.pathname.startsWith(PATH + '/')) {
        const stateId = url.pathname.slice(PATH.length + 1);
        if (!ID.test(stateId)) return fail(404, 'State not found.');
        const [row] = this.sql.exec('SELECT seq, length FROM states WHERE state_id = ?', stateId);
        if (!row) return fail(404, 'State not found.');
        const blobs = [...this.sql.exec('SELECT data FROM state_chunks WHERE seq = ? ORDER BY part', row.seq)].map(chunk => chunk.data);
        const body = new Blob(blobs);
        if (body.size !== row.length) return fail(500, 'Stored snapshot is incomplete.');
        return new Response(body, { headers });
      }
      return fail(404, 'Not found.');
    } catch (error) {
      if (error instanceof InvalidInput) return fail(error.status, error.message);
      return fail(500, 'Catalog operation failed. A failed write did not publish a snapshot.');
    }
  }
}

export default {
  async fetch(request, env) {
    const path = new URL(request.url).pathname;
    if (request.method === 'OPTIONS') return new Response(null, { headers });
    if (request.method === 'GET' && ['/', '/api/health', '/api/v2/health'].includes(path)) {
      return json({ status: 'ok', service: 'AdBlock State Sync Worker', version: 2 });
    }
    if (path === '/api/states' || path.startsWith('/api/states/')) {
      // Legacy KV diffs cannot be made into exact snapshots without a known base.
      // Leave the namespace untouched and prevent old clients from applying/publishing diffs.
      return fail(410, 'Legacy state sync is retired. Upgrade the app and publish a reviewed rule snapshot.');
    }
    const index = path === PATH && (request.method === 'GET' || request.method === 'POST');
    const detail = request.method === 'GET' && path.startsWith(PATH + '/');
    if (!index && !detail) return fail(404, 'Not found.');
    if (!env.RULE_STATES) return fail(503, 'Rule snapshot storage is not configured.');
    if (request.method === 'POST' && env.ALLOW_RULE_PUBLISH !== 'true') return fail(403, 'Publishing is disabled by the operator.');
    try {
      return await env.RULE_STATES.get(env.RULE_STATES.idFromName('catalog-v2')).fetch(request);
    } catch {
      // A response can be lost after commit. Idempotent retries with the same input recover the ID.
      return fail(503, 'Storage unavailable. Retry the same snapshot to check its publication.');
    }
  },
};

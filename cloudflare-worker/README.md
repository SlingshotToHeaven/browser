# Filter snapshot catalog (v2)

This optional Cloudflare Worker shares **rule data only**. It has no script, scriptlet, or path-based file API. The app validates a downloaded snapshot independently, then previews the local result. A catalog entry or hash is not approval to install anything.

## Verify locally

Use Node.js 22 or later and npm. These commands launch a temporary Workerd with Miniflare; they do **not** deploy or use a Cloudflare account.

```sh
npm ci --prefix cloudflare-worker
npm test --prefix cloudflare-worker
```

Run the commands above from the repository root. The tests use disposable SQLite storage and cover validation, large snapshots, restart, concurrency, stale pagination boundaries, and rollback after a partial chunk write.

## Deploy or migrate as an operator

1. Use a separate staging Worker first. Deploy the module project with a current, authenticated Wrangler from `cloudflare-worker/`; use `wrangler deploy --config wrangler.toml` only after reviewing the target account and project. Do not paste `worker.js` into a single-file editor. `rules.js` is an imported module. The checked-in config creates the `RULE_STATES` binding and the SQLite Durable Object class `RuleStateCatalog` via migration `v2-rule-catalog`.
2. Keep `ALLOW_RULE_PUBLISH = "false"` initially. Check `GET /api/v2/health` and `GET /api/v2/states` on staging. If you administer an older v1 deployment, keep its KV namespace and backups untouched. There is **no automatic KV import**: a v1 diff may depend on an unknown base or refer to executable files. `/api/states` and its descendants return **410** on the new Worker. Older app versions cannot use v2; don't point them at the new catalog expecting v1 compatibility.
3. In a v2 app, open **Ad Blocker → Modules → Cloud States**, set the **HTTPS origin** of the staging Worker, and check the list and review UI. Use disposable rule snapshots. To move rules from an existing installation, review its four local rule texts and explicitly publish a fresh v2 snapshot instead of reusing a legacy diff.
4. Enable `ALLOW_RULE_PUBLISH = "true"` only if the catalog is intentionally open for publishing. **Reads are public and there is no app publisher authentication**. The flag opt-in, validation and quotas are not access controls. Don't publish rules or comments containing private hosts, signed URLs or browsing details. Restrict writers at your deployment boundary before operating an authenticated/private catalog; the app has no credential-sharing flow for catalog requests.

This repository change does not deploy, mutate, or delete any live catalog. If rolling back, turn publishing off instead of reopening an unsafe diff import. Preserve the SQLite catalog and legacy KV data separately.

## API and retries

| Route | Contract |
| --- | --- |
| `GET /api/v2/health` | Service and `version: 2` |
| `GET /api/v2/states[?before=N]` | Up to 100 newest entries and a `next_before` cursor (or `null`) |
| `GET /api/v2/states/{state_id}` | The complete snapshot, plus its ID and timestamp |
| `POST /api/v2/states` | Opt-in only. A v2 snapshot with exactly the four named string sections |

Example request body with fixture rule data:

```json
{"version":2,"description":"Fixture","files":{"hosts":"tracker.example.invalid\n","network":"@@||example.invalid/Case%2Fpath^\n","cosmetic":"##.ad, .sponsor\n","user_filters":""}}
```

- Limits: 8 MiB/450,000 host rules, 512 KiB/12,000 rules per other section, and 14 MiB encoded request/response. The catalog is capped at 1,000 states and 256 MiB of stored snapshot bytes. Unsupported rules or files fail validation; no scripts travel through this endpoint.
- The server hashes the exact four texts in a fixed section order and the normalized description to make `v2_<sha256>`. Metadata and all payload chunks become visible together. A fresh publish returns **201**; the same validated input returns **200** and its existing ID without adding a row.
- A timeout, disconnect, or invalid response does not tell you whether the server committed. The app reports **Publication not confirmed** and can retry the same reviewed snapshot, description and origin on request. Do not change the description or use a new local snapshot to retry a lost response if you mean to recover the same ID.

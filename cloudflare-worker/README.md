# Cloudflare Worker: AdBlock State Sync

This Cloudflare Worker provides the backend sync and storage engine for community ad-blocking states and diffs for wv-browser.

## Quick Setup (2 Minutes)

### Option 1: Via Cloudflare Dashboard (No CLI needed)
1. Log in to [dash.cloudflare.com](https://dash.cloudflare.com).
2. Go to **Workers & Pages** -> **Create application** -> **Create Worker**.
3. Name it `adblock-state-sync` and click **Deploy**.
4. In the worker settings, go to **Settings** -> **Variables and Secrets** -> **KV Namespace Bindings**:
   - Create a KV Namespace named `ADBLOCK_STATES`.
   - Add a binding with Variable name `ADBLOCK_STATES` pointing to that KV namespace.
5. Click **Edit code**, paste the contents of `worker.js`, and click **Deploy**.
6. Copy your worker URL (e.g., `https://adblock-state-sync.<your-subdomain>.workers.dev`).
7. Enter this URL into wv-browser in **Ad Blocker -> Modules -> Cloud States -> Settings (Endpoint URL)**.

### Option 2: Via Wrangler CLI
```bash
cd cloudflare-worker
npx wrangler kv:namespace create ADBLOCK_STATES
# Copy the returned id into wrangler.toml
npx wrangler deploy
```

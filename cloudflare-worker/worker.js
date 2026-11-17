/**
 * Cloudflare Worker for wv-browser AdBlock State Sync
 *
 * Provides endpoints for listing, fetching, and committing modular
 * ad-blocking states and diffs using Cloudflare KV.
 *
 * Bindings required in Cloudflare dashboard or wrangler.toml:
 *   - KV Namespace bound to: ADBLOCK_STATES
 */

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const corsHeaders = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type",
    };

    if (request.method === "OPTIONS") {
      return new Response(null, { headers: corsHeaders });
    }

    // Health check endpoint
    if (url.pathname === "/" || url.pathname === "/api/health") {
      return new Response(JSON.stringify({ status: "ok", service: "AdBlock State Sync Worker" }), {
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // GET /api/states - List latest community state commits
    if (request.method === "GET" && url.pathname === "/api/states") {
      try {
        const indexRaw = await env.ADBLOCK_STATES.get("__INDEX__");
        const index = indexRaw ? JSON.parse(indexRaw) : [];
        return new Response(JSON.stringify(index), {
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      } catch (e) {
        return new Response(JSON.stringify({ error: e.message }), {
          status: 500,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }
    }

    // GET /api/states/:id - Retrieve full state diff payload
    if (request.method === "GET" && url.pathname.startsWith("/api/states/")) {
      try {
        const stateId = url.pathname.replace("/api/states/", "").trim();
        const stateData = await env.ADBLOCK_STATES.get(stateId);
        if (!stateData) {
          return new Response(JSON.stringify({ error: "State not found: " + stateId }), {
            status: 404,
            headers: { ...corsHeaders, "Content-Type": "application/json" },
          });
        }
        return new Response(stateData, {
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      } catch (e) {
        return new Response(JSON.stringify({ error: e.message }), {
          status: 500,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }
    }

    // POST /api/states - Commit and upload a new state diff
    if (request.method === "POST" && url.pathname === "/api/states") {
      try {
        const body = await request.json();
        const stateId = "state_" + Date.now() + "_" + Math.random().toString(36).substring(2, 8);
        body.state_id = stateId;
        body.timestamp = Date.now();

        // 1. Store the full state body into KV
        await env.ADBLOCK_STATES.put(stateId, JSON.stringify(body));

        // 2. Prepend metadata summary to recent states index (caps at 100 entries)
        let filesChangedCount = typeof body.files_changed === 'number' ? body.files_changed : 0;
        if (filesChangedCount === 0 && body.diffs) {
          for (const k of Object.keys(body.diffs)) {
            const fd = body.diffs[k] || {};
            const a = (fd.added || fd.added_lines || []).length;
            const r = (fd.removed || fd.removed_lines || []).length;
            if (a > 0 || r > 0) filesChangedCount++;
          }
        }

        const summary = {
          state_id: stateId,
          description: body.description || "Untitled State",
          author: body.author || "Anonymous",
          timestamp: body.timestamp,
          files_changed: filesChangedCount,
          stats: body.stats || {}
        };

        const indexRaw = await env.ADBLOCK_STATES.get("__INDEX__");
        let index = indexRaw ? JSON.parse(indexRaw) : [];
        index.unshift(summary);
        if (index.length > 100) {
          index = index.slice(0, 100);
        }
        await env.ADBLOCK_STATES.put("__INDEX__", JSON.stringify(index));

        return new Response(JSON.stringify({ success: true, state_id: stateId }), {
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      } catch (e) {
        return new Response(JSON.stringify({ error: e.message }), {
          status: 400,
          headers: { ...corsHeaders, "Content-Type": "application/json" },
        });
      }
    }

    return new Response(JSON.stringify({ error: "Not found" }), {
      status: 404,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }
};

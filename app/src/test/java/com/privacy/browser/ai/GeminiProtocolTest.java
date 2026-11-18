package com.privacy.browser.ai;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

/** Fake provider replies only; no keys, sockets, or live Google requests. */
public class GeminiProtocolTest {
    @Test public void structuredRoundTripPreservesSignaturesCallsAndResponses() throws Exception {
        String turn = "{\"role\":\"model\",\"parts\":[{\"text\":\"preface\"},{\"text\":\"hidden\",\"thought\":true},"
                + "{\"functionCall\":{\"name\":\"nav_get_active_tab_info\",\"args\":{},\"id\":\"one\"},\"thoughtSignature\":\"fixture\"},"
                + "{\"functionCall\":{\"name\":\"nav_reload_page\",\"args\":{},\"id\":\"two\"}}]}";
        GeminiProtocol.Response response = GeminiProtocol.parse(envelope(turn), true);
        assertEquals("preface", response.text);
        AgentToolBatch batch = new AgentToolBatch(response.content);
        assertTrue(batch.complete(1, AgentToolBatch.terminal("stale", "Changed document")));
        assertNull(batch.finish());
        assertTrue(batch.complete(0, AgentToolBatch.terminal("denied", "Not approved")));
        JSONArray parts = new JSONArray().put(new JSONObject().put("role", "user").put("parts", new JSONArray().put(new JSONObject().put("text", "Fixture"))));
        JSONArray paired = batch.finish();
        parts.put(paired.getJSONObject(0)).put(paired.getJSONObject(1));
        JSONObject outbound = GeminiProtocol.payload(parts, new JSONArray(), true);
        assertTrue(outbound.has("tools"));
        JSONArray back = outbound.getJSONArray("contents");
        assertEquals(new JSONObject(turn).toString(), back.getJSONObject(1).toString());
        assertEquals("two", back.getJSONObject(2).getJSONArray("parts").getJSONObject(1).getJSONObject("functionResponse").getString("id"));
        assertNull(batch.finish());
    }

    @Test public void invalidAndUnsupportedContentDoNotBecomeProviderContext() throws Exception {
        assertThrows(IOException.class, () -> GeminiProtocol.parse(envelope("{\"role\":\"model\",\"parts\":[{\"inlineData\":{\"data\":\"fixture\"}}]}"), true));
        assertThrows(IOException.class, () -> GeminiProtocol.parse(envelope("{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"nav_reload_page\"}}]}"), false));
        assertThrows(IOException.class, () -> GeminiProtocol.parse(envelope("{\"role\":\"user\",\"parts\":[{\"text\":\"Fixture\"}]}"), true));
        assertFalse(GeminiProtocol.payload(new JSONArray(), new JSONArray(), false).has("tools"));
    }

    @Test public void credentialModelAndModeChangesCannotReuseTheSameRequestScope() {
        GeminiSettings run = GeminiSettings.create("fixture-model", "fixture-key", true);
        assertNotNull(run);
        assertTrue(run.matches(GeminiSettings.create("fixture-model", "fixture-key", true)));
        assertFalse(run.matches(GeminiSettings.create("fixture-model", "second-key", true)));
        assertFalse(run.matches(GeminiSettings.create("second-model", "fixture-key", true)));
        assertFalse(run.matches(GeminiSettings.create("fixture-model", "fixture-key", false)));
        assertFalse(run.matches(null));
        assertNull(GeminiSettings.create("other/route", "fixture-key", false));
        assertNull(GeminiSettings.create("fixture-model", "two\nheaders", false));
    }

    private static String envelope(String content) { return "{\"candidates\":[{\"content\":" + content + "}]}"; }
}

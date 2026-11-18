package com.privacy.browser.ai;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class AgentToolBatchTest {
    @Test public void outOfOrderResultsStayCorrelatedAndFinishOnce() throws Exception {
        JSONObject content = new JSONObject("{\"role\":\"model\",\"parts\":[{\"text\":\"Checking\"},"
                + "{\"functionCall\":{\"name\":\"dom_find_elements\",\"id\":\"a\",\"args\":{\"css_selector\":\".one\"}},\"thoughtSignature\":\"fixture\"},"
                + "{\"functionCall\":{\"name\":\"dom_find_elements\",\"id\":\"b\",\"args\":{\"css_selector\":\".two\"}}}]}" );
        AgentToolBatch batch = new AgentToolBatch(content);
        assertTrue(batch.complete(1, AgentToolBatch.terminal("denied", "Not approved")));
        assertNull(batch.finish());
        assertFalse(batch.complete(1, AgentToolBatch.terminal("success", "Duplicate")));
        assertTrue(batch.complete(0, new JSONObject().put("items", new JSONArray().put("title"))));
        JSONArray pair = batch.finish();
        assertEquals(content.toString(), pair.getJSONObject(0).toString());
        JSONArray parts = pair.getJSONObject(1).getJSONArray("parts");
        assertEquals(2, parts.length());
        assertEquals("a", parts.getJSONObject(0).getJSONObject("functionResponse").getString("id"));
        assertEquals("title", parts.getJSONObject(0).getJSONObject("functionResponse").getJSONObject("response").getJSONArray("items").get(0));
        assertEquals("b", parts.getJSONObject(1).getJSONObject("functionResponse").getString("id"));
        assertEquals("denied", parts.getJSONObject(1).getJSONObject("functionResponse").getJSONObject("response").getString("status"));
        assertNull(batch.finish());
    }

    @Test public void terminalTimeoutErrorAndStaleResultsDoNotCreateSyntheticCalls() throws Exception {
        JSONArray parts = new JSONArray();
        for (String name : new String[]{"nav_open_url", "nav_reload_page", "dom_find_elements"}) {
            parts.put(new JSONObject().put("functionCall", new JSONObject().put("name", name)));
        }
        AgentToolBatch batch = new AgentToolBatch(new JSONObject().put("role", "model").put("parts", parts));
        String[] outcomes = {"timeout", "error", "stale"};
        for (int i = 0; i < outcomes.length; i++) assertTrue(batch.complete(i, AgentToolBatch.terminal(outcomes[i], "Fixture")));
        JSONArray responses = batch.finish().getJSONObject(1).getJSONArray("parts");
        for (int i = 0; i < outcomes.length; i++) {
            JSONObject response = responses.getJSONObject(i).getJSONObject("functionResponse");
            assertEquals(batch.calls.get(i).name, response.getString("name"));
            assertFalse(response.has("id"));
            assertEquals(outcomes[i], response.getJSONObject("response").getString("status"));
        }
        assertFalse(batch.complete(0, AgentToolBatch.terminal("ok", "Late")));
    }
}

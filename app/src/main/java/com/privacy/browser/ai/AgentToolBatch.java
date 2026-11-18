package com.privacy.browser.ai;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Correlates tool results by original part order and echoes provider IDs when present. */
final class AgentToolBatch {
    private static final int MAX_CALLS = 8;
    private static final int MAX_RESULT_CHARS = 24 * 1024;

    static final class Call {
        final String name, id;
        private final String arguments;
        Call(String name, String id, JSONObject arguments) {
            this.name = name;
            this.id = id;
            this.arguments = arguments == null ? "{}" : arguments.toString();
        }
        JSONObject args() throws JSONException { return new JSONObject(arguments); }
    }

    final List<Call> calls;
    private final String model;
    private final String[] responses;
    private int remaining;
    private boolean drained;

    AgentToolBatch(JSONObject content) throws JSONException {
        model = content.toString();
        JSONArray parts = content.getJSONArray("parts");
        List<Call> list = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.getJSONObject(i);
            if (!part.has("functionCall")) continue;
            JSONObject call = part.getJSONObject("functionCall");
            Object name = call.opt("name"), id = call.opt("id"), args = call.opt("args");
            if (list.size() == MAX_CALLS || !(name instanceof String) || !((String) name).matches("[a-zA-Z0-9_-]{1,64}")
                    || id != null && (!(id instanceof String) || ((String) id).isEmpty() || ((String) id).length() > 128 || !ids.add((String) id))
                    || args != null && !(args instanceof JSONObject)) throw new IllegalArgumentException("Malformed or oversized tool turn.");
            list.add(new Call((String) name, (String) id, (JSONObject) args));
        }
        this.calls = Collections.unmodifiableList(list);
        remaining = list.size();
        responses = new String[remaining];
    }

    boolean complete(int index, JSONObject response) throws JSONException {
        if (drained || index < 0 || index >= responses.length || responses[index] != null) return false;
        String text = response.toString();
        if (text.length() > MAX_RESULT_CHARS) text = terminal("result_too_large", "Tool result exceeded the response limit.").toString();
        responses[index] = text;
        remaining--;
        return true;
    }

    JSONArray finish() throws JSONException {
        if (remaining != 0 || drained || calls.isEmpty()) return null;
        JSONArray parts = new JSONArray();
        for (int i = 0; i < calls.size(); i++) {
            Call call = calls.get(i);
            JSONObject function = new JSONObject().put("name", call.name).put("response", new JSONObject(responses[i]));
            if (call.id != null) function.put("id", call.id);
            parts.put(new JSONObject().put("functionResponse", function));
        }
        drained = true;
        return new JSONArray().put(new JSONObject(model)).put(new JSONObject().put("role", "user").put("parts", parts));
    }

    static JSONObject terminal(String status, String reason) {
        try { return new JSONObject().put("status", status).put("message", reason); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
}

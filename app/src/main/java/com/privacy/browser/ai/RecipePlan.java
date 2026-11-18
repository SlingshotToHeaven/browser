package com.privacy.browser.ai;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** No control flow, raw scripts, arbitrary waits, shell, or nested scheduling. */
final class RecipePlan {
    static final int MAX_RECIPE = 8192;
    private static final int MAX_STEPS = 12;
    final List<AssistantAction> steps;
    final String json;

    private RecipePlan(List<AssistantAction> steps, String json) {
        this.steps = Collections.unmodifiableList(steps);
        this.json = json;
    }

    static RecipePlan parse(String text) throws JSONException {
        if (text == null || text.length() > MAX_RECIPE || !boundedJson(text, 4)) {
            throw new IllegalArgumentException("Enter a bounded recipe JSON array.");
        }
        JSONTokener reader = new JSONTokener(text);
        Object value = reader.nextValue();
        if (!(value instanceof JSONArray) || reader.nextClean() != 0) throw new IllegalArgumentException("Expected only a recipe JSON array.");
        JSONArray array = (JSONArray) value;
        if (array.length() == 0 || array.length() > MAX_STEPS) throw new IllegalArgumentException("A recipe must have 1–12 steps.");
        JSONArray normalized = new JSONArray();
        List<AssistantAction> steps = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject raw = array.getJSONObject(i);
            Object name = raw.opt("action"), args = raw.opt("params");
            if (!(name instanceof String) || !(args instanceof JSONObject) || raw.length() != 2) {
                throw new IllegalArgumentException("Each step needs only action and params.");
            }
            AssistantAction action = AssistantAction.parse((String) name, (JSONObject) args, true);
            normalized.put(new JSONObject().put("action", action.kind.name).put("params", action.args()));
            steps.add(action);
        }
        String json = normalized.toString();
        if (json.length() > MAX_RECIPE) throw new IllegalArgumentException("Normalized recipe exceeds its size limit.");
        return new RecipePlan(steps, json);
    }

    static boolean boundedJson(String text, int maxDepth) {
        int depth = 0;
        boolean inside = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inside) {
                if (ch == '\\') i++;
                else if (ch == '"') inside = false;
                else if (ch < ' ') return false;
            } else if (ch == '"') inside = true;
            else if (ch == '[' || ch == '{') { if (++depth > maxDepth) return false; }
            else if (ch == ']' || ch == '}') { if (--depth < 0) return false; }
            else if (ch == '/' || ch == '\'' || ch == '#' || ch == '\\' || ch == ';' || ch == '=' || ch == 0) return false;
        }
        return !inside && depth == 0;
    }
}

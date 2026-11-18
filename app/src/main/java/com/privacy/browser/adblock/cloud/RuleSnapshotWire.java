package com.privacy.browser.adblock.cloud;

import com.privacy.browser.adblock.rules.FilterSnapshot;
import com.privacy.browser.adblock.rules.FilterSnapshot.Part;

import org.json.JSONObject;

import java.io.IOException;
import java.util.EnumMap;
import java.util.regex.Pattern;

/** The v2 wire schema maps section names to typed data, never to caller-chosen paths. */
final class RuleSnapshotWire {
    static final int MAX_BODY = 14 * 1024 * 1024;
    private static final Pattern ID = Pattern.compile("v2_[0-9a-f]{64}");

    static FilterSnapshot decode(JSONObject result, String id) throws Exception {
        requireV2(result);
        if (!validId(id).equals(result.opt("state_id")) || result.length() != 5
                || !(result.opt("description") instanceof String) || !(result.opt("timestamp") instanceof Number)) {
            throw new IOException("Catalog returned an unexpected state.");
        }
        validDescription(result.getString("description"));
        JSONObject files = result.getJSONObject("files");
        if (files.length() != Part.values().length) throw new IOException("Expected only the four rule sections, not executable files.");
        EnumMap<Part, String> text = new EnumMap<>(Part.class);
        for (Part part : Part.values()) {
            Object value = files.opt(part.key);
            if (!(value instanceof String)) throw new IOException("Invalid rule section: " + part.key);
            text.put(part, (String) value);
        }
        return FilterSnapshot.parse(text, true);
    }

    static JSONObject encode(String description, FilterSnapshot snapshot) throws Exception {
        snapshot.requireSupported();
        JSONObject parts = new JSONObject();
        for (Part part : Part.values()) parts.put(part.key, snapshot.text(part));
        return new JSONObject().put("version", 2).put("description", validDescription(description)).put("files", parts);
    }

    static String validDescription(String value) {
        if (value.length() > 160) throw new IllegalArgumentException("Description must have at most 160 characters.");
        if (value.codePoints().anyMatch(ch -> Character.isISOControl(ch) || Character.getType(ch) == Character.SURROGATE
                || Character.getType(ch) == Character.FORMAT)) throw new IllegalArgumentException("Invalid description text.");
        return value;
    }

    static String validId(String id) throws IOException {
        if (id == null || !ID.matcher(id).matches()) throw new IOException("Invalid version 2 state ID.");
        return id;
    }

    static void requireV2(JSONObject object) throws IOException {
        Object version = object.opt("version");
        if (!(version instanceof Number) || ((Number) version).doubleValue() != 2) throw new IOException("This catalog does not support filter snapshots v2.");
    }
}

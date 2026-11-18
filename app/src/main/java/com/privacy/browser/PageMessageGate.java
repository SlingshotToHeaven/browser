package com.privacy.browser;

import java.util.Objects;
import java.util.UUID;

/** Scoped, bounded page inputs. Caller must also verify the live view/document. */
final class PageMessageGate {
    static final int MAX_MESSAGE = 128 * 1024;
    static final int MAX_URL = 8192;
    private static final long PICKER_LIFETIME_MS = 60_000;
    private static final int BURST = 32;

    final String token = UUID.randomUUID().toString();
    private final String origin;
    private String picker;
    private String pickerKind;
    private long expires;
    private long windowStart;
    private int used;

    PageMessageGate(String origin) {
        this.origin = Objects.requireNonNull(origin);
    }

    boolean allowsFrame(boolean mainFrame, String sourceOrigin, String payload) {
        return mainFrame && origin.equals(sourceOrigin) && payload != null
                && !payload.isEmpty() && payload.length() <= MAX_MESSAGE;
    }

    boolean admitsPayload(long now) {
        if (now - windowStart >= 1000L) {
            windowStart = now;
            used = 0;
        }
        if (used >= BURST) return false;
        used++;
        return true;
    }

    // Android's JSON parser is recursive. Reject deeply nested messages before entering it.
    static boolean shallowObject(String data) {
        if (data.isEmpty() || data.charAt(0) != '{') return false;
        boolean quoted = false;
        int depth = 0;
        for (int i = 0; i < data.length(); i++) {
            char ch = data.charAt(i);
            if (quoted) {
                if (ch == '\\') i++;
                else if (ch == '"') quoted = false;
            } else if (ch == '"') quoted = true;
            else if (ch == '\'' || ch == '/' || ch == '#' || ch == ';' || ch == '=' || ch == '\\') return false;
            else if (ch == '{' || ch == '[') {
                if (++depth > 8) return false;
            } else if (ch == '}' || ch == ']') {
                if (--depth < 0) return false;
            }
        }
        return !quoted && depth == 0;
    }

    String armPicker(String kind, long now) {
        pickerKind = kind;
        picker = UUID.randomUUID().toString();
        expires = now + PICKER_LIFETIME_MS;
        return picker;
    }

    boolean consumePicker(String kind, String token, long now) {
        if (picker == null || !picker.equals(token) || !pickerKind.equals(kind) || now >= expires) return false;
        cancelPicker();
        return true;
    }

    void cancelPicker() {
        picker = null;
        pickerKind = null;
    }
}

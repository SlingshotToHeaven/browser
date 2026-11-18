package com.privacy.browser;

import java.util.function.Supplier;

/** One plain-text snapshot per foreground address-bar editing session. */
final class OmniboxClipboard {
    private boolean inspected;
    private String text;

    // Only call after a quiet, nonblank request is still eligible to show rows.
    void inspect(boolean incognito, Supplier<CharSequence> clipboard) {
        if (incognito || inspected) return;
        inspected = true;
        CharSequence value = clipboard.get();
        if (value != null && value.length() <= OmniboxRequests.MAX_TEXT_CHARS) text = value.toString().trim();
    }

    String offer(boolean incognito, String query) {
        return incognito || text == null || text.isEmpty() || text.equalsIgnoreCase(query) ? null : text;
    }

    void reset() {
        inspected = false;
        text = null;
    }
}

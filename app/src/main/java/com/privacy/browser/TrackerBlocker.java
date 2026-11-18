package com.privacy.browser;

import android.content.Context;

import com.privacy.browser.adblock.AdBlockManager;

/** Only construct on the startup worker. No uninitialized blocker can reach a WebView client. */
final class TrackerBlocker {
    private final AdBlockManager manager;

    TrackerBlocker(Context context) {
        manager = AdBlockManager.getInstance(context);
        manager.initialize();
    }

    AdBlockManager manager() { return manager; }
}

package com.privacy.browser;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;

import com.privacy.browser.adblock.AdBlockManager;

/**
 * Backward-compatible facade delegating to the unified AdBlockManager engine.
 */
public class TrackerBlocker {

    private AdBlockManager adBlockManager;

    public void initialize(@NonNull Context context) {
        adBlockManager = AdBlockManager.getInstance(context);
        adBlockManager.initialize();
    }

    public boolean isBlocked(Uri uri) {
        if (adBlockManager == null) return false;
        return adBlockManager.isBlocked(uri, null);
    }

    public boolean isBlocked(Uri uri, String topPageUrl) {
        if (adBlockManager == null) return false;
        return adBlockManager.isBlocked(uri, topPageUrl);
    }

    public int getRuleCount() {
        return adBlockManager != null ? adBlockManager.getRuleCount() : 0;
    }
}

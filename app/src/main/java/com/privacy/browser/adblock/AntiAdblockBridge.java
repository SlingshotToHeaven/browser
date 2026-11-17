package com.privacy.browser.adblock;

import android.webkit.JavascriptInterface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * JavaScript interface receiving real-time notifications of defeated anti-adblock scripts and probes.
 */
public class AntiAdblockBridge {

    public interface DefeatListener {
        void onAntiAdblockDefeated(String tabId, String detector, String details);
    }

    public interface TopUrlProvider {
        @Nullable String getTopUrl();
    }

    private final String tabId;
    private final AdBlockManager adBlockManager;
    private final TopUrlProvider topUrlProvider;
    private final DefeatListener listener;

    public AntiAdblockBridge(@NonNull AdBlockManager adBlockManager,
                             @Nullable String tabId,
                             @Nullable TopUrlProvider topUrlProvider,
                             @Nullable DefeatListener listener) {
        this.adBlockManager = adBlockManager;
        this.tabId = tabId;
        this.topUrlProvider = topUrlProvider;
        this.listener = listener;
    }

    public AntiAdblockBridge(@NonNull AdBlockManager adBlockManager, @Nullable String tabId, @Nullable DefeatListener listener) {
        this(adBlockManager, tabId, null, listener);
    }

    @JavascriptInterface
    public boolean isAdBlockActive(String url) {
        if (!adBlockManager.isGlobalEnabled()) {
            return false;
        }
        String top = topUrlProvider != null ? topUrlProvider.getTopUrl() : null;
        if (top != null && !top.isEmpty() && !adBlockManager.isAdBlockActiveForSite(top)) {
            return false;
        }
        if (url != null && !url.isEmpty() && !adBlockManager.isAdBlockActiveForSite(url)) {
            return false;
        }
        return true;
    }

    @JavascriptInterface
    public boolean isCosmeticEnabled(String url) {
        if (!adBlockManager.isGlobalEnabled() || !adBlockManager.isCosmeticFilteringEnabled()) {
            return false;
        }
        String top = topUrlProvider != null ? topUrlProvider.getTopUrl() : null;
        if (top != null && !top.isEmpty()) {
            if (!adBlockManager.isAdBlockActiveForSite(top) || !adBlockManager.isSiteCosmeticEnabled(top)) {
                return false;
            }
        }
        if (url != null && !url.isEmpty()) {
            if (!adBlockManager.isAdBlockActiveForSite(url) || !adBlockManager.isSiteCosmeticEnabled(url)) {
                return false;
            }
        }
        return true;
    }

    @JavascriptInterface
    public void reportAntiAdblockDefeated(String detector, String details) {
        if (detector != null) {
            adBlockManager.recordNeutralizedAntiAdblock(tabId, detector, details != null ? details : "");
            if (listener != null) {
                listener.onAntiAdblockDefeated(tabId, detector, details);
            }
        }
    }
}

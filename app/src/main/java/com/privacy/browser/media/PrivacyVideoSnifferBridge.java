package com.privacy.browser.media;

import android.webkit.JavascriptInterface;

/**
 * JavaScript interface injected into WebView to listen for real-time <video> events,
 * HTML5 media playback, and client-side (SPA) navigation changes.
 */
public class PrivacyVideoSnifferBridge {

    public interface VideoCallback {
        void onVideoDetected(String pageUrl, String title, String videoSrc);
        void onPageUrlChanged(String pageUrl, String title);
        void onYouTubePlayerResponse(String pageUrl, String title, String jsonResponse);
    }

    private final VideoCallback callback;

    public PrivacyVideoSnifferBridge(VideoCallback callback) {
        this.callback = callback;
    }

    @JavascriptInterface
    public void onVideoFound(String pageUrl, String title, String videoSrc) {
        if (callback != null) {
            callback.onVideoDetected(pageUrl, title, videoSrc);
        }
    }

    @JavascriptInterface
    public void onPageUrlChanged(String pageUrl, String title) {
        if (callback != null) {
            callback.onPageUrlChanged(pageUrl, title);
        }
    }

    @JavascriptInterface
    public void onYouTubeData(String pageUrl, String title, String jsonResponse) {
        if (callback != null) {
            callback.onYouTubePlayerResponse(pageUrl, title, jsonResponse);
        }
    }
}

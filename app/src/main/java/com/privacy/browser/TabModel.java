package com.privacy.browser;

import java.util.UUID;

/**
 * Encapsulates an active browser tab and its dedicated WebView instance.
 */
public class TabModel {

    private final String id;
    private volatile String title;
    private volatile String url;
    private final PrivacyWebView webView;
    private boolean isDesktopMode = false;
    private boolean isInitialized = false;
    private boolean isIncognito = false;
    private android.graphics.Bitmap thumbnail = null;

    public TabModel(PrivacyWebView webView, String initialUrl) {
        this.id = UUID.randomUUID().toString();
        this.webView = webView;
        this.url = initialUrl;
        this.title = "New Tab";
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title != null && !title.isEmpty() ? title : "Untitled";
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public PrivacyWebView getWebView() {
        return webView;
    }

    public boolean isDesktopMode() {
        return isDesktopMode;
    }

    public void setDesktopMode(boolean desktopMode) {
        isDesktopMode = desktopMode;
    }

    public boolean isInitialized() {
        return isInitialized;
    }

    public void setInitialized(boolean initialized) {
        isInitialized = initialized;
    }

    public boolean isIncognito() {
        return isIncognito;
    }

    public void setIncognito(boolean incognito) {
        isIncognito = incognito;
    }

    public android.graphics.Bitmap getThumbnail() {
        return thumbnail;
    }

    public void setThumbnail(android.graphics.Bitmap thumbnail) {
        if (this.thumbnail != null && this.thumbnail != thumbnail && !this.thumbnail.isRecycled()) {
            this.thumbnail.recycle();
        }
        this.thumbnail = thumbnail;
    }

    public void captureThumbnail() {
        if (webView == null || webView.getWidth() <= 0 || webView.getHeight() <= 0) return;
        try {
            int w = Math.min(webView.getWidth(), 480);
            int h = (int) (w * ((float) webView.getHeight() / webView.getWidth()));
            if (h <= 0) h = 320;
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.RGB_565);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
            float scaleX = (float) w / webView.getWidth();
            float scaleY = (float) h / webView.getHeight();
            canvas.scale(scaleX, scaleY);
            webView.draw(canvas);
            setThumbnail(bmp);
        } catch (Throwable ignored) {}
    }

    public void destroy() {
        if (thumbnail != null && !thumbnail.isRecycled()) {
            thumbnail.recycle();
            thumbnail = null;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
        }
        com.privacy.browser.media.MediaSniffer.clearTab(id);
    }
}

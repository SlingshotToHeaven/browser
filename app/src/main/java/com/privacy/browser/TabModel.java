package com.privacy.browser;

import com.privacy.downloader.interceptor.BlobDownloadManager;

import com.privacy.browser.adblock.AdBlockManager;
import com.privacy.browser.adblock.WebScriptPolicy;
import com.privacy.browser.comic.ComicManager;

import java.util.UUID;

/**
 * A tab row can exist without a live page or WebView.
 */
public class TabModel {

    private final String id;
    private volatile String title;
    final TabNavigation<PrivacyWebView> navigation;
    private TabSession.Row recovery; // metadata only until selected, never a live page
    TabPageMessages messages;
    WebScriptPolicy scriptPolicy;
    private BlobDownloadManager blobDownloads;
    private boolean isDesktopMode = false;
    private boolean isInitialized = false;
    private boolean isIncognito = false;
    long lastLiveUse;
    boolean keepLive; // only for the lifetime of this WebView, never persisted

    public TabModel(PrivacyWebView webView, String initialUrl) {
        id = UUID.randomUUID().toString();
        navigation = new TabNavigation<>(webView, initialUrl);
        title = "New Tab";
    }

    TabModel(TabSession.Row row) {
        id = row.id;
        navigation = new TabNavigation<>(null, row.url, row.rendererLost);
        navigation.seedRestoredUrl(row.url);
        title = row.title;
        isDesktopMode = row.desktop;
        recovery = row.rendererLost ? null : row;
    }

    TabSession.Row recovery() { return recovery; }
    void discardRecovery() { recovery = null; }

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
        return navigation.url();
    }

    public PrivacyWebView getWebView() {
        return navigation.view();
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

    void setIncognito(boolean incognito) {
        isIncognito = incognito;
    }

    String sessionUrl() {
        return getUrl() == null ? navigation.initialTarget() : getUrl();
    }

    boolean hasPageTransfer() { return blobDownloads != null && blobDownloads.hasPendingTransfer(); }

    boolean park(PrivacyWebView view, TabSession.Row row) {
        return id.equals(row.id) && !row.rendererLost && releaseView(view, false, row);
    }

    BlobDownloadManager blobDownloads() { return blobDownloads; }

    void setBlobDownloads(BlobDownloadManager blobDownloads) {
        this.blobDownloads = blobDownloads;
    }

    String pageKey() {
        TabNavigation.Page page = navigation.page();
        return page == null ? null : page.key;
    }

    void updatePageScope(TabNavigation.Page oldPage, PrivacyWebView view) {
        TabNavigation.Page current = navigation.page();
        if (oldPage == current) return;
        if (oldPage != null) {
            ComicManager.clearTab(oldPage.key);
            if (!isIncognito) AdBlockManager.getInstance(view.getContext()).resetPageStats(oldPage.key);
        }
        if (current != null && !isIncognito) {
            AdBlockManager.getInstance(view.getContext()).beginPageStats(current.key);
        }
    }

    boolean releaseView(PrivacyWebView webView, boolean rendererLost) {
        return releaseView(webView, rendererLost, null);
    }

    private boolean releaseView(PrivacyWebView webView, boolean rendererLost, TabSession.Row parked) {
        TabNavigation.Page oldPage = navigation.page();
        if (!navigation.detach(webView, rendererLost)) return false;
        recovery = parked;
        if (parked != null) {
            navigation.seedRestoredUrl(parked.url);
            setTitle(parked.title);
        }
        keepLive = false;
        // Invalidate callbacks before dismantling the view. Do not call load/stop/history
        // APIs on a renderer-dead WebView or signal its fullscreen JS callback.
        if (blobDownloads != null) {
            if (rendererLost) blobDownloads.rendererGone();
            else blobDownloads.close();
            blobDownloads = null;
        }
        if (messages != null) {
            messages.close(!rendererLost);
            messages = null;
        }
        if (scriptPolicy != null) {
            scriptPolicy.close(!rendererLost);
            scriptPolicy = null;
        }
        updatePageScope(oldPage, webView);
        isInitialized = false;
        webView.setToolbarCoordinator(null);
        webView.setOnScrollListener(null);
        if (!rendererLost) {
            webView.setOnLongClickListener(null);
            webView.setDownloadListener(null);
            webView.setWebChromeClient(null);
        }
        WebViewDisposal.detachAndDestroy(webView);
        return true;
    }

    public void destroy() {
        releaseView(getWebView(), false);
        discardRecovery();
    }
}

package com.privacy.browser;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.util.Log;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.Nullable;

import com.privacy.browser.adblock.AdBlockManager;
import com.privacy.browser.adblock.SiteRequestPolicy;
import com.privacy.downloader.net.RequestContext;
import com.privacy.downloader.ui.DownloadPromptDialog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import org.json.JSONObject;

/**
 * Hardened WebViewClient handling subresource blocking, cosmetic CSS injection,
 * download interception, and SSL security.
 */
public class PrivacyWebViewClient extends WebViewClient {

    private static final byte[] TINY_TRANSPARENT_PNG = new byte[]{
        (byte) 0x89, (byte) 80, (byte) 78, (byte) 71, (byte) 13, (byte) 10, (byte) 26, (byte) 10,
        (byte) 0, (byte) 0, (byte) 0, (byte) 13, (byte) 73, (byte) 72, (byte) 68, (byte) 82,
        (byte) 0, (byte) 0, (byte) 0, (byte) 1, (byte) 0, (byte) 0, (byte) 0, (byte) 1,
        (byte) 8, (byte) 4, (byte) 0, (byte) 0, (byte) 0, (byte) 0xb5, (byte) 28, (byte) 12,
        (byte) 2, (byte) 0, (byte) 0, (byte) 0, (byte) 11, (byte) 73, (byte) 68, (byte) 65,
        (byte) 84, (byte) 120, (byte) 0xda, (byte) 99, (byte) 100, (byte) 96, (byte) 0, (byte) 0,
        (byte) 0, (byte) 6, (byte) 0, (byte) 2, (byte) 48, (byte) 0x81, (byte) 0xd0, (byte) 47,
        (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 73, (byte) 69, (byte) 78, (byte) 68,
        (byte) 0xae, (byte) 66, (byte) 96, (byte) 0x82
    };

    private static final String TAG = "PrivacyWebViewClient";
    private static final String FORCE_IMAGE_INTERACTION_JS =
        "(function() {" +
        "  try {" +
        "    if (document.getElementById('__privacy_image_override_css')) return;" +
        "    var style = document.createElement('style');" +
        "    style.id = '__privacy_image_override_css';" +
        "    style.textContent = 'img, a, picture, figure, [style*=\"background-image\"] { ' +" +
        "      '-webkit-user-select: auto !important; ' +" +
        "      'user-select: auto !important; ' +" +
        "      '-webkit-touch-callout: default !important; ' +" +
        "      'pointer-events: auto !important; }';" +
        "    (document.head || document.documentElement).appendChild(style);" +
        "    document.addEventListener('contextmenu', function(e) {" +
        "      e.stopImmediatePropagation();" +
        "    }, true);" +
        "  } catch(e) {}" +
        "})();";

    interface ClientListener {
        void onPageStarted(String url);
        void onPageCommitted(String url);
        void onNativeHistoryChanged(String url);
        void onPageVisible(String url);
        boolean onPageFinished(String url);
        void onPageFailed(String url);
        void onTrackerBlocked(String pageKey);
        boolean canPrompt();
        void onRendererLost();
    }

    private final AdBlockManager filters;
    private final SiteRequestPolicy.Cache requestPolicies;
    private final ClientListener listener;
    private final TabModel tab;
    private final boolean privateTab;
    private final Context appContext;
    private final TabPageMessages pageMessages;
    private static volatile String homeHtml;

    PrivacyWebViewClient(TrackerBlocker trackerBlocker, TabModel tab, Context context, ClientListener listener) {
        filters = trackerBlocker.manager();
        requestPolicies = filters.requestPolicies(tab.sessionUrl());
        this.tab = tab;
        privateTab = tab.isIncognito();
        appContext = context.getApplicationContext();
        pageMessages = tab.messages;
        this.listener = listener;
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        if (view != tab.getWebView()) return true;
        if (request == null || request.getUrl() == null) return false;
        Uri uri = request.getUrl();
        if (InternalHome.ownsHost(uri.toString())) return !request.isForMainFrame() || request.isRedirect();
        // Link decisions still use the native current URL, not the attempted destination.
        if (requestPolicies.forUrl(tab.getUrl()).blocksNetwork(uri)) {
            recordBlocked(uri, tab.pageKey());
            return true;
        }
        // Subframes cannot invoke the native prompt or borrow the top page's identity.
        if (!request.isForMainFrame() || !isDownloadableUrl(uri.toString())) return false;
        if (listener.canPrompt()) {
            RequestContext context = request.hasGesture() && !request.isRedirect()
                    ? downloadContext(view) : unboundDownloadContext();
            DownloadPromptDialog.show(view.getContext(), uri.toString(), context, null, null, -1);
        }
        return true;
    }

    private RequestContext unboundDownloadContext() {
        return privateTab ? RequestContext.fromBrowser(null, null, true) : RequestContext.anonymous();
    }

    RequestContext downloadContext(WebView view) {
        if (privateTab || view == null || view != tab.getWebView() || InternalHome.ownsHost(tab.getUrl())
                || !tab.navigation.current(tab.getWebView(), tab.navigation.page(), view.getUrl())) return unboundDownloadContext();
        String origin = RequestContext.origin(tab.getUrl());
        return origin == null ? unboundDownloadContext()
                : RequestContext.fromBrowser(origin, view.getSettings().getUserAgentString(), false);
    }

    private void recordBlocked(Uri uri, String pageKey) {
        if (privateTab) return;
        String host = uri.getHost();
        if (filters.recordBlockedHost(pageKey, host == null ? "unknown" : host)) listener.onTrackerBlocked(pageKey);
    }

    private WebResourceResponse serveHome(WebResourceRequest request) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-store");
        headers.put("X-Content-Type-Options", "nosniff");
        headers.put("X-Frame-Options", "DENY");
        headers.put("Referrer-Policy", "no-referrer");
        headers.put("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; "
                + "style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
        if (request.isForMainFrame() && "GET".equals(request.getMethod()) && InternalHome.isHome(request.getUrl().toString())) {
            try {
                String html = getHomeHtml().replace("__HOME_DELIVERY_TOKEN__", pageMessages.issueHomeDelivery());
                return new WebResourceResponse("text/html", "UTF-8", 200, "OK", headers,
                        new ByteArrayInputStream(html.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            } catch (IOException ignored) {}
        }
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", headers, new ByteArrayInputStream(new byte[0]));
    }

    private String getHomeHtml() throws IOException {
        String cached = homeHtml;
        if (cached != null) return cached;
        try (java.io.InputStream input = appContext.getAssets().open("home.html")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) out.write(buffer, 0, count);
            cached = out.toString("UTF-8");
            homeHtml = cached;
            return cached;
        }
    }

    public static boolean isDownloadableUrl(String url) {
        if (url == null) return false;
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                return false;
            }
            String path = uri.getPath();
            if (path != null) {
                String lower = path.toLowerCase(Locale.US);
                String[] exts = {
                    ".zip", ".rar", ".7z", ".tar", ".gz", ".bz2", ".xz", ".iso", ".dmg", ".bin",
                    ".apk", ".xapk", ".apks", ".exe", ".msi", ".deb", ".rpm",
                    ".mp4", ".mkv", ".avi", ".mov", ".flv", ".wmv", ".webm",
                    ".mp3", ".flac", ".wav", ".aac", ".ogg", ".m4a",
                    ".pdf", ".torrent", ".img", ".csv", ".xlsx", ".docx"
                };
                for (String ext : exts) {
                    if (lower.endsWith(ext)) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    @Nullable
    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        TabNavigation.Page page = tab.navigation.page(); // per-view owner at interception entry
        String pageKey = page == null ? null : page.key;
        if (view != tab.getWebView()) return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
        if (request == null) return null;
        Uri uri = request.getUrl();
        if (uri == null) return null;

        String url = uri.toString();
        boolean mainFrame = request.isForMainFrame();
        if (InternalHome.ownsHost(url)) return serveHome(request);
        if (!privateTab) filters.recordRequest(pageKey);

        // Referer is only a filter fallback. It never seeds navigation or download authority.
        SiteRequestPolicy policy = mainFrame ? (url.isEmpty() ? null : requestPolicies.forUrl(url))
                : requestPolicies.forSubresource();
        if (policy == null) {
            policy = requestPolicies.forUrl(header(request.getRequestHeaders(), "Referer", "referer"));
        }
        if (policy.hasAssetPolicy()) {
            switch (policy.asset(uri.getPath())) {
                case FONT:
                    return new WebResourceResponse("font/woff2", "UTF-8", 200, "OK", null, new ByteArrayInputStream(new byte[0]));
                case MEDIA:
                    return new WebResourceResponse("text/plain", "UTF-8", 200, "OK", null, new ByteArrayInputStream(new byte[0]));
                default:
                    break;
            }
        }
        if (policy.blocksNetwork(uri)) {
            recordBlocked(uri, pageKey);

            // Never return 403 or error status codes for blocked ad/tracker resources.
            // In Android Chromium WebView, returning 4xx on any subframe or iframe navigation triggers
            // Chromium's built-in error page ("Webpage not available").
            // Instead, always return 200 OK with clean, benign payloads matching the expected content type:
            // 1. Transparent PNG for images
            // 2. Empty JavaScript comment for scripts
            // 3. Empty CSS comment for stylesheets
            // 4. Empty JSON object for APIs / XHR
            // 5. Clean notification page for main-frame navigation
            // 6. Zero-dimension, display:none empty HTML for iframes / subframes / documents.
            Map<String, String> headers = new HashMap<>();
            Map<String, String> requestHeaders = request.getRequestHeaders();
            String reqOrigin = header(requestHeaders, "Origin", "origin");
            headers.put("Access-Control-Allow-Origin", reqOrigin != null && !reqOrigin.isEmpty() ? reqOrigin : "*");
            headers.put("Access-Control-Allow-Credentials", "true");
            headers.put("Access-Control-Allow-Methods", "GET, POST, OPTIONS, HEAD, PUT, DELETE");
            headers.put("Access-Control-Allow-Headers", "*");
            headers.put("Cache-Control", "no-cache, no-store, must-revalidate");

            String accept = header(requestHeaders, "Accept", "accept");

            // Main frame navigation (top-level page navigated to blocked ad URL)
            if (mainFrame) {
                String blockedPage = "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"><title>Blocked</title><style>html,body{height:100%;margin:0;display:flex;align-items:center;justify-content:center;background:#000000;font-family:-apple-system,BlinkMacSystemFont,Roboto,sans-serif;color:#ffffff;text-align:center;padding:24px;box-sizing:border-box;}h1{font-size:20px;font-weight:600;margin:0 0 8px;color:#ffffff;}p{font-size:14px;color:#a0a0a0;margin:0;}</style></head><body><div><h1>Advertisement Blocked</h1><p>This tracking or advertisement domain was blocked by Privacy Shield.</p></div></body></html>";
                byte[] htmlBytes = blockedPage.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                return new WebResourceResponse("text/html", "UTF-8", 200, "OK", headers, new ByteArrayInputStream(htmlBytes));
            }

            String path = uri.getPath();
            path = path == null ? "" : path.toLowerCase(Locale.US);

            // Image requests -> 1x1 transparent PNG
            // Note: Modern browsers include image/avif, image/webp in Accept header for main HTML requests too.
            // Only treat as image if not main frame and either file extension is an image or Accept explicitly indicates an image resource.
            boolean isExplicitImage = (accept != null && (accept.startsWith("image/") || (accept.contains("image/") && !accept.contains("text/html"))));
            if (isExplicitImage
                    || path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg")
                    || path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".ico")
                    || path.endsWith(".svg")) {
                return new WebResourceResponse("image/png", null, 200, "OK", headers, new ByteArrayInputStream(TINY_TRANSPARENT_PNG));
            }

            // JavaScript requests -> empty comment script (prevents syntax/execution errors and onerror retries)
            if ((accept != null && (accept.contains("javascript") || accept.contains("ecmascript")))
                    || path.endsWith(".js") || path.contains("/js/") || path.endsWith(".mjs")) {
                byte[] jsBytes = "/* blocked by privacy shield */".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                return new WebResourceResponse("application/javascript", "UTF-8", 200, "OK", headers, new ByteArrayInputStream(jsBytes));
            }

            // Stylesheet requests -> empty CSS comment
            if ((accept != null && accept.contains("text/css")) || path.endsWith(".css")) {
                byte[] cssBytes = "/* blocked */".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                return new WebResourceResponse("text/css", "UTF-8", 200, "OK", headers, new ByteArrayInputStream(cssBytes));
            }

            // JSON / XHR API requests -> empty JSON object
            if ((accept != null && accept.contains("application/json")) || path.endsWith(".json")) {
                byte[] jsonBytes = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                return new WebResourceResponse("application/json", "UTF-8", 200, "OK", headers, new ByteArrayInputStream(jsonBytes));
            }

            // Subframes, iframes, and any other subresources:
            // ALWAYS return 200 OK with empty, self-collapsing HTML!
            String emptyHtml = "<!DOCTYPE html><html><head><style>html,body{display:none!important;visibility:hidden!important;margin:0!important;padding:0!important;width:0!important;height:0!important;overflow:hidden!important;border:none!important;}</style></head><body></body></html>";
            byte[] bytes = emptyHtml.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return new WebResourceResponse("text/html", "UTF-8", 200, "OK", headers, new ByteArrayInputStream(bytes));
        }

        // Only allowed, per-view resource GETs supply passive hints. There is deliberately no
        // process-wide ServiceWorkerClient forwarding anything to a selected tab.
        if (!privateTab && !mainFrame && page != null && page.media.isObserving()
                && !policy.blocksMedia() && "GET".equals(request.getMethod()) && tab.navigation.page() == page) {
            page.media.requestHint(url, uri.getEncodedPath(), tab.getTitle());
        }

        return super.shouldInterceptRequest(view, request);
    }

    private static String header(Map<String, String> headers, String name, String lowercase) {
        if (headers == null) return null;
        String value = headers.get(name);
        return value == null ? headers.get(lowercase) : value;
    }

    Predicate<String> mediaOptionsPolicy() {
        if (privateTab) return url -> false;
        String document = tab.getUrl();
        SiteRequestPolicy.Cache policies = requestPolicies; // No view or Activity captured by a worker.
        return url -> {
            if (url == null || url.length() > PageMessageGate.MAX_URL || InternalHome.ownsHost(url)) return false;
            SiteRequestPolicy policy = policies.forUrl(document);
            if (policy.blocksMedia()) return false;
            Uri uri = Uri.parse(url);
            return policy.asset(uri.getPath()) == SiteRequestPolicy.Asset.PASS && !policy.blocksNetwork(uri);
        };
    }

    // Explicit GET/history navigation seeds interception before the provider's first callback.
    void setNavigationTarget(String url) { requestPolicies.navigate(url); }

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        if (view != tab.getWebView()) return;
        setNavigationTarget(url);
        listener.onPageStarted(url);
    }

    @Override
    public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
        onNativeCommit(view, url);
    }

    @Override
    public void onPageCommitVisible(WebView view, String url) {
        onNativeCommit(view, url);
        if (view != tab.getWebView() || url == null || !url.equals(view.getUrl())) return;
        listener.onPageVisible(url);
        if (listener.canPrompt()) {
            // A provider milestone, not a measurement of pixels already presented to the user.
            android.os.Trace.beginSection("Browser.tabs.commitVisible");
            android.os.Trace.endSection();
        }
    }

    private void onNativeCommit(WebView view, String url) {
        if (view != tab.getWebView()) return;
        listener.onPageCommitted(url);
        if (view == tab.getWebView() && url != null && url.equals(tab.getUrl()) && url.equals(view.getUrl())) {
            // A trusted commit can be deduplicated for UI/permissions yet move native history at the same URL.
            listener.onNativeHistoryChanged(url);
            setNavigationTarget(url);
        }
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        if (view != tab.getWebView() || !listener.onPageFinished(url)) return;
        if (InternalHome.isHome(url)) {
            ThemeManager.applyHomeUi(tab.getWebView());
            pageMessages.activateHome();
            return;
        }
        if (tab.scriptPolicy != null) tab.scriptPolicy.onFinishedPage(url);
    }

    @Override
    public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
        if (view == tab.getWebView()) listener.onRendererLost();
        // WebView can share a renderer, but this callback is sent to each affected view.
        return true;
    }

    private void collapseFailedSubframe(@Nullable WebView view, @Nullable WebResourceRequest request) {
        if (view != tab.getWebView() || request == null || request.isForMainFrame() || InternalHome.isHome(tab.getUrl())
                || tab.scriptPolicy == null || !tab.scriptPolicy.allowsScripts(view)
                || !tab.navigation.current(tab.getWebView(), tab.navigation.page(), view.getUrl())) {
            return;
        }
        Uri uri = request.getUrl();
        if (uri == null) return;
        final String u = JSONObject.quote(uri.toString());
        final String host = JSONObject.quote(uri.getHost() == null ? "" : uri.getHost());
        final String path = JSONObject.quote(uri.getPath() == null ? "" : uri.getPath());
        String js = "(function() {" +
            "  try {" +
            "    var u = " + u + ";" +
            "    var host = " + host + ";" +
            "    var path = " + path + ";" +
            "    var iframes = document.querySelectorAll('iframe, frame, embed, object');" +
            "    for (var i = 0; i < iframes.length; i++) {" +
            "      var f = iframes[i];" +
            "      var s = f.src || f.getAttribute('src') || '';" +
            "      var match = false;" +
            "      if (u && s.indexOf(u) !== -1) match = true;" +
            "      if (host && s.indexOf(host) !== -1) match = true;" +
            "      if (path && path.length > 5 && s.indexOf(path) !== -1) match = true;" +
            "      if (match) {" +
            "        f.style.setProperty('display', 'none', 'important');" +
            "        f.style.setProperty('width', '0px', 'important');" +
            "        f.style.setProperty('height', '0px', 'important');" +
            "        f.style.setProperty('visibility', 'hidden', 'important');" +
            "        var p = f.parentElement;" +
            "        if (p && p !== document.body && p !== document.documentElement && p.tagName !== 'ARTICLE' && p.tagName !== 'MAIN') {" +
            "          var pid = (p.id || '') + ' ' + (p.className || '');" +
            "          if (/adHolder|dmg-ads|ad-container|ad-wrapper|ad-box|div-gpt-ad|mpu/i.test(pid)) {" +
            "            p.style.setProperty('display', 'none', 'important');" +
            "            p.style.setProperty('height', '0px', 'important');" +
            "            p.style.setProperty('min-height', '0px', 'important');" +
            "            p.style.setProperty('margin', '0px', 'important');" +
            "            p.style.setProperty('padding', '0px', 'important');" +
            "          }" +
            "        }" +
            "      }" +
            "    }" +
            "  } catch(e) {}" +
            "})();";
        view.evaluateJavascript(InternalHome.guardExternalPage(tab.getUrl(), js), null);
    }

    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
        if (view != tab.getWebView()) return;
        if (request.isForMainFrame()) listener.onPageFailed(request.getUrl().toString());
        else collapseFailedSubframe(view, request);
    }

    @Override
    public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
        super.onReceivedHttpError(view, request, errorResponse);
        collapseFailedSubframe(view, request);
    }

    @Override
    public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
        // Enforce strict SSL integrity - never bypass invalid certificates
        Log.e(TAG, "SSL Error encountered: " + error.toString() + ". Aborting connection.");
        handler.cancel();
    }
}

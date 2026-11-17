package com.privacy.browser;

import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.privacy.downloader.ui.DownloadPromptDialog;

import java.io.ByteArrayInputStream;
import java.util.Locale;

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
    private static final String COSMETIC_FILTER_JS =
        "(function() {" +
        "  if (document.getElementById('__privacy_shield_css')) return;" +
        "  const style = document.createElement('style');" +
        "  style.id = '__privacy_shield_css';" +
        "  style.textContent = '.advertisement, .adsbygoogle, [id*=\"google_ads\"], .trc_related_container, .cookie-banner, .consent-banner { display: none !important; }';" +
        "  (document.head || document.documentElement).appendChild(style);" +
        "})();";

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

    public static final String VIDEO_SNIFFER_JS =
        "(function() {" +
        "  if (window.__privacy_video_sniffer_installed) {" +
        "    if (typeof window.__checkPrivacyMedia === 'function') window.__checkPrivacyMedia();" +
        "    if (typeof window.__checkYouTubeData === 'function') window.__checkYouTubeData();" +
        "    return;" +
        "  }" +
        "  window.__privacy_video_sniffer_installed = true;" +
        "  var reportedUrls = {};" +
        "  var mediaRegex = /\\.(m3u8|mpd|mp4|webm|m4v|m4a|mp3|ogg|wav|flac)(\\?.*)?$|(\\/manifest\\.m3u8)|(\\/master\\.m3u8)|(\\/playlist\\.m3u8)|(mime=(video|audio)%2f)/i;" +
        "  function isChunkOrSegment(u) {" +
        "    if (!u || typeof u !== 'string') return true;" +
        "    if (u.indexOf('googlevideo.com') !== -1 || u.indexOf('/videoplayback') !== -1) return true;" +
        "    if (u.indexOf('/proxy.html') !== -1 || u.indexOf('/generate_204') !== -1 || u.indexOf('/stats/qoe') !== -1) return true;" +
        "    if (u.indexOf('range=') !== -1 || u.indexOf('.ts') !== -1 || u.indexOf('.m4s') !== -1 || u.indexOf('.key') !== -1) return true;" +
        "    return /[\\/._-](segment|seg|chunk|frag|slice|part|sq)[\\/._\\d-]|init\\.mp4/i.test(u);" +
        "  }" +
        "  function reportMedia(url) {" +
        "    if (!url || typeof url !== 'string') return;" +
        "    if (url.startsWith('blob:') || url.startsWith('data:') || url.startsWith('javascript:')) return;" +
        "    if (reportedUrls[url]) return;" +
        "    if (isChunkOrSegment(url)) return;" +
        "    if (mediaRegex.test(url)) {" +
        "      reportedUrls[url] = true;" +
        "      if (window.PrivacyVideoSniffer && typeof window.PrivacyVideoSniffer.onVideoFound === 'function') {" +
        "        setTimeout(function() {" +
        "          try { window.PrivacyVideoSniffer.onVideoFound(window.location.href, document.title || '', url); } catch(e) {}" +
        "        }, 0);" +
        "      }" +
        "    }" +
        "  }" +
        "  try {" +
        "    var origMediaSrc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');" +
        "    if (origMediaSrc && origMediaSrc.set) {" +
        "      Object.defineProperty(HTMLMediaElement.prototype, 'src', {" +
        "        set: function(val) {" +
        "          reportMedia(val);" +
        "          return origMediaSrc.set.call(this, val);" +
        "        }," +
        "        get: origMediaSrc.get," +
        "        configurable: true," +
        "        enumerable: true" +
        "      });" +
        "    }" +
        "  } catch(e) {}" +
        "  try {" +
        "    if (window.fetch) {" +
        "      var origFetch = window.fetch;" +
        "      window.fetch = function(input, init) {" +
        "        var u = (typeof input === 'string') ? input : (input && input.url ? input.url : '');" +
        "        reportMedia(u);" +
        "        return origFetch.apply(this, arguments);" +
        "      };" +
        "    }" +
        "  } catch(e) {}" +
        "  try {" +
        "    if (window.XMLHttpRequest) {" +
        "      var origOpen = XMLHttpRequest.prototype.open;" +
        "      XMLHttpRequest.prototype.open = function(method, url) {" +
        "        reportMedia(url);" +
        "        return origOpen.apply(this, arguments);" +
        "      };" +
        "    }" +
        "  } catch(e) {}" +
        "  function notifyVideo(video) {" +
        "    if (!video) return;" +
        "    var src = video.currentSrc || video.src || '';" +
        "    if (!src && video.children) {" +
        "      for (var i = 0; i < video.children.length; i++) {" +
        "        var child = video.children[i];" +
        "        if (child.tagName === 'SOURCE' && child.src) {" +
        "          src = child.src;" +
        "          break;" +
        "        }" +
        "      }" +
        "    }" +
        "    if (src) reportMedia(src);" +
        "  }" +
        "  window.__checkPrivacyMedia = function() {" +
        "    var videos = document.querySelectorAll('video, audio');" +
        "    for (var i = 0; i < videos.length; i++) {" +
        "      notifyVideo(videos[i]);" +
        "    }" +
        "  };" +
        "  window.__checkPrivacyMedia();" +
        "  var events = ['play', 'playing', 'loadedmetadata', 'canplay', 'loadstart'];" +
        "  for (var e = 0; e < events.length; e++) {" +
        "    document.addEventListener(events[e], function(evt) {" +
        "      if (evt.target && (evt.target.tagName === 'VIDEO' || evt.target.tagName === 'AUDIO')) {" +
        "        notifyVideo(evt.target);" +
        "      }" +
        "    }, true);" +
        "  }" +
        "  if (window.MutationObserver) {" +
        "    var observer = new MutationObserver(function(mutations) {" +
        "      for (var m = 0; m < mutations.length; m++) {" +
        "        var nodes = mutations[m].addedNodes;" +
        "        for (var n = 0; n < nodes.length; n++) {" +
        "          var node = nodes[n];" +
        "          if (node.nodeType === 1) {" +
        "            if (node.tagName === 'VIDEO' || node.tagName === 'AUDIO') {" +
        "              notifyVideo(node);" +
        "            } else if (node.querySelectorAll) {" +
        "              var vList = node.querySelectorAll('video, audio');" +
        "              for (var k = 0; k < vList.length; k++) {" +
        "                notifyVideo(vList[k]);" +
        "              }" +
        "            }" +
        "          }" +
        "        }" +
        "      }" +
        "    });" +
        "    var obsTarget = document.documentElement || document.body || document;" +
        "    if (obsTarget) { try { observer.observe(obsTarget, { childList: true, subtree: true }); } catch(e) {} }" +
        "    else { document.addEventListener('DOMContentLoaded', function() { try { observer.observe(document.documentElement || document.body || document, { childList: true, subtree: true }); } catch(e) {} }); }" +
        "  }" +
        "  function getCurrentVideoId() {" +
        "    var m = window.location.search.match(/[?&]v=([^&#]+)/) ||" +
        "            window.location.pathname.match(/\\/shorts\\/([^&#/?]+)/) ||" +
        "            window.location.pathname.match(/\\/embed\\/([^&#/?]+)/);" +
        "    return m ? m[1] : null;" +
        "  }" +
        "  function checkYouTubeData() {" +
        "    try {" +
        "      var currentVid = getCurrentVideoId();" +
        "      if (!currentVid) return;" +
        "      var data = null;" +
        "      var player = document.getElementById('movie_player') || document.querySelector('#movie_player');" +
        "      if (player && typeof player.getPlayerResponse === 'function') {" +
        "        try {" +
        "          var pr = player.getPlayerResponse();" +
        "          if (pr && pr.videoDetails && pr.videoDetails.videoId === currentVid) {" +
        "            data = pr;" +
        "          }" +
        "        } catch(e) {}" +
        "      }" +
        "      if (!data) {" +
        "        var watchEl = document.querySelector('ytm-watch, ytd-watch-flexy');" +
        "        if (watchEl && watchEl.playerData) {" +
        "          var wpr = watchEl.playerData;" +
        "          if (wpr && wpr.videoDetails && wpr.videoDetails.videoId === currentVid) {" +
        "            data = wpr;" +
        "          }" +
        "        }" +
        "      }" +
        "      if (!data && window.ytInitialPlayerResponse) {" +
        "        var ipr = window.ytInitialPlayerResponse;" +
        "        if (ipr && ipr.videoDetails && ipr.videoDetails.videoId === currentVid) {" +
        "          data = ipr;" +
        "        }" +
        "      }" +
        "      if (!data && window.ytplayer && window.ytplayer.config && window.ytplayer.config.args) {" +
        "        var raw = window.ytplayer.config.args.raw_player_response;" +
        "        if (raw && raw.videoDetails && raw.videoDetails.videoId === currentVid) {" +
        "          data = raw;" +
        "        }" +
        "      }" +
        "      if (data && data.streamingData && window.PrivacyVideoSniffer && typeof window.PrivacyVideoSniffer.onYouTubeData === 'function') {" +
        "        var str = JSON.stringify(data);" +
        "        window.PrivacyVideoSniffer.onYouTubeData(window.location.href, document.title || '', str);" +
        "      }" +
        "    } catch(e) {}" +
        "  }" +
        "  window.__checkYouTubeData = checkYouTubeData;" +
        "  checkYouTubeData();" +
        "  var ytInterval = setInterval(checkYouTubeData, 1500);" +
        "  setTimeout(function() { clearInterval(ytInterval); }, 15000);" +
        "  function onUrlChange() {" +
        "    if (window.PrivacyVideoSniffer && typeof window.PrivacyVideoSniffer.onPageUrlChanged === 'function') {" +
        "      window.PrivacyVideoSniffer.onPageUrlChanged(window.location.href, document.title || '');" +
        "    }" +
        "    setTimeout(checkYouTubeData, 500);" +
        "    setTimeout(checkYouTubeData, 1500);" +
        "  }" +
        "  var pushState = history.pushState;" +
        "  var replaceState = history.replaceState;" +
        "  history.pushState = function() {" +
        "    var ret = pushState.apply(this, arguments);" +
        "    onUrlChange();" +
        "    return ret;" +
        "  };" +
        "  history.replaceState = function() {" +
        "    var ret = replaceState.apply(this, arguments);" +
        "    onUrlChange();" +
        "    return ret;" +
        "  };" +
        "  window.addEventListener('popstate', onUrlChange);" +
        "  window.addEventListener('hashchange', onUrlChange);" +
        "  document.addEventListener('yt-navigate-finish', onUrlChange);" +
        "  document.addEventListener('yt-page-data-updated', onUrlChange);" +
        "})();";

    public interface ClientListener {
        void onPageStarted(String url, Bitmap favicon);
        void onPageFinished(String url);
        void onTrackerBlocked(String host);
    }

    private final TrackerBlocker trackerBlocker;
    private final ClientListener listener;
    private String tabId;
    private volatile String currentUrl;
    private volatile String currentTitle;
    private volatile android.content.Context appContext;

    public PrivacyWebViewClient(@NonNull TrackerBlocker trackerBlocker,
                                @NonNull ClientListener listener) {
        this(trackerBlocker, null, null, null, listener);
    }

    public PrivacyWebViewClient(@NonNull TrackerBlocker trackerBlocker,
                                @Nullable String tabId,
                                @NonNull ClientListener listener) {
        this(trackerBlocker, tabId, null, null, listener);
    }

    public PrivacyWebViewClient(@NonNull TrackerBlocker trackerBlocker,
                                @Nullable String tabId,
                                @Nullable String initialUrl,
                                @Nullable android.content.Context context,
                                @NonNull ClientListener listener) {
        this.trackerBlocker = trackerBlocker;
        this.tabId = tabId;
        this.currentUrl = initialUrl;
        if (context != null) {
            this.appContext = context.getApplicationContext();
        }
        this.listener = listener;
    }

    public void setTabId(String tabId) {
        this.tabId = tabId;
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        if (request == null || request.getUrl() == null) {
            return false;
        }
        Uri uri = request.getUrl();
        String topUrl = currentUrl != null ? currentUrl : (view != null ? view.getUrl() : null);
        com.privacy.browser.adblock.AdBlockManager abm = appContext != null ? com.privacy.browser.adblock.AdBlockManager.getInstance(appContext) : null;
        if (abm != null && abm.isAdBlockActiveForSite(topUrl) && trackerBlocker != null && trackerBlocker.isBlocked(uri, topUrl)) {
            Log.d(TAG, "BLOCKED ad link navigation: " + uri);
            String host = uri.getHost() != null ? uri.getHost() : "unknown";
            abm.recordBlockedHost(tabId, host);
            listener.onTrackerBlocked(host);
            new Handler(Looper.getMainLooper()).post(() -> {
                android.widget.Toast.makeText(view.getContext(), "Blocked ad link navigation", android.widget.Toast.LENGTH_SHORT).show();
            });
            return true;
        }
        return handleUrlLoading(view, uri.toString());
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        if (url == null) {
            return false;
        }
        try {
            Uri uri = Uri.parse(url);
            String topUrl = currentUrl != null ? currentUrl : (view != null ? view.getUrl() : null);
            com.privacy.browser.adblock.AdBlockManager abm = appContext != null ? com.privacy.browser.adblock.AdBlockManager.getInstance(appContext) : null;
            if (abm != null && abm.isAdBlockActiveForSite(topUrl) && trackerBlocker != null && trackerBlocker.isBlocked(uri, topUrl)) {
                Log.d(TAG, "BLOCKED ad link navigation: " + uri);
                String host = uri.getHost() != null ? uri.getHost() : "unknown";
                abm.recordBlockedHost(tabId, host);
                listener.onTrackerBlocked(host);
                new Handler(Looper.getMainLooper()).post(() -> {
                    android.widget.Toast.makeText(view.getContext(), "Blocked ad link navigation", android.widget.Toast.LENGTH_SHORT).show();
                });
                return true;
            }
        } catch (Exception ignored) {}
        return handleUrlLoading(view, url);
    }

    private boolean handleUrlLoading(WebView view, String url) {
        if (isDownloadableUrl(url)) {
            String cookies = CookieManager.getInstance().getCookie(url);
            String userAgent = view.getSettings() != null ? view.getSettings().getUserAgentString() : null;
            String referrer = view.getUrl();
            new Handler(Looper.getMainLooper()).post(() -> {
                DownloadPromptDialog.show(view.getContext(), url, userAgent, null, null, cookies, referrer, null);
            });
            return true;
        }
        return false;
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
        if (request == null || request.getUrl() == null) {
            return null;
        }

        if (request.isForMainFrame()) {
            this.currentUrl = request.getUrl().toString();
        }

        if (appContext == null && view != null) {
            try {
                android.content.Context ctx = view.getContext();
                if (ctx != null) {
                    appContext = ctx.getApplicationContext();
                }
            } catch (Throwable ignored) {}
        }

        Uri uri = request.getUrl();
        if (appContext != null) {
            com.privacy.browser.adblock.AdBlockManager.getInstance(appContext).recordRequest(tabId);
        }

        String topUrl = currentUrl;
        if (topUrl == null || topUrl.isEmpty()) {
            if (request.getRequestHeaders() != null) {
                topUrl = request.getRequestHeaders().get("Referer");
                if (topUrl == null) {
                    topUrl = request.getRequestHeaders().get("referer");
                }
            }
        }

        com.privacy.browser.adblock.AdBlockManager abm = appContext != null ? com.privacy.browser.adblock.AdBlockManager.getInstance(appContext) : null;
        boolean adBlockActive = (abm != null) && abm.isAdBlockActiveForSite(topUrl);

        if (!adBlockActive) {
            // Unrestricted stock browser behavior when adblocking is disabled: pass straight through!
            try {
                com.privacy.browser.media.MediaSniffer.inspectRequest(tabId, uri, currentTitle);
            } catch (Throwable ignored) {}
            return super.shouldInterceptRequest(view, request);
        }

        if (topUrl != null) {
            if (abm.isSiteFontsBlocked(topUrl)) {
                String path = uri.getPath();
                if (path != null) {
                    String p = path.toLowerCase(java.util.Locale.US);
                    if (p.endsWith(".woff") || p.endsWith(".woff2") || p.endsWith(".ttf") || p.endsWith(".otf") || p.contains("/fonts/")) {
                        return new WebResourceResponse("font/woff2", "UTF-8", 200, "OK", null, new ByteArrayInputStream(new byte[0]));
                    }
                }
            }
            if (abm.isSiteLargeMediaBlocked(topUrl)) {
                String path = uri.getPath();
                if (path != null) {
                    String p = path.toLowerCase(java.util.Locale.US);
                    if (p.endsWith(".mp4") || p.endsWith(".webm") || p.endsWith(".m4v") || p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".png") || p.endsWith(".webp") || p.endsWith(".gif")) {
                        return new WebResourceResponse("text/plain", "UTF-8", 200, "OK", null, new ByteArrayInputStream(new byte[0]));
                    }
                }
            }
        }

        if (trackerBlocker.isBlocked(uri, topUrl)) {
            String host = uri.getHost() != null ? uri.getHost() : "unknown";
            Log.d(TAG, "BLOCKED tracker/ad subresource: " + host);
            if (appContext != null) {
                com.privacy.browser.adblock.AdBlockManager.getInstance(appContext).recordBlockedHost(tabId, host);
            }
            listener.onTrackerBlocked(host);

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
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            String reqOrigin = null;
            if (request.getRequestHeaders() != null) {
                reqOrigin = request.getRequestHeaders().get("Origin");
                if (reqOrigin == null) {
                    reqOrigin = request.getRequestHeaders().get("origin");
                }
            }
            headers.put("Access-Control-Allow-Origin", reqOrigin != null && !reqOrigin.isEmpty() ? reqOrigin : "*");
            headers.put("Access-Control-Allow-Credentials", "true");
            headers.put("Access-Control-Allow-Methods", "GET, POST, OPTIONS, HEAD, PUT, DELETE");
            headers.put("Access-Control-Allow-Headers", "*");
            headers.put("Cache-Control", "no-cache, no-store, must-revalidate");

            String accept = null;
            if (request.getRequestHeaders() != null) {
                accept = request.getRequestHeaders().get("Accept");
                if (accept == null) {
                    accept = request.getRequestHeaders().get("accept");
                }
            }

            // Main frame navigation (top-level page navigated to blocked ad URL)
            if (request.isForMainFrame()) {
                String blockedPage = "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"><title>Blocked</title><style>html,body{height:100%;margin:0;display:flex;align-items:center;justify-content:center;background:#000000;font-family:-apple-system,BlinkMacSystemFont,Roboto,sans-serif;color:#ffffff;text-align:center;padding:24px;box-sizing:border-box;}h1{font-size:20px;font-weight:600;margin:0 0 8px;color:#ffffff;}p{font-size:14px;color:#a0a0a0;margin:0;}</style></head><body><div><h1>Advertisement Blocked</h1><p>This tracking or advertisement domain was blocked by Privacy Shield.</p></div></body></html>";
                byte[] htmlBytes = blockedPage.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                return new WebResourceResponse("text/html", "UTF-8", 200, "OK", headers, new ByteArrayInputStream(htmlBytes));
            }

            String path = uri.getPath() != null ? uri.getPath().toLowerCase(java.util.Locale.US) : "";

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

        // Robust Media Sniffing (HLS/m3u8, DASH/mpd, Vimeo/YouTube chunks, MP4/WebM)
        try {
            com.privacy.browser.media.MediaSniffer.inspectRequest(tabId, uri, currentTitle);
        } catch (Throwable ignored) {}

        return super.shouldInterceptRequest(view, request);
    }

    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
        super.onPageStarted(view, url, favicon);
        this.currentUrl = url;
        if (view != null) {
            this.currentTitle = view.getTitle();
            if (view.getContext() != null) {
                this.appContext = view.getContext().getApplicationContext();
            }
        }
        if (tabId != null && url != null) {
            com.privacy.browser.media.MediaSniffer.setTabUrl(tabId, url);
        }
        // Synchronize local homepage theme and prevent algorithmic force-dark on light home
        if (url != null && url.startsWith("file:///android_asset/home.html") && view != null && view.getContext() != null) {
            boolean isDark = ThemeManager.isAppDarkTheme(view.getContext());
            if (!isDark) {
                if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.ALGORITHMIC_DARKENING)) {
                    androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(view.getSettings(), false);
                }
                if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.FORCE_DARK)) {
                    androidx.webkit.WebSettingsCompat.setForceDark(view.getSettings(), androidx.webkit.WebSettingsCompat.FORCE_DARK_OFF);
                }
            }
        }
        // Early inject anti-adblock defusers, cosmetic rules, YouTube defuser, popup defuser, and user scripts ONLY if active
        if (view != null && view.getContext() != null) {
            com.privacy.browser.adblock.AdBlockManager abm = com.privacy.browser.adblock.AdBlockManager.getInstance(view.getContext());
            if (abm.isAdBlockActiveForSite(url)) {
                boolean cosmeticAllowed = abm.isSiteCosmeticEnabled(url);
                boolean antiAdblockEnabled = abm.isAntiAdblockDefuserEnabled();
                com.privacy.browser.adblock.AntiAdblockDefuser.inject(view, cosmeticAllowed, antiAdblockEnabled);
                if (cosmeticAllowed) {
                    String cleanHost = com.privacy.browser.adblock.AdBlockManager.extractCleanHost(url);
                    String userCss = com.privacy.browser.adblock.rules.UserFiltersManager.getInstance(view.getContext()).getCosmeticCssForDomain(cleanHost);
                    if (!userCss.isEmpty()) {
                        String js = "var s = document.createElement('style'); s.id = '__user_ublock_filters'; s.textContent = '" + userCss.replace("'", "\\'") + "'; var t = document.head || document.documentElement; if (t) t.appendChild(s);";
                        view.evaluateJavascript(js, null);
                    }
                }
                // Early inject YouTube ad defuser
                if (url != null && (url.contains("youtube.com") || url.contains("youtu.be"))) {
                    com.privacy.browser.adblock.YouTubeAdDefuser.inject(view);
                }
                // Early inject popup and click-jacking defuser
                com.privacy.browser.adblock.PopupClickDefuser.inject(view);
                // Early inject custom user scripts configured for document-start
                com.privacy.browser.adblock.UserScriptManager.getInstance(view.getContext())
                        .injectForTiming(view, url, com.privacy.browser.adblock.UserScriptManager.RunAt.DOCUMENT_START);
            }
        }
        // Early inject video sniffer so requests fired during initial page loading are captured
        view.evaluateJavascript(VIDEO_SNIFFER_JS, null);
        listener.onPageStarted(url, favicon);
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        super.onPageFinished(view, url);
        this.currentUrl = url;
        if (view != null) {
            this.currentTitle = view.getTitle();
            if (view.getContext() != null) {
                this.appContext = view.getContext().getApplicationContext();
            }
        }
        // Inject cosmetic CSS hiding rules, anti-adblock, YouTube defuser, popup defuser, and user scripts ONLY if active
        if (view != null && view.getContext() != null) {
            com.privacy.browser.adblock.AdBlockManager abm = com.privacy.browser.adblock.AdBlockManager.getInstance(view.getContext());
            if (abm.isAdBlockActiveForSite(url)) {
                boolean cosmeticAllowed = abm.isSiteCosmeticEnabled(url);
                com.privacy.browser.adblock.AntiAdblockDefuser.inject(view, cosmeticAllowed, abm.isAntiAdblockDefuserEnabled());
                if (cosmeticAllowed) {
                    String cleanHost = com.privacy.browser.adblock.AdBlockManager.extractCleanHost(url);
                    String userCss = com.privacy.browser.adblock.rules.UserFiltersManager.getInstance(view.getContext()).getCosmeticCssForDomain(cleanHost);
                    if (!userCss.isEmpty()) {
                        String js = "var s = document.createElement('style'); s.id = '__user_ublock_filters'; s.textContent = '" + userCss.replace("'", "\\'") + "'; var t = document.head || document.documentElement; if (t) t.appendChild(s);";
                        view.evaluateJavascript(js, null);
                    }
                }
                if (abm.isSiteLargeMediaBlocked(url)) {
                    view.evaluateJavascript("var ms = document.createElement('style'); ms.textContent = 'img, video, audio, picture { display: none !important; }'; var t = document.head || document.documentElement; if (t) t.appendChild(ms);", null);
                }
                // Inject YouTube ad defuser
                if (url != null && (url.contains("youtube.com") || url.contains("youtu.be"))) {
                    com.privacy.browser.adblock.YouTubeAdDefuser.inject(view);
                }
                // Inject popup and click-jacking defuser
                com.privacy.browser.adblock.PopupClickDefuser.inject(view);
                // Inject custom user scripts configured for document-end
                com.privacy.browser.adblock.UserScriptManager.getInstance(view.getContext())
                        .injectForTiming(view, url, com.privacy.browser.adblock.UserScriptManager.RunAt.DOCUMENT_END);
            }
        }

        // Inject force image interaction override if enabled in settings
        if (view.getContext() != null && com.privacy.browser.settings.SettingsActivity.isForceLongPressImagesEnabled(view.getContext())) {
            view.evaluateJavascript(FORCE_IMAGE_INTERACTION_JS, null);
        }

        // Inject real-time video sniffer & SPA history hook
        view.evaluateJavascript(VIDEO_SNIFFER_JS, null);

        // Inject sequential comic and gallery sniffer
        if (com.privacy.browser.settings.SettingsActivity.isComicDownloaderEnabled(view.getContext())) {
            com.privacy.browser.comic.ComicDetector.inject(view);
        }

        // Apply document zoom if non-default page zoom selected
        if (view.getContext() != null) {
            int pageZoom = com.privacy.browser.settings.SettingsActivity.getPageZoom(view.getContext());
            if (pageZoom != 100) {
                double factor = pageZoom / 100.0;
                view.evaluateJavascript("try { if (document.documentElement && document.documentElement.style) { document.documentElement.style.zoom = '" + factor + "'; } } catch(e) {}", null);
            }
        }

        // Proactively sniff Vimeo media streams if on a Vimeo video page
        if (url != null && com.privacy.browser.media.MediaSniffer.isVimeoUrl(url)) {
            String pageTitle = view != null ? view.getTitle() : null;
            com.privacy.browser.media.MediaSniffer.resolveVimeoFromUrl(tabId, url, pageTitle, null);
        }

        // Proactively sniff YouTube media streams if on a YouTube video page
        if (url != null && com.privacy.browser.media.MediaSniffer.isYouTubeUrl(url)) {
            String pageTitle = view != null ? view.getTitle() : null;
            com.privacy.browser.media.MediaSniffer.resolveYouTubeFromUrl(tabId, url, pageTitle, null);
        }

        // Sync theme and defense statistics on local homepage
        if (url != null && url.startsWith("file:///android_asset/home.html") && view != null && view.getContext() != null) {
            boolean isDark = ThemeManager.isAppDarkTheme(view.getContext());
            String themeMode = isDark ? "dark" : "light";
            long totalBlocked = com.privacy.browser.adblock.AdBlockManager.getInstance(view.getContext()).getTotalBlockedCount();
            String js = "if (typeof applyTheme === 'function') applyTheme('" + themeMode + "'); if (typeof refreshHomeData === 'function') refreshHomeData(" + totalBlocked + ");";
            view.evaluateJavascript(js, null);
        }

        listener.onPageFinished(url);
    }

    private void collapseFailedSubframe(@Nullable WebView view, @Nullable WebResourceRequest request) {
        if (view == null || request == null || request.isForMainFrame()) {
            return;
        }
        Uri uri = request.getUrl();
        if (uri == null) return;
        final String u = uri.toString().replace("'", "\\'");
        final String host = uri.getHost() != null ? uri.getHost().replace("'", "\\'") : "";
        final String path = uri.getPath() != null ? uri.getPath().replace("'", "\\'") : "";
        String js = "(function() {" +
            "  try {" +
            "    var u = '" + u + "';" +
            "    var host = '" + host + "';" +
            "    var path = '" + path + "';" +
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
        view.evaluateJavascript(js, null);
    }

    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
        super.onReceivedError(view, request, error);
        collapseFailedSubframe(view, request);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
        super.onReceivedError(view, errorCode, description, failingUrl);
        if (failingUrl != null && view != null && !failingUrl.equals(currentUrl)) {
            String u = failingUrl.replace("'", "\\'");
            String js = "(function() {" +
                "  try {" +
                "    var u = '" + u + "';" +
                "    var iframes = document.querySelectorAll('iframe, frame, embed, object');" +
                "    for (var i = 0; i < iframes.length; i++) {" +
                "      var f = iframes[i];" +
                "      var s = f.src || f.getAttribute('src') || '';" +
                "      if (u && s.indexOf(u) !== -1) {" +
                "        f.style.setProperty('display', 'none', 'important');" +
                "        f.style.setProperty('width', '0px', 'important');" +
                "        f.style.setProperty('height', '0px', 'important');" +
                "        f.style.setProperty('visibility', 'hidden', 'important');" +
                "        var p = f.parentElement;" +
                "        while (p && p !== document.body && p !== document.documentElement) {" +
                "          p.style.setProperty('display', 'none', 'important');" +
                "          p.style.setProperty('height', '0px', 'important');" +
                "          p.style.setProperty('min-height', '0px', 'important');" +
                "          p = p.parentElement;" +
                "        }" +
                "      }" +
                "    }" +
                "  } catch(e) {}" +
                "})();";
            view.evaluateJavascript(js, null);
        }
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

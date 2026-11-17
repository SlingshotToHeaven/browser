package com.privacy.browser.adblock;

import android.content.Context;
import android.util.Log;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.util.Collections;
import java.util.Set;

/**
 * uBlock Origin-grade YouTube ad defuser.
 * Strips adPlacements from ytInitialPlayerResponse, hooks JSON.parse and Response.prototype.json,
 * fast-forwards and auto-clicks video ad skip buttons, and cosmetically neutralizes sponsored video cards.
 */
public class YouTubeAdDefuser {

    public static final String SCRIPT_JS =
        "(function() {" +
        "  try {" +
        "    if (window.PrivacyAntiAdblockBridge && typeof window.PrivacyAntiAdblockBridge.isAdBlockActive === 'function') {" +
        "      if (!window.PrivacyAntiAdblockBridge.isAdBlockActive(window.location.href)) return;" +
        "    }" +
        "  } catch(e) {}" +
        "  if (window.__privacy_yt_defuser_installed) return;" +
        "  window.__privacy_yt_defuser_installed = true;" +
        "  function stripAdsFromPlayerResponse(obj) {" +
        "    if (!obj || typeof obj !== 'object') return obj;" +
        "    try {" +
        "      if (obj.adPlacements) delete obj.adPlacements;" +
        "      if (obj.adSlots) delete obj.adSlots;" +
        "      if (obj.playerAds) delete obj.playerAds;" +
        "      if (obj.adBreakHeartbeatParams) delete obj.adBreakHeartbeatParams;" +
        "      if (obj.playbackTracking) {" +
        "        if (obj.playbackTracking.videostatsPlaybackUrl) delete obj.playbackTracking.videostatsPlaybackUrl;" +
        "        if (obj.playbackTracking.videostatsDelayplayUrl) delete obj.playbackTracking.videostatsDelayplayUrl;" +
        "        if (obj.playbackTracking.videostatsWatchtimeUrl) delete obj.playbackTracking.videostatsWatchtimeUrl;" +
        "      }" +
        "    } catch(e) {}" +
        "    return obj;" +
        "  }" +
        "  var _ytInitialPlayerResponse;" +
        "  try {" +
        "    Object.defineProperty(window, 'ytInitialPlayerResponse', {" +
        "      set: function(val) {" +
        "        _ytInitialPlayerResponse = stripAdsFromPlayerResponse(val);" +
        "      }," +
        "      get: function() { return _ytInitialPlayerResponse; }," +
        "      configurable: true" +
        "    });" +
        "  } catch(e) {}" +
        "  if (window.ytInitialPlayerResponse) {" +
        "    stripAdsFromPlayerResponse(window.ytInitialPlayerResponse);" +
        "  }" +
        "  try {" +
        "    var origJsonParse = JSON.parse;" +
        "    JSON.parse = function() {" +
        "      var res = origJsonParse.apply(this, arguments);" +
        "      if (res && typeof res === 'object') {" +
        "        if (res.adPlacements || res.playerAds || res.adSlots) {" +
        "          stripAdsFromPlayerResponse(res);" +
        "        }" +
        "      }" +
        "      return res;" +
        "    };" +
        "  } catch(e) {}" +
        "  try {" +
        "    if (window.Response && window.Response.prototype && window.Response.prototype.json) {" +
        "      var origJson = window.Response.prototype.json;" +
        "      window.Response.prototype.json = function() {" +
        "        return origJson.apply(this, arguments).then(function(data) {" +
        "          return stripAdsFromPlayerResponse(data);" +
        "        });" +
        "      };" +
        "    }" +
        "  } catch(e) {}" +
        "  function skipVideoAds() {" +
        "    try {" +
        "      var video = document.querySelector('video');" +
        "      var adShowing = document.querySelector('.ad-showing, .ad-interrupting, .video-ads, [class*=\"ytp-ad-module\"]');" +
        "      if (adShowing && video) {" +
        "        video.muted = true;" +
        "        video.playbackRate = 16.0;" +
        "        if (isFinite(video.duration) && video.duration > 0) {" +
        "          video.currentTime = video.duration;" +
        "        }" +
        "      }" +
        "      var skipSelectors = [" +
        "        '.ytp-ad-skip-button'," +
        "        '.ytp-ad-skip-button-modern'," +
        "        '.ytp-skip-ad-button'," +
        "        '.videoAdUiSkipButton'," +
        "        'button[class*=\"skip\"]'," +
        "        '.ytp-ad-overlay-close-button'" +
        "      ];" +
        "      for (var i = 0; i < skipSelectors.length; i++) {" +
        "        var btn = document.querySelector(skipSelectors[i]);" +
        "        if (btn) btn.click();" +
        "      }" +
        "    } catch(e) {}" +
        "  }" +
        "  setInterval(skipVideoAds, 150);" +
        "  try {" +
        "    if (!document.getElementById('__privacy_yt_css')) {" +
        "      var style = document.createElement('style');" +
        "      style.id = '__privacy_yt_css';" +
        "      style.textContent = " +
        "        'ytd-ad-slot-renderer, ytd-in-feed-ad-layout-renderer, ytd-banner-promo-renderer, ' +" +
        "        'ytm-promoted-sparkles-web-renderer, ytm-companion-ad-renderer, ytm-promoted-video-renderer, ' +" +
        "        '.ytp-ad-module, .ytp-ad-overlay-container, #player-ads, #masthead-ad, ' +" +
        "        '#panels:has(ytd-ads-engagement-panel-content-renderer) ' +" +
        "        '{ display: none !important; visibility: hidden !important; height: 0 !important; }';" +
        "      (document.head || document.documentElement).appendChild(style);" +
        "    }" +
        "  } catch(e) {}" +
        "})();";

    private static volatile String cachedScriptJs = null;

    @NonNull
    public static String getScriptJs(@NonNull android.content.Context context) {
        if (cachedScriptJs != null) return cachedScriptJs;
        synchronized (YouTubeAdDefuser.class) {
            if (cachedScriptJs != null) return cachedScriptJs;
            AdBlockFileManager fileManager = AdBlockFileManager.getInstance(context);
            String js = fileManager.readFileContent(AdBlockFileManager.Category.SCRIPTLETS, AdBlockFileManager.FILE_SCRIPTLET_YOUTUBE_DEFUSER);
            if (js == null || js.trim().isEmpty()) {
                js = SCRIPT_JS;
            }
            cachedScriptJs = js;
            return cachedScriptJs;
        }
    }

    public static void clearCache() {
        synchronized (YouTubeAdDefuser.class) {
            cachedScriptJs = null;
        }
    }

    public static void attachDocumentStart(@NonNull Context context, @NonNull WebView webView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Set<String> allowedOrigins = Collections.singleton("*");
            try {
                WebViewCompat.addDocumentStartJavaScript(webView, getScriptJs(context), allowedOrigins);
                Log.i("YouTubeAdDefuser", "YouTube ad defuser registered at document_start.");
            } catch (Exception e) {
                Log.w("YouTubeAdDefuser", "Failed to register document_start YouTube defuser: " + e.getMessage());
            }
        }
    }

    public static void inject(@NonNull WebView webView) {
        String js = getScriptJs(webView.getContext().getApplicationContext());
        webView.evaluateJavascript(js, null);
    }
}

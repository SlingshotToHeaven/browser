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
 * Scriptlet engine neutralizing rogue popups, tab-unders, window.open hijacking,
 * and transparent full-screen clickjacking overlays from ad networks.
 */
public class PopupClickDefuser {

    public static final String DEFUSER_JS =
        "(function() {" +
        "  try {" +
        "    if (window.PrivacyAntiAdblockBridge && typeof window.PrivacyAntiAdblockBridge.isAdBlockActive === 'function') {" +
        "      if (!window.PrivacyAntiAdblockBridge.isAdBlockActive(window.location.href)) return;" +
        "    }" +
        "  } catch(e) {}" +
        "  if (window.__ublock_popup_click_defused) return;" +
        "  window.__ublock_popup_click_defused = true;" +
        "  function report(name, detail) {" +
        "    try {" +
        "      if (window.PrivacyAntiAdblockBridge && typeof window.PrivacyAntiAdblockBridge.reportAntiAdblockDefeated === 'function') {" +
        "        window.PrivacyAntiAdblockBridge.reportAntiAdblockDefeated(name, detail);" +
        "      }" +
        "    } catch(e) {}" +
        "  }" +
        "  var adSignatures = [" +
        "    'popads', 'propellerads', 'adcash', 'exoclick', 'clickadu', 'revenuehits'," +
        "    'popcash', 'zeropark', 'richads', 'trafficstars', 'juicyads', 'adsterra'," +
        "    'syndication.exoclick', 'servedby.', 'creative.ak.fbcdn', 'doubleclick'," +
        "    'googleadservices', 'googlesyndication', 'pagead', 'taboola', 'outbrain'," +
        "    'mgid', 'revcontent', 'criteo', 'infolinks', 'bidvertiser', 'ad-maven'," +
        "    'yllix', 'hilltopads', 'clksite', 'onclickmega', 'directrev', 'adk2x'," +
        "    'adxcore', 'vidoomy', 'coinhive', 'monetag', 'adbutler', 'galaksion'" +
        "  ];" +
        "  function isAdUrl(u) {" +
        "    if (!u || typeof u !== 'string') return false;" +
        "    var lower = u.toLowerCase();" +
        "    for (var i = 0; i < adSignatures.length; i++) {" +
        "      if (lower.indexOf(adSignatures[i]) !== -1) return true;" +
        "    }" +
        "    return false;" +
        "  }" +
        "  var lastUserGestureTime = 0;" +
        "  function noteUserGesture() {" +
        "    lastUserGestureTime = Date.now();" +
        "  }" +
        "  window.addEventListener('click', noteUserGesture, true);" +
        "  window.addEventListener('keydown', noteUserGesture, true);" +
        "  window.addEventListener('touchend', noteUserGesture, true);" +
        "  try {" +
        "    var origOpen = window.open;" +
        "    window.open = function(url, target, features) {" +
        "      var isAd = isAdUrl(url);" +
        "      var recentGesture = (Date.now() - lastUserGestureTime) < 1500;" +
        "      if (isAd || (!recentGesture && isAdUrl(url))) {" +
        "        report('Popup Blocker', 'Neutralized window.open ad popup: ' + (url || 'blank'));" +
        "        return { closed: true, close: function(){}, focus: function(){}, postMessage: function(){} };" +
        "      }" +
        "      return origOpen.apply(this, arguments);" +
        "    };" +
        "  } catch(e) {}" +
        "  function defuseClickjackOverlays() {" +
        "    try {" +
        "      var all = document.querySelectorAll('div, a, section, span');" +
        "      var vw = window.innerWidth || document.documentElement.clientWidth;" +
        "      var vh = window.innerHeight || document.documentElement.clientHeight;" +
        "      for (var i = 0; i < all.length; i++) {" +
        "        var el = all[i];" +
        "        if (el.id && el.id.startsWith('__ublock_')) continue;" +
        "        var idClass = ((el.id || '') + ' ' + (el.className || '') + ' ' + (el.getAttribute('role') || '') + ' ' + (el.getAttribute('aria-modal') || '')).toLowerCase();" +
        "        if (idClass.indexOf('cookie') !== -1 || idClass.indexOf('consent') !== -1 || idClass.indexOf('modal') !== -1 || idClass.indexOf('dialog') !== -1 || idClass.indexOf('privacy') !== -1) continue;" +
        "        var style = window.getComputedStyle(el);" +
        "        if (style.position === 'fixed' || style.position === 'absolute') {" +
        "          var zIndex = parseInt(style.zIndex, 10);" +
        "          var opacity = parseFloat(style.opacity);" +
        "          var rect = el.getBoundingClientRect();" +
        "          if (zIndex >= 1000 && (opacity <= 0.05 || style.backgroundColor === 'transparent' || style.backgroundColor === 'rgba(0, 0, 0, 0)')) {" +
        "            if (rect.width >= vw * 0.5 && rect.height >= vh * 0.5) {" +
        "              el.remove();" +
        "              report('Click-Jack Defuser', 'Removed invisible full-screen ad overlay');" +
        "            }" +
        "          }" +
        "        }" +
        "      }" +
        "    } catch(e) {}" +
        "  }" +
        "  try {" +
        "    document.addEventListener('click', function(e) {" +
        "      var a = e.target && e.target.closest ? e.target.closest('a') : null;" +
        "      if (a && a.href) {" +
        "        if (isAdUrl(a.href)) {" +
        "          e.preventDefault();" +
        "          e.stopPropagation();" +
        "          e.stopImmediatePropagation();" +
        "          report('Ad Link Shield', 'Blocked tap on ad redirect link: ' + a.href);" +
        "        }" +
        "      }" +
        "    }, true);" +
        "  } catch(e) {}" +
        "  if (document.readyState === 'loading') {" +
        "    document.addEventListener('DOMContentLoaded', defuseClickjackOverlays);" +
        "  } else {" +
        "    defuseClickjackOverlays();" +
        "  }" +
        "  setInterval(defuseClickjackOverlays, 1500);" +
        "})();";

    private static volatile String cachedDefuserJs = null;

    @NonNull
    public static String getDefuserJs(@NonNull android.content.Context context) {
        if (cachedDefuserJs != null) return cachedDefuserJs;
        synchronized (PopupClickDefuser.class) {
            if (cachedDefuserJs != null) return cachedDefuserJs;
            AdBlockFileManager fileManager = AdBlockFileManager.getInstance(context);
            String js = fileManager.readFileContent(AdBlockFileManager.Category.SCRIPTLETS, AdBlockFileManager.FILE_SCRIPTLET_POPUP_DEFUSER);
            if (js == null || js.trim().isEmpty()) {
                js = DEFUSER_JS;
            }
            cachedDefuserJs = js;
            return cachedDefuserJs;
        }
    }

    public static void clearCache() {
        synchronized (PopupClickDefuser.class) {
            cachedDefuserJs = null;
        }
    }

    public static void attachDocumentStart(@NonNull Context context, @NonNull WebView webView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Set<String> allowedOrigins = Collections.singleton("*");
            try {
                WebViewCompat.addDocumentStartJavaScript(webView, getDefuserJs(context), allowedOrigins);
                Log.i("PopupClickDefuser", "Popup & click-jack defuser registered at document_start.");
            } catch (Exception e) {
                Log.w("PopupClickDefuser", "Failed to register document_start popup defuser: " + e.getMessage());
            }
        }
    }

    public static void inject(@NonNull WebView webView) {
        String js = getDefuserJs(webView.getContext().getApplicationContext());
        webView.evaluateJavascript(js, null);
    }
}

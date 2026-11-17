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
 * Scriptlet injection engine neutralizing anti-adblock detection routines (FuckAdBlock, BlockAdBlock,
 * bait element height checks, AdSense canary probes, and blocking modal barriers).
 */
public class AntiAdblockDefuser {

    private static final String TAG = "AntiAdblockDefuser";

    public static final String SCRIPTLET_JS =
        "(function() {" +
        "  try {" +
        "    if (window.PrivacyAntiAdblockBridge && typeof window.PrivacyAntiAdblockBridge.isAdBlockActive === 'function') {" +
        "      if (!window.PrivacyAntiAdblockBridge.isAdBlockActive(window.location.href)) return;" +
        "    }" +
        "  } catch(e) {}" +
        "  if (window.__privacy_antiadblock_defused) return;" +
        "  window.__privacy_antiadblock_defused = true;" +
        "  function report(name, detail) {" +
        "    try {" +
        "      if (window.PrivacyAntiAdblockBridge && typeof window.PrivacyAntiAdblockBridge.reportAntiAdblockDefeated === 'function') {" +
        "        window.PrivacyAntiAdblockBridge.reportAntiAdblockDefeated(name, detail);" +
        "      }" +
        "    } catch(e) {}" +
        "  }" +
        "  try {" +
        "    function createDummyFAB() {" +
        "      var def = function() { return def; };" +
        "      def.on = function(detected, fn) {" +
        "        if (!detected && typeof fn === 'function') {" +
        "          try { setTimeout(fn, 1); } catch(e) {}" +
        "          report('FuckAdBlock Hook', 'Suppressed adblock detection callback and fired clean branch');" +
        "        }" +
        "        return def;" +
        "      };" +
        "      def.onDetected = function(fn) { return def; };" +
        "      def.onNotDetected = function(fn) {" +
        "        if (typeof fn === 'function') {" +
        "          try { setTimeout(fn, 1); } catch(e) {}" +
        "          report('BlockAdBlock Hook', 'Fired clean non-detected callback');" +
        "        }" +
        "        return def;" +
        "      };" +
        "      def.check = function() { return false; };" +
        "      def.clearEvent = function() {};" +
        "      def.setOption = function() {};" +
        "      return def;" +
        "    };" +
        "    var dummy = createDummyFAB();" +
        "    window.FuckAdBlock = dummy;" +
        "    window.fuckAdBlock = dummy;" +
        "    window.BlockAdBlock = dummy;" +
        "    window.blockAdBlock = dummy;" +
        "    window.Sniffer = dummy;" +
        "  } catch(e) {}" +
        "  window.canRunAds = true;" +
        "  window.isAdBlockActive = false;" +
        "  window.adblock = false;" +
        "  window.adblocker = false;" +
        "  window.google_ad_status = 1;" +
        "  window.isAdblock = false;" +
        "  window.adBlocker = false;" +
        "  window.adBlockDetected = false;" +
        "  try {" +
        "    window.ga = window.ga || function(){};" +
        "    window.gtag = window.gtag || function(){};" +
        "    window.dataLayer = window.dataLayer || [];" +
        "    window.fbq = window.fbq || function(){};" +
        "    window.twq = window.twq || function(){};" +
        "    window.ttq = window.ttq || function(){};" +
        "    window.snaptr = window.snaptr || function(){};" +
        "  } catch(e) {}" +
        "  try {" +
        "    if (!window.adsbygoogle) window.adsbygoogle = [];" +
        "    window.adsbygoogle.push = function(arg) {" +
        "      report('Google AdSense Probe', 'Intercepted adsbygoogle.push probe and supplied clean stub');" +
        "      return 1;" +
        "    };" +
        "    window.adsbygoogle.loaded = true;" +
        "  } catch(e) {}" +
        "  try {" +
        "    var baitRegex = /(ad[_-]?banner|adsbox|ad[_-]?holder|ad[_-]?sense|ad[_-]?frame|carbonads|pub_300x250)/i;" +
        "    var origOffsetHeight = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight');" +
        "    if (origOffsetHeight && origOffsetHeight.get) {" +
        "      Object.defineProperty(HTMLElement.prototype, 'offsetHeight', {" +
        "        get: function() {" +
        "          var idOrClass = (this.id || '') + ' ' + (this.className || '');" +
        "          if (baitRegex.test(idOrClass)) {" +
        "            report('Bait Element Probe', 'Spoofed non-zero offsetHeight for: ' + (this.id || this.className));" +
        "            return 250;" +
        "          }" +
        "          return origOffsetHeight.get.call(this);" +
        "        }," +
        "        configurable: true" +
        "      });" +
        "    }" +
        "  } catch(e) {}" +
        "  function neutralizeModals() {" +
        "    try {" +
        "      var sel = '[id*=\"adblock-modal\"], [class*=\"adblock-modal\"], [id*=\"adblock-notice\"], [class*=\"adblock-notice\"], .adblock-overlay, #ab-message, .sp-message-container, div[id*=\"adblock_detected\"], div[class*=\"adblock_detected\"], .ad-block-warning, #adblock-popup, .anti-adblock, [class*=\"fc-ab-root\"]';" +
        "      var list = document.querySelectorAll(sel);" +
        "      if (list && list.length > 0) {" +
        "        for (var i = 0; i < list.length; i++) {" +
        "          list[i].style.setProperty('display', 'none', 'important');" +
        "        }" +
        "        if (document.body) {" +
        "          document.body.style.setProperty('overflow', 'auto', 'important');" +
        "          document.body.style.setProperty('position', 'static', 'important');" +
        "        }" +
        "        if (document.documentElement) {" +
        "          document.documentElement.style.setProperty('overflow', 'auto', 'important');" +
        "        }" +
        "        report('Anti-Adblock Modal Barrier', 'Removed blocking overlay modal and restored page scrolling');" +
        "      }" +
        "    } catch(e) {}" +
        "  }" +
        "  if (document.readyState === 'loading') {" +
        "    document.addEventListener('DOMContentLoaded', neutralizeModals);" +
        "  } else {" +
        "    neutralizeModals();" +
        "  }" +
        "  setTimeout(neutralizeModals, 1000);" +
        "  setTimeout(neutralizeModals, 2500);" +
        "})();";

    public static final String COSMETIC_FILTER_JS =
        "(function() {" +
        "  try {" +
        "    if (window.PrivacyAntiAdblockBridge && typeof window.PrivacyAntiAdblockBridge.isCosmeticEnabled === 'function') {" +
        "      if (!window.PrivacyAntiAdblockBridge.isCosmeticEnabled(window.location.href)) return;" +
        "    } else if (window.PrivacyAntiAdblockBridge && typeof window.PrivacyAntiAdblockBridge.isAdBlockActive === 'function') {" +
        "      if (!window.PrivacyAntiAdblockBridge.isAdBlockActive(window.location.href)) return;" +
        "    }" +
        "  } catch(e) {}" +
        "  if (document.getElementById('__privacy_shield_css')) return;" +
        "  var style = document.createElement('style');" +
        "  style.id = '__privacy_shield_css';" +
        "  var selectors = " +
        "    '.advertisement, .adsbygoogle, ins.adsbygoogle, amp-ad, [id*=\"google_ads\"], [name*=\"google_ads\"], [id*=\"aswift\"], [name*=\"aswift\"], ' +" +
        "    'div[id^=\"div-gpt-ad\"], div[id*=\"-gpt-ad-\"], div[id*=\"_gpt_ad_\"], ' +" +
        "    '.adHolder, .adHolder.mpu, div[id^=\"mpu_\"], ' +" +
        "    '.dmg-ads, .dmg-ads-container, .dmg-ads-ad, .dmg-ads-label, .dmg-ads-label-container, ' +" +
        "    '.mol-ads-cmp, .mol-ads-label-container, ' +" +
        "    '.mpu_puff_wrapper, .billboard-container, .connatix-placeholder, .connatix, ' +" +
        "    '[class*=\"ad-banner\"], [class*=\"ad_banner\"], [id*=\"ad-banner\"], [id*=\"ad_banner\"], ' +" +
        "    '.sponsored-post, [data-ad-slot], [data-adunit], [data-ad-client], ' +" +
        "    '.ad-container, .ad-wrapper, .ad-box, .ad-unit, .ads-holder, .ad-placement, .ad_wrapper, .ad_container, .ad_box, ' +" +
        "    '[class*=\"ad-holder\"], [class*=\"ad_holder\"], [class*=\"ad-space\"], [class*=\"ad-zone\"], [class*=\"ad-slot\"], [class*=\"ad_slot\"], ' +" +
        "    'div[id*=\"billboard\"], div[id*=\"leaderboard\"], ' +" +
        "    '.taboola, [class*=\"taboola\"], [id*=\"taboola\"], .outbrain, [class*=\"outbrain\"], [id*=\"outbrain\"], .trc_related_container, ' +" +
        "    '.mgid, [class*=\"mgid\"], [id*=\"mgid\"], .revcontent, [class*=\"revcontent\"], [id*=\"revcontent\"], ' +" +
        "    '.criteo, [class*=\"criteo\"], [id*=\"criteo\"], .native-ad, .native_ad, .nativo, [class*=\"nativo\"], ' +" +
        "    '.sticky-banner, .bottom-banner, .floating-ad, .floating-banner, .bottom-ad-bar, .sticky-ad, .popup-ad, [class*=\"interstitial\"], ' +" +
        "    'iframe[src*=\"doubleclick\"], iframe[src*=\"googlesyndication\"], iframe[src*=\"googleads\"], iframe[src*=\"adservice\"], iframe[src*=\"adnxs\"], ' +" +
        "    'iframe[src*=\"2mdn.net\"], iframe[src*=\"outbrain\"], iframe[src*=\"taboola\"], iframe[src*=\"criteo\"], iframe[src*=\"amazon-adsystem\"], ' +" +
        "    'iframe[id*=\"google_ads\"], iframe[name*=\"google_ads\"], iframe[id*=\"aswift\"], iframe[name*=\"aswift\"], ' +" +
        "    'iframe[title*=\"advertisement\" i], iframe[title*=\"ad frame\" i], ' +" +
        "    '.video-ads, .ytp-ad-module, .ytp-ad-overlay-container, .ytp-ad-player-overlay, ' +" +
        "    'a[href*=\"/adclick\"], a[href*=\"/pagead/\"], a[href*=\"doubleclick.net\"], ' +" +
        "    'a[href*=\"exoclick.com\"], a[href*=\"popads.net\"], a[href*=\"propellerads.com\"], ' +" +
        "    'a[href*=\"adcash.com\"], a[href*=\"adsterra.com\"], a[href*=\"syndication.exoclick\"]'; " +
        "  style.textContent = selectors + ' { display: none !important; visibility: hidden !important; height: 0 !important; max-height: 0 !important; min-height: 0 !important; margin: 0 !important; padding: 0 !important; border: none !important; pointer-events: none !important; opacity: 0 !important; }';" +
        "  var target = document.head || document.documentElement;" +
        "  if (target) { target.appendChild(style); }" +
        "  else { document.addEventListener('DOMContentLoaded', function() { (document.head || document.documentElement).appendChild(style); }); }" +
        "  function pruneAds(root) {" +
        "    try {" +
        "      var doc = root || document;" +
        "      var nodes = doc.querySelectorAll(selectors);" +
        "      for (var i = 0; i < nodes.length; i++) {" +
        "        var node = nodes[i];" +
        "        if (node && node.id !== '__privacy_shield_css' && node.tagName !== 'BODY' && node.tagName !== 'HTML') {" +
        "          if (node.tagName === 'ARTICLE' || node.tagName === 'MAIN' || node.tagName === 'SECTION') continue;" +
        "          if (node.querySelector && node.querySelector('p.mol-para-with-font, article, main')) continue;" +
        "          node.style.setProperty('display', 'none', 'important');" +
        "          node.style.setProperty('height', '0px', 'important');" +
        "          node.style.setProperty('min-height', '0px', 'important');" +
        "          node.style.setProperty('max-height', '0px', 'important');" +
        "          node.style.setProperty('margin', '0px', 'important');" +
        "          node.style.setProperty('padding', '0px', 'important');" +
        "          node.style.setProperty('border', 'none', 'important');" +
        "        }" +
        "      }" +
        "      var iframes = doc.querySelectorAll('iframe, embed, object');" +
        "      for (var j = 0; j < iframes.length; j++) {" +
        "        var f = iframes[j];" +
        "        var s = f.src || f.getAttribute('src') || '';" +
        "        var idClass = (f.id || '') + ' ' + (f.className || '') + ' ' + (f.name || '');" +
        "        if (/googleads|doubleclick|googlesyndication|adservice|2mdn\\.net|adnxs|smartadserver|criteo|rubicon|pubmatic|amazon-adsystem|openx|taboola|outbrain|revcontent|mgid|connatix/i.test(s)" +
        "            || /google_ads|aswift|adHolder|dmg-ads|connatix/i.test(idClass)" +
        "            || /advertisement|ad frame/i.test(f.getAttribute('title') || '')) {" +
        "          f.style.setProperty('display', 'none', 'important');" +
        "          f.style.setProperty('width', '0px', 'important');" +
        "          f.style.setProperty('height', '0px', 'important');" +
        "          f.style.setProperty('visibility', 'hidden', 'important');" +
        "        }" +
        "      }" +
        "    } catch(e) {}" +
        "  }" +
        "  pruneAds(document);" +
        "  if (document.readyState === 'loading') {" +
        "    document.addEventListener('DOMContentLoaded', function() { pruneAds(document); });" +
        "  } else {" +
        "    pruneAds(document);" +
        "  }" +
        "  setTimeout(function() { pruneAds(document); }, 500);" +
        "  setTimeout(function() { pruneAds(document); }, 1500);" +
        "  setTimeout(function() { pruneAds(document); }, 3000);" +
        "  if (window.MutationObserver && !window.__privacy_ad_observer_active) {" +
        "    window.__privacy_ad_observer_active = true;" +
        "    var pending = false;" +
        "    var observer = new window.MutationObserver(function() {" +
        "      if (pending) return;" +
        "      pending = true;" +
        "      (window.requestAnimationFrame || window.setTimeout)(function() {" +
        "        pending = false;" +
        "        pruneAds(document);" +
        "      }, 32);" +
        "    });" +
        "    observer.observe(document.documentElement || document, { childList: true, subtree: true });" +
        "  }" +
        "})();";

    private static volatile String cachedScriptletJs = null;
    private static volatile String cachedCosmeticJs = null;

    @NonNull
    public static String getScriptletJs(@NonNull Context context) {
        if (cachedScriptletJs != null) return cachedScriptletJs;
        synchronized (AntiAdblockDefuser.class) {
            if (cachedScriptletJs != null) return cachedScriptletJs;
            AdBlockFileManager fileManager = AdBlockFileManager.getInstance(context);
            String js = fileManager.readFileContent(AdBlockFileManager.Category.SCRIPTLETS, AdBlockFileManager.FILE_SCRIPTLET_ANTI_ADBLOCK);
            if (js == null || js.trim().isEmpty()) {
                js = SCRIPTLET_JS;
            }
            cachedScriptletJs = js;
            return cachedScriptletJs;
        }
    }

    @NonNull
    public static String getCosmeticJs(@NonNull Context context) {
        if (cachedCosmeticJs != null) return cachedCosmeticJs;
        synchronized (AntiAdblockDefuser.class) {
            if (cachedCosmeticJs != null) return cachedCosmeticJs;
            AdBlockFileManager fileManager = AdBlockFileManager.getInstance(context);
            String js = fileManager.readFileContent(AdBlockFileManager.Category.SCRIPTLETS, AdBlockFileManager.FILE_SCRIPTLET_COSMETIC_PRUNER);
            if (js == null || js.trim().isEmpty()) {
                js = COSMETIC_FILTER_JS;
            }
            cachedCosmeticJs = js;
            return cachedCosmeticJs;
        }
    }

    public static void clearCache() {
        synchronized (AntiAdblockDefuser.class) {
            cachedScriptletJs = null;
            cachedCosmeticJs = null;
        }
    }

    public static void attachDocumentStart(@NonNull Context context, @NonNull WebView webView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Set<String> allowedOrigins = Collections.singleton("*");
            try {
                WebViewCompat.addDocumentStartJavaScript(webView, getCosmeticJs(context), allowedOrigins);
                WebViewCompat.addDocumentStartJavaScript(webView, getScriptletJs(context), allowedOrigins);
                UserScriptManager.attachDocumentStart(context, webView);
                Log.i(TAG, "Cosmetic element pruner & anti-adblock defuser registered at document_start.");
            } catch (Exception e) {
                Log.w(TAG, "Failed to register document_start scripts: " + e.getMessage());
            }
        }
    }

    public static void inject(@NonNull WebView webView, boolean cosmeticEnabled, boolean antiAdblockEnabled) {
        Context context = webView.getContext().getApplicationContext();
        if (antiAdblockEnabled) {
            webView.evaluateJavascript(getScriptletJs(context), null);
        }
        if (cosmeticEnabled) {
            webView.evaluateJavascript(getCosmeticJs(context), null);
        }
    }
}

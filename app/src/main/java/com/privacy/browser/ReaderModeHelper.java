package com.privacy.browser;

import android.webkit.WebView;

/**
 * Extracts and displays article contents in distraction-free pitch-black AMOLED typography.
 */
public class ReaderModeHelper {

    private static final String TOGGLE_READER_JS =
        "(function() {" +
        "  var existing = document.getElementById('privacy-reader-overlay');" +
        "  if (existing) {" +
        "    existing.remove();" +
        "    if (document.body) document.body.style.overflow = '';" +
        "    return 'disabled';" +
        "  }" +
        "  var article = document.querySelector('article') || document.querySelector('main') || document.querySelector('.post-content') || document.querySelector('#content') || document.body;" +
        "  if (!article) return 'no_content';" +
        "  var title = (document.querySelector('h1') ? document.querySelector('h1').innerText : document.title) || 'Reader View';" +
        "  var clone = article.cloneNode(true);" +
        "  var junk = clone.querySelectorAll('script, style, iframe, nav, header, footer, .ad, .advertisement, [id*=\"google_ads\"], .social-share, .comments');" +
        "  junk.forEach(function(el) { el.remove(); });" +
        "  var overlay = document.createElement('div');" +
        "  overlay.id = 'privacy-reader-overlay';" +
        "  overlay.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;z-index:2147483647;background:#000000;color:#E0E0E0;font-family:-apple-system,sans-serif;line-height:1.75;padding:28px 18px 48px;overflow-y:auto;-webkit-overflow-scrolling:touch;font-size:18px;';" +
        "  var wrapper = document.createElement('div');" +
        "  wrapper.style.cssText = 'max-width:680px;margin:0 auto;';" +
        "  var h1 = document.createElement('h1');" +
        "  h1.innerText = title;" +
        "  h1.style.cssText = 'color:#FFFFFF;font-size:24px;line-height:1.3;margin-bottom:24px;font-weight:bold;border-bottom:1px solid #242436;padding-bottom:14px;';" +
        "  wrapper.appendChild(h1);" +
        "  var contentDiv = document.createElement('div');" +
        "  contentDiv.innerHTML = clone.innerHTML;" +
        "  var links = contentDiv.querySelectorAll('a');" +
        "  links.forEach(function(a) { a.style.color = '#00E5FF'; });" +
        "  var imgs = contentDiv.querySelectorAll('img');" +
        "  imgs.forEach(function(img) { img.style.maxWidth = '100%'; img.style.height = 'auto'; img.style.borderRadius = '8px'; img.style.margin = '14px 0'; });" +
        "  wrapper.appendChild(contentDiv);" +
        "  overlay.appendChild(wrapper);" +
        "  if (document.body) {" +
        "    document.body.appendChild(overlay);" +
        "    document.body.style.overflow = 'hidden';" +
        "  }" +
        "  return 'enabled';" +
        "})();";

    public static void toggleReaderMode(WebView webView) {
        if (webView == null) return;
        webView.evaluateJavascript(TOGGLE_READER_JS, null);
    }
}

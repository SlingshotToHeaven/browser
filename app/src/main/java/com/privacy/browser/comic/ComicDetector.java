package com.privacy.browser.comic;

import android.webkit.WebView;

import androidx.annotation.NonNull;

/**
 * Injects DOM sniffer to identify sequential image strips, paginated comic readers,
 * and high-resolution targets.
 */
public class ComicDetector {

    public static final String DETECTOR_JS =
        "(function() {" +
        "  if (window.__privacy_comic_scanner_running) return;" +
        "  window.__privacy_comic_scanner_running = true;" +
        "  function scanComicPages() {" +
        "    var detectedUrls = [];" +
        "    var seenUrls = {};" +
        "    function addUrl(u) {" +
        "      if (!u || typeof u !== 'string') return;" +
        "      u = u.trim();" +
        "      if (u.length < 5) return;" +
        "      if (u.startsWith('data:') || u.startsWith('blob:') || u.startsWith('javascript:')) return;" +
        "      if (u.indexOf('logo') !== -1 || u.indexOf('avatar') !== -1 || u.indexOf('icon') !== -1 || u.indexOf('banner') !== -1) return;" +
        "      try {" +
        "        var a = document.createElement('a');" +
        "        a.href = u;" +
        "        u = a.href;" +
        "      } catch(e) {}" +
        "      if (!seenUrls[u]) {" +
        "        seenUrls[u] = true;" +
        "        detectedUrls.push(u);" +
        "      }" +
        "    }" +
        "    // 1. Check Global JS arrays / manifests where readers store all page images\n" +
        "    var globalCandidates = [" +
        "      window.ts_reader && window.ts_reader.params && window.ts_reader.params.sources," +
        "      window.chapter_data && window.chapter_data.images," +
        "      window.chapterImages," +
        "      window.pages," +
        "      window.comicPages," +
        "      window.pData && window.pData.imgList," +
        "      window.lstImages," +
        "      window.images," +
        "      window.comic_pages," +
        "      window.chapter_images" +
        "    ];" +
        "    for (var g = 0; g < globalCandidates.length; g++) {" +
        "      var arr = globalCandidates[g];" +
        "      if (arr && Array.isArray(arr) && arr.length >= 2) {" +
        "        for (var k = 0; k < arr.length; k++) {" +
        "          var item = arr[k];" +
        "          if (typeof item === 'string') addUrl(item);" +
        "          else if (item && item.src) addUrl(item.src);" +
        "          else if (item && item.url) addUrl(item.url);" +
        "          else if (item && item.image) addUrl(item.image);" +
        "        }" +
        "        if (detectedUrls.length >= 2) {" +
        "          reportResults(detectedUrls);" +
        "          return;" +
        "        }" +
        "      }" +
        "    }" +
        "    // 2. Check for Paginated Reader Open (e.g. <link>/1, <link>/p/1, <link>/page/1, ?page=1)\n" +
        "    var currentUrl = window.location.href;" +
        "    var readerPathMatch = currentUrl.match(/^(https?:\\/\\/[^?#]+?\\/)(?:(pages?|p)\\/)?(\\d+)\\/?([?#].*)?$/i);" +
        "    var readerQueryMatch = currentUrl.match(/^(https?:\\/\\/[^?#]+[?&](?:pages?|p)=)(\\d+)(.*)?$/i);" +
        "    if (readerPathMatch || readerQueryMatch) {" +
        "      var totalPages = 0;" +
        "      var selects = document.querySelectorAll('select');" +
        "      for (var s = 0; s < selects.length; s++) {" +
        "        var sel = selects[s];" +
        "        var opts = sel.options;" +
        "        if (opts && opts.length >= 2) {" +
        "          var isPageSelect = false;" +
        "          for (var o = 0; o < Math.min(opts.length, 5); o++) {" +
        "            var txt = opts[o].text.trim();" +
        "            var val = opts[o].value.trim();" +
        "            if (/^(?:page\\s*)?\\d+$/i.test(txt) || /^\\d+$/.test(val) || /page/i.test(sel.name || sel.id || sel.className)) {" +
        "              isPageSelect = true;" +
        "              break;" +
        "            }" +
        "          }" +
        "          if (isPageSelect && opts.length > totalPages) {" +
        "            totalPages = opts.length;" +
        "          }" +
        "        }" +
        "      }" +
        "      if (!totalPages) {" +
        "        var navElements = document.querySelectorAll('.pagination, .pager, .nav, .page-nav, .navigation, #navigation, .top-nav, .bot-nav, [class*=\"page\"], [id*=\"page\"]');" +
        "        for (var n = 0; n < navElements.length; n++) {" +
        "          var navText = navElements[n].innerText || '';" +
        "          var tm = navText.match(/(?:page\\s*)?(\\d+)\\s*(?:\\/|of)\\s*(\\d+)/i);" +
        "          if (tm && tm[2]) {" +
        "            var parsed = parseInt(tm[2], 10);" +
        "            if (parsed >= 2 && parsed <= 2000) totalPages = Math.max(totalPages, parsed);" +
        "          }" +
        "        }" +
        "        if (!totalPages && document.body) {" +
        "          var bText = document.body.innerText || '';" +
        "          var bMatch = bText.match(/(?:page\\s*)?(\\d+)\\s*(?:\\/|of)\\s*(\\d+)/i);" +
        "          if (bMatch && bMatch[2]) {" +
        "            var pVal = parseInt(bMatch[2], 10);" +
        "            if (pVal >= 2 && pVal <= 2000) totalPages = pVal;" +
        "          }" +
        "        }" +
        "      }" +
        "      if (!totalPages) {" +
        "        var pageLinks = document.querySelectorAll('a[href*=\"/page/\"], a[href*=\"/p/\"], .pagination a, .pager a');" +
        "        var maxP = 0;" +
        "        for (var pl = 0; pl < pageLinks.length; pl++) {" +
        "          var num = parseInt(pageLinks[pl].innerText.trim(), 10);" +
        "          if (!isNaN(num) && num > maxP && num <= 2000) maxP = num;" +
        "        }" +
        "        if (maxP >= 2) totalPages = maxP;" +
        "      }" +
        "      if (totalPages >= 2) {" +
        "        var readerUrls = [];" +
        "        if (readerPathMatch) {" +
        "          var prefix = readerPathMatch[1];" +
        "          var subDir = readerPathMatch[2] ? (readerPathMatch[2] + '/') : '';" +
        "          var queryPart = readerPathMatch[4] || '';" +
        "          for (var p = 1; p <= totalPages; p++) {" +
        "            readerUrls.push(prefix + subDir + p + queryPart);" +
        "          }" +
        "        } else if (readerQueryMatch) {" +
        "          var qPrefix = readerQueryMatch[1];" +
        "          var qTail = readerQueryMatch[3] || '';" +
        "          for (var q = 1; q <= totalPages; q++) {" +
        "            readerUrls.push(qPrefix + q + qTail);" +
        "          }" +
        "        }" +
        "        if (readerUrls.length >= 2) {" +
        "          reportResults(readerUrls);" +
        "          return;" +
        "        }" +
        "      } else {" +
        "        var mainReaderImg = document.querySelector('#comic-image, #image, .reader-image, .comic-page, #main-image, img.manga-image, div[class*=\"reader\"] img, div[id*=\"reader\"] img');" +
        "        if (mainReaderImg) {" +
        "          var mainSrc = extractHighResUrl(mainReaderImg);" +
        "          if (mainSrc) {" +
        "            addUrl(mainSrc);" +
        "            reportResults(detectedUrls);" +
        "            return;" +
        "          }" +
        "        }" +
        "      }" +
        "    }" +
        "    // 3. Inspect Image with Parent Anchor (full-res or reader link) & High-Res attributes\n" +
        "    function extractHighResUrl(img) {" +
        "      if (!img) return null;" +
        "      var candidate = null;" +
        "      var parentAnchor = img.closest ? img.closest('a') : null;" +
        "      if (parentAnchor && parentAnchor.href) {" +
        "        var aHref = parentAnchor.href;" +
        "        if (/\\.(jpe?g|png|webp|avif|gif)(\\?.*)?$/i.test(aHref)) {" +
        "          return aHref;" +
        "        }" +
        "        if (/(?:\\/pages?|\\/p)?\\/\\d+\\/?([?#].*)?$/i.test(aHref) || /[?&](?:pages?|p)=\\d+/i.test(aHref)) {" +
        "          return aHref;" +
        "        }" +
        "      }" +
        "      var attrs = [" +
        "        'data-src', 'data-original', 'data-full-res', 'data-full', 'data-zoom-src'," +
        "        'data-highres', 'data-origin', 'data-lazy-src', 'data-url', 'data-pic'," +
        "        'data-echo', 'data-file', 'data-orig', 'data-img'" +
        "      ];" +
        "      for (var i = 0; i < attrs.length; i++) {" +
        "        var val = img.getAttribute(attrs[i]);" +
        "        if (val && val.length > 5) {" +
        "          candidate = val;" +
        "          break;" +
        "        }" +
        "      }" +
        "      if (!candidate && img.srcset) {" +
        "        var parts = img.srcset.split(',');" +
        "        var maxW = 0;" +
        "        var bestUrl = null;" +
        "        for (var s = 0; s < parts.length; s++) {" +
        "          var item = parts[s].trim().split(/\\s+/);" +
        "          if (item[0]) {" +
        "            var w = 1;" +
        "            if (item[1]) {" +
        "              var matchW = item[1].match(/(\\d+)/);" +
        "              if (matchW) w = parseInt(matchW[1], 10);" +
        "            }" +
        "            if (w >= maxW) {" +
        "              maxW = w;" +
        "              bestUrl = item[0];" +
        "            }" +
        "          }" +
        "        }" +
        "        if (bestUrl) candidate = bestUrl;" +
        "      }" +
        "      if (!candidate) {" +
        "        candidate = img.currentSrc || img.src;" +
        "      }" +
        "      if (candidate) {" +
        "        candidate = candidate.replace(/\\/thumbs?\\//i, '/');" +
        "        candidate = candidate.replace(/\\/preview\\//i, '/');" +
        "        candidate = candidate.replace(/\\/resize\\/\\d+x\\d+\\//i, '/');" +
        "        candidate = candidate.replace(/([_-])(thumb|small|medium|preview|thumbnail)(\\.[a-z0-9]+)$/i, '$3');" +
        "        candidate = candidate.replace(/(\\?|&)(w|width|size|resize|quality)=\\d+(&|$)/gi, '$1').replace(/[?&]$/, '');" +
        "      }" +
        "      return candidate;" +
        "    }" +
        "    // 4. Continuous strip reader containers\n" +
        "    var containerSelectors = [" +
        "      '.reading-content', '#readerarea', '.comic-page', '.entry-content'," +
        "      '.container-chapter-reader', '.chapter-content', '.viewer', '#viewer'," +
        "      'div[class*=\"chapter\"]', 'div[class*=\"reader\"]', 'div[class*=\"comic\"]'," +
        "      'div[id*=\"reader\"]', 'div[id*=\"comic\"]', '.chapter-images', '.manga-read'," +
        "      '.vung-doc', '.comic-wrap', '#comic-wrap'" +
        "    ];" +
        "    for (var c = 0; c < containerSelectors.length; c++) {" +
        "      var container = document.querySelector(containerSelectors[c]);" +
        "      if (container) {" +
        "        var imgs = container.querySelectorAll('img, picture source');" +
        "        if (imgs && imgs.length >= 2) {" +
        "          for (var j = 0; j < imgs.length; j++) {" +
        "            var src = extractHighResUrl(imgs[j]);" +
        "            if (src) addUrl(src);" +
        "          }" +
        "          if (detectedUrls.length >= 2) {" +
        "            reportResults(detectedUrls);" +
        "            return;" +
        "          }" +
        "        }" +
        "      }" +
        "    }" +
        "    // 5. Fallback: Scan non-tiny images in the DOM\n" +
        "    var allImgs = document.querySelectorAll('img');" +
        "    var candidateBatch = [];" +
        "    for (var m = 0; m < allImgs.length; m++) {" +
        "      var el = allImgs[m];" +
        "      var w = el.naturalWidth || el.offsetWidth || el.width || 0;" +
        "      var h = el.naturalHeight || el.offsetHeight || el.height || 0;" +
        "      if ((w > 0 && w < 100) || (h > 0 && h < 100)) continue;" +
        "      var fullUrl = extractHighResUrl(el);" +
        "      if (fullUrl) {" +
        "        candidateBatch.push(fullUrl);" +
        "      }" +
        "    }" +
        "    if (candidateBatch.length >= 2) {" +
        "      for (var b = 0; b < candidateBatch.length; b++) {" +
        "        addUrl(candidateBatch[b]);" +
        "      }" +
        "      reportResults(detectedUrls);" +
        "    }" +
        "  }" +
        "  function reportResults(urls) {" +
        "    if (!urls || urls.length < 1) return;" +
        "    var title = document.title || 'Comic';" +
        "    var h1 = document.querySelector('h1');" +
        "    if (h1 && h1.innerText && h1.innerText.trim().length > 0) {" +
        "      title = h1.innerText.trim();" +
        "    }" +
        "    try {" +
        "      if (window.ComicDetectorBridge && typeof window.ComicDetectorBridge.onComicDetected === 'function') {" +
        "        window.ComicDetectorBridge.onComicDetected(title, JSON.stringify(urls));" +
        "      }" +
        "    } catch(e) {}" +
        "  }" +
        "  scanComicPages();" +
        "  window.addEventListener('scroll', function() {" +
        "    clearTimeout(window.__comic_scan_timer);" +
        "    window.__comic_scan_timer = setTimeout(scanComicPages, 800);" +
        "  }, { passive: true });" +
        "})();";

    public static void inject(@NonNull WebView webView) {
        webView.evaluateJavascript(DETECTOR_JS, null);
    }
}

package com.privacy.browser.adblock.picker;

import androidx.annotation.NonNull;

/**
 * JavaScript injection engine powering the interactive Element Picker and Element Zapper.
 * Provides touch/pointer tracking, node hierarchy extraction, live CSS preview, and DOM deletion.
 */
public class ElementPickerEngine {

    public static String getInjectionScript(boolean isZapper) {
        return "(function() {" +
            "  if (window.__uBlockPickerActive) {" +
            "    if (window.__uBlockPickerCleanup) window.__uBlockPickerCleanup();" +
            "    return;" +
            "  }" +
            "  window.__uBlockPickerActive = true;" +
            "  var isZapper = " + isZapper + ";" +
            "  var style = document.createElement('style');" +
            "  style.id = '__ublock_picker_style';" +
            "  style.textContent = " +
            "    '.__ublock_highlight { ' +" +
            "    '  outline: 3px dashed ' + (isZapper ? '#FFA000' : '#E53935') + ' !important; ' +" +
            "    '  background-color: ' + (isZapper ? 'rgba(255, 160, 0, 0.3)' : 'rgba(229, 57, 53, 0.3)') + ' !important; ' +" +
            "    '  cursor: crosshair !important; ' +" +
            "    '  box-shadow: 0 0 12px rgba(0,0,0,0.6) !important; ' +" +
            "    '  transition: all 0.1s ease !important; ' +" +
            "    '} ' +" +
            "    '.__ublock_preview_hidden { ' +" +
            "    '  display: none !important; ' +" +
            "    '}';" +
            "  (document.head || document.documentElement).appendChild(style);" +
            "  var currentTarget = null;" +
            "  function clearHighlight() {" +
            "    if (currentTarget) {" +
            "      currentTarget.classList.remove('__ublock_highlight');" +
            "      currentTarget = null;" +
            "    }" +
            "  }" +
            "  function setHighlight(el) {" +
            "    if (!el || el === document.body || el === document.documentElement) return;" +
            "    if (el === currentTarget) return;" +
            "    clearHighlight();" +
            "    currentTarget = el;" +
            "    currentTarget.classList.add('__ublock_highlight');" +
            "  }" +
            "  function onPointerMove(e) {" +
            "    var x = e.clientX || (e.touches && e.touches[0] ? e.touches[0].clientX : 0);" +
            "    var y = e.clientY || (e.touches && e.touches[0] ? e.touches[0].clientY : 0);" +
            "    var el = document.elementFromPoint(x, y);" +
            "    if (el && (!el.id || !el.id.startsWith('__ublock_'))) {" +
            "      setHighlight(el);" +
            "    }" +
            "  }" +
            "  function onClick(e) {" +
            "    e.preventDefault();" +
            "    e.stopPropagation();" +
            "    e.stopImmediatePropagation();" +
            "    var x = e.clientX || (e.changedTouches && e.changedTouches[0] ? e.changedTouches[0].clientX : 0);" +
            "    var y = e.clientY || (e.changedTouches && e.changedTouches[0] ? e.changedTouches[0].clientY : 0);" +
            "    var el = currentTarget || document.elementFromPoint(x, y);" +
            "    if (!el || el === document.body || el === document.documentElement) return;" +
            "    if (isZapper) {" +
            "      el.remove();" +
            "      clearHighlight();" +
            "      if (window.PrivacyElementPickerBridge && typeof window.PrivacyElementPickerBridge.onElementZapped === 'function') {" +
            "        window.PrivacyElementPickerBridge.onElementZapped();" +
            "      }" +
            "      cleanup();" +
            "      return;" +
            "    }" +
            "    extractAndSendHierarchy(el);" +
            "  }" +
            "  function extractAndSendHierarchy(el) {" +
            "    var nodes = [];" +
            "    var curr = el;" +
            "    while (curr && curr !== document.body && curr !== document.documentElement && nodes.length < 5) {" +
            "      var tag = curr.tagName.toLowerCase();" +
            "      var id = curr.id ? curr.id.trim() : '';" +
            "      var classes = [];" +
            "      if (curr.classList) {" +
            "        for (var i = 0; i < curr.classList.length; i++) {" +
            "          var c = curr.classList[i];" +
            "          if (c && !c.startsWith('__ublock_')) classes.push(c);" +
            "        }" +
            "      }" +
            "      var textSnippet = (curr.innerText || curr.textContent || '').trim().substring(0, 40).replace(/\\s+/g, ' ');" +
            "      var candidateSelectors = generateCandidateSelectors(curr, tag, id, classes);" +
            "      nodes.push({" +
            "        tag: tag," +
            "        id: id," +
            "        classes: classes," +
            "        text: textSnippet," +
            "        selectors: candidateSelectors" +
            "      });" +
            "      curr = curr.parentElement;" +
            "    }" +
            "    if (window.PrivacyElementPickerBridge && typeof window.PrivacyElementPickerBridge.onElementPicked === 'function') {" +
            "      window.PrivacyElementPickerBridge.onElementPicked(JSON.stringify(nodes));" +
            "    }" +
            "  }" +
            "  function generateCandidateSelectors(node, tag, id, classes) {" +
            "    var list = [];" +
            "    if (id) {" +
            "      list.push('#' + id);" +
            "      list.push(tag + '#' + id);" +
            "    }" +
            "    if (classes.length > 0) {" +
            "      list.push('.' + classes.join('.'));" +
            "      list.push(tag + '.' + classes[0]);" +
            "      if (classes.length > 1) {" +
            "        list.push(tag + '.' + classes.join('.'));" +
            "      }" +
            "    }" +
            "    var attrs = ['data-ad', 'data-ad-slot', 'data-adunit', 'data-ad-client', 'aria-label', 'role'];" +
            "    for (var a = 0; a < attrs.length; a++) {" +
            "      var val = node.getAttribute(attrs[a]);" +
            "      if (val) {" +
            "        list.push(tag + '[' + attrs[a] + '=\"' + val + '\"]');" +
            "      }" +
            "    }" +
            "    list.push(tag);" +
            "    return list;" +
            "  }" +
            "  window.addEventListener('mousemove', onPointerMove, true);" +
            "  window.addEventListener('touchmove', onPointerMove, true);" +
            "  window.addEventListener('click', onClick, true);" +
            "  window.addEventListener('touchend', onClick, true);" +
            "  function cleanup() {" +
            "    clearHighlight();" +
            "    window.removeEventListener('mousemove', onPointerMove, true);" +
            "    window.removeEventListener('touchmove', onPointerMove, true);" +
            "    window.removeEventListener('click', onClick, true);" +
            "    window.removeEventListener('touchend', onClick, true);" +
            "    var st = document.getElementById('__ublock_picker_style');" +
            "    if (st) st.remove();" +
            "    var prev = document.getElementById('__ublock_preview_style');" +
            "    if (prev) prev.remove();" +
            "    window.__uBlockPickerActive = false;" +
            "  }" +
            "  window.__uBlockPickerCleanup = cleanup;" +
            "  window.__uBlockPickerPreview = function(selector) {" +
            "    var prev = document.getElementById('__ublock_preview_style');" +
            "    if (prev) prev.remove();" +
            "    if (!selector) return 0;" +
            "    prev = document.createElement('style');" +
            "    prev.id = '__ublock_preview_style';" +
            "    prev.textContent = selector + ' { display: none !important; }';" +
            "    (document.head || document.documentElement).appendChild(prev);" +
            "    try {" +
            "      return document.querySelectorAll(selector).length;" +
            "    } catch(e) {" +
            "      return 0;" +
            "    }" +
            "  };" +
            "  window.__uBlockPickerHighlightNode = function(depthIndex) {" +
            "    clearHighlight();" +
            "    var curr = currentTarget;" +
            "    for (var d = 0; d < depthIndex && curr && curr.parentElement; d++) {" +
            "      curr = curr.parentElement;" +
            "    }" +
            "    if (curr) {" +
            "      curr.classList.add('__ublock_highlight');" +
            "      try { curr.scrollIntoView({ behavior: 'smooth', block: 'nearest' }); } catch(e) {}" +
            "    }" +
            "  };" +
            "})();";
    }

    public static String getCleanupScript() {
        return "if (window.__uBlockPickerCleanup) { window.__uBlockPickerCleanup(); }";
    }

    public static String getPreviewScript(@NonNull String selector) {
        String clean = selector.replace("'", "\\'");
        return "if (window.__uBlockPickerPreview) { window.__uBlockPickerPreview('" + clean + "'); }";
    }

    public static String getHighlightNodeScript(int depthIndex) {
        return "if (window.__uBlockPickerHighlightNode) { window.__uBlockPickerHighlightNode(" + depthIndex + "); }";
    }
}

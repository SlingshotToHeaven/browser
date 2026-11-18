// Stock cosmetic filter: stylesheet first, bounded inline recovery
(function() {
  try {
    if (window.PrivacyAntiAdblockBridge && typeof window.PrivacyAntiAdblockBridge.isCosmeticEnabled === 'function') {
      if (!window.PrivacyAntiAdblockBridge.isCosmeticEnabled(window.location.href)) return;
    } else if (window.PrivacyAntiAdblockBridge && typeof window.PrivacyAntiAdblockBridge.isAdBlockActive === 'function') {
      if (!window.PrivacyAntiAdblockBridge.isAdBlockActive(window.location.href)) return;
    }
  } catch(e) {}
  var selectors = 
    '.advertisement, .adsbygoogle, ins.adsbygoogle, amp-ad, [id*="google_ads"], [name*="google_ads"], [id*="aswift"], [name*="aswift"], ' +
    'div[id^="div-gpt-ad"], div[id*="-gpt-ad-"], div[id*="_gpt_ad_"], ' +
    '.adHolder, .adHolder.mpu, div[id^="mpu_"], ' +
    '.dmg-ads, .dmg-ads-container, .dmg-ads-ad, .dmg-ads-label, .dmg-ads-label-container, ' +
    '.mol-ads-cmp, .mol-ads-label-container, ' +
    '.mpu_puff_wrapper, .billboard-container, .connatix-placeholder, .connatix, ' +
    '[class*="ad-banner"], [class*="ad_banner"], [id*="ad-banner"], [id*="ad_banner"], ' +
    '.sponsored-post, [data-ad-slot], [data-adunit], [data-ad-client], ' +
    '.ad-container, .ad-wrapper, .ad-box, .ad-unit, .ads-holder, .ad-placement, .ad_wrapper, .ad_container, .ad_box, ' +
    '[class*="ad-holder"], [class*="ad_holder"], [class*="ad-space"], [class*="ad-zone"], [class*="ad-slot"], [class*="ad_slot"], ' +
    'div[id*="billboard"], div[id*="leaderboard"], ' +
    '.taboola, [class*="taboola"], [id*="taboola"], .outbrain, [class*="outbrain"], [id*="outbrain"], .trc_related_container, ' +
    '.mgid, [class*="mgid"], [id*="mgid"], .revcontent, [class*="revcontent"], [id*="revcontent"], ' +
    '.criteo, [class*="criteo"], [id*="criteo"], .native-ad, .native_ad, .nativo, [class*="nativo"], ' +
    '.sticky-banner, .bottom-banner, .floating-ad, .floating-banner, .bottom-ad-bar, .sticky-ad, .popup-ad, [class*="interstitial"], ' +
    'iframe[src*="doubleclick"], iframe[src*="googlesyndication"], iframe[src*="googleads"], iframe[src*="adservice"], iframe[src*="adnxs"], ' +
    'iframe[src*="2mdn.net"], iframe[src*="outbrain"], iframe[src*="taboola"], iframe[src*="criteo"], iframe[src*="amazon-adsystem"], ' +
    'iframe[id*="google_ads"], iframe[name*="google_ads"], iframe[id*="aswift"], iframe[name*="aswift"], ' +
    'iframe[title*="advertisement" i], iframe[title*="ad frame" i], ' +
    '.video-ads, .ytp-ad-module, .ytp-ad-overlay-container, .ytp-ad-player-overlay, ' +
    'a[href*="/adclick"], a[href*="/pagead/"], a[href*="doubleclick.net"], ' +
    'a[href*="exoclick.com"], a[href*="popads.net"], a[href*="propellerads.com"], ' +
    'a[href*="adcash.com"], a[href*="adsterra.com"], a[href*="syndication.exoclick"]';
  // The old frame pass matched src, id, class, name and title case-insensitively.
  var srcTokens = 'googleads|doubleclick|googlesyndication|adservice|2mdn.net|adnxs|smartadserver|criteo|rubicon|pubmatic|amazon-adsystem|openx|taboola|outbrain|revcontent|mgid|connatix';
  var adSource = new RegExp(srcTokens.replace('.', '\\.'), 'i');
  var frameRules = [];
  ['iframe', 'embed', 'object'].forEach(function(tag) {
    function add(attribute, tokens) {
      tokens.split('|').forEach(function(token) { frameRules.push(tag + '[' + attribute + '*="' + token + '" i]'); });
    }
    add('src', srcTokens);
    ['id', 'class', 'name'].forEach(function(attribute) { add(attribute, 'google_ads|aswift|adHolder|dmg-ads|connatix'); });
    add('title', 'advertisement|ad frame');
  });
  var frameSelectors = frameRules.join(',');

  function install() {
    if (document.getElementById('__privacy_shield_css')) return;
    var root = document.head || document.documentElement;
    if (!root) return;
    var style = document.createElement('style');
    style.id = '__privacy_shield_css';
    style.textContent = selectors + ' { display: none !important; visibility: hidden !important; height: 0 !important; max-height: 0 !important; min-height: 0 !important; margin: 0 !important; padding: 0 !important; border: none !important; pointer-events: none !important; opacity: 0 !important; }'
      + frameSelectors + ' { display: none !important; width: 0 !important; height: 0 !important; visibility: hidden !important; }';
    root.appendChild(style);

    // Retain bounded inline recovery if CSS cannot win the cascade or a frame's src only matches when resolved.
    // Most matches (including later attribute changes) need no inline writes.
    var inline = new WeakSet(), checkedCss = new WeakSet();
    var attributes = style.sheet ? ['style', 'src'] :
      ['style', 'src', 'id', 'class', 'name', 'title', 'href', 'data-ad-slot', 'data-adunit', 'data-ad-client'];
    function frame(el) { return /^(IFRAME|EMBED|OBJECT)$/.test(el.tagName); }
    function important(el) { return /!\s*important/i.test(el.getAttribute('style') || ''); }
    function hide(el, properties) {
      inline.add(el);
      for (var property in properties) el.style.setProperty(property, properties[property], 'important');
    }
    var collapse = {display: 'none', height: '0px', 'min-height': '0px', 'max-height': '0px', margin: '0px', padding: '0px', border: 'none'};
    var collapseFrame = {display: 'none', width: '0px', height: '0px', visibility: 'hidden'};
    __privacyObserveElements(document.documentElement, attributes, function(el) {
      return !inline.has(el) && (!style.sheet || frame(el) || important(el));
    }, function(nodes) {
      var checks = 128, fixes = []; // bound content checks across the whole slice
      nodes.forEach(function(el) {
        if (!el.style || inline.has(el) || el === style || el.tagName === 'BODY' || el.tagName === 'HTML') return;
        var embedded = frame(el);
        var cssFrame = embedded && el.matches(frameSelectors);
        var resolvedOnly = embedded && !cssFrame && adSource.test(el.src || '');
        // amp-ad is the only stock selector that can match an element with no attributes.
        var ordinary = !cssFrame && !resolvedOnly && (el.tagName === 'AMP-AD' || el.hasAttributes())
            && !/^(ARTICLE|MAIN|SECTION)$/.test(el.tagName) && el.matches(selectors);
        if (!cssFrame && !resolvedOnly && !ordinary) return;
        var override = !style.sheet || important(el) || resolvedOnly;
        if (!override) {
          if (checkedCss.has(el)) return;
          checkedCss.add(el);
          // A more specific author !important may beat the injected stylesheet. Only check matches;
          // never measure geometry or re-probe unchanged content on each mutation.
          override = window.getComputedStyle(el).display !== 'none';
        }
        if (!override) return;
        if (cssFrame || resolvedOnly) {
          fixes.push([el, collapseFrame]);
          return;
        }
        // Keep the old inline-prune content guard. If we cannot verify a large subtree, leave it alone.
        var walk = document.createTreeWalker(el, 1);
        while (checks > 0) {
          checks--;
          var child = walk.nextNode();
          if (!child) { fixes.push([el, collapse]); break; }
          if (child.matches('p.mol-para-with-font, article, main')) break;
        }
      });
      fixes.forEach(function(fix) { hide(fix[0], fix[1]); });
    });
  }

  if (document.documentElement) install();
  else document.addEventListener('DOMContentLoaded', install, {once: true});
})();

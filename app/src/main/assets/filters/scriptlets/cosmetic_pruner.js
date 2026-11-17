// Cosmetic elements filter and pruner scriptlet
(function() {
  try {
    if (window.PrivacyAntiAdblockBridge && typeof window.PrivacyAntiAdblockBridge.isCosmeticEnabled === 'function') {
      if (!window.PrivacyAntiAdblockBridge.isCosmeticEnabled(window.location.href)) return;
    } else if (window.PrivacyAntiAdblockBridge && typeof window.PrivacyAntiAdblockBridge.isAdBlockActive === 'function') {
      if (!window.PrivacyAntiAdblockBridge.isAdBlockActive(window.location.href)) return;
    }
  } catch(e) {}
  if (document.getElementById('__privacy_shield_css')) return;
  var style = document.createElement('style');
  style.id = '__privacy_shield_css';
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
  style.textContent = selectors + ' { display: none !important; visibility: hidden !important; height: 0 !important; max-height: 0 !important; min-height: 0 !important; margin: 0 !important; padding: 0 !important; border: none !important; pointer-events: none !important; opacity: 0 !important; }';
  var target = document.head || document.documentElement;
  if (target) { target.appendChild(style); }
  else { document.addEventListener('DOMContentLoaded', function() { (document.head || document.documentElement).appendChild(style); }); }

  function pruneAds(root) {
    try {
      var doc = root || document;
      var nodes = doc.querySelectorAll(selectors);
      for (var i = 0; i < nodes.length; i++) {
        var node = nodes[i];
        if (node && node.id !== '__privacy_shield_css' && node.tagName !== 'BODY' && node.tagName !== 'HTML') {
          if (node.tagName === 'ARTICLE' || node.tagName === 'MAIN' || node.tagName === 'SECTION') continue;
          if (node.querySelector && node.querySelector('p.mol-para-with-font, article, main')) continue;
          node.style.setProperty('display', 'none', 'important');
          node.style.setProperty('height', '0px', 'important');
          node.style.setProperty('min-height', '0px', 'important');
          node.style.setProperty('max-height', '0px', 'important');
          node.style.setProperty('margin', '0px', 'important');
          node.style.setProperty('padding', '0px', 'important');
          node.style.setProperty('border', 'none', 'important');
        }
      }
      var iframes = doc.querySelectorAll('iframe, embed, object');
      for (var j = 0; j < iframes.length; j++) {
        var f = iframes[j];
        var s = f.src || f.getAttribute('src') || '';
        var idClass = (f.id || '') + ' ' + (f.className || '') + ' ' + (f.name || '');
        if (/googleads|doubleclick|googlesyndication|adservice|2mdn\.net|adnxs|smartadserver|criteo|rubicon|pubmatic|amazon-adsystem|openx|taboola|outbrain|revcontent|mgid|connatix/i.test(s)
            || /google_ads|aswift|adHolder|dmg-ads|connatix/i.test(idClass)
            || /advertisement|ad frame/i.test(f.getAttribute('title') || '')) {
          f.style.setProperty('display', 'none', 'important');
          f.style.setProperty('width', '0px', 'important');
          f.style.setProperty('height', '0px', 'important');
          f.style.setProperty('visibility', 'hidden', 'important');
        }
      }
    } catch(e) {}
  }

  pruneAds(document);
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function() { pruneAds(document); });
  } else {
    pruneAds(document);
  }
  setTimeout(function() { pruneAds(document); }, 500);
  setTimeout(function() { pruneAds(document); }, 1500);
  setTimeout(function() { pruneAds(document); }, 3000);

  if (window.MutationObserver && !window.__privacy_ad_observer_active) {
    window.__privacy_ad_observer_active = true;
    var pending = false;
    var observer = new window.MutationObserver(function() {
      if (pending) return;
      pending = true;
      (window.requestAnimationFrame || window.setTimeout)(function() {
        pending = false;
        pruneAds(document);
      }, 32);
    });
    observer.observe(document.documentElement || document, { childList: true, subtree: true });
  }
})();

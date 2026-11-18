// Popup and clickjack defuser scriptlet
(function() {
  try {
    if (window.PrivacyAntiAdblockBridge && typeof window.PrivacyAntiAdblockBridge.isAdBlockActive === 'function') {
      if (!window.PrivacyAntiAdblockBridge.isAdBlockActive(window.location.href)) return;
    }
  } catch(e) {}
  if (window.__ublock_popup_click_defused) return;
  window.__ublock_popup_click_defused = true;

  function report(name, detail) {
    try {
      if (window.PrivacyAntiAdblockBridge && typeof window.PrivacyAntiAdblockBridge.reportAntiAdblockDefeated === 'function') {
        window.PrivacyAntiAdblockBridge.reportAntiAdblockDefeated(name, detail);
      }
    } catch(e) {}
  }

  var adSignatures = [
    'popads', 'propellerads', 'adcash', 'exoclick', 'clickadu', 'revenuehits',
    'popcash', 'zeropark', 'richads', 'trafficstars', 'juicyads', 'adsterra',
    'syndication.exoclick', 'servedby.', 'creative.ak.fbcdn', 'doubleclick',
    'googleadservices', 'googlesyndication', 'pagead', 'taboola', 'outbrain',
    'mgid', 'revcontent', 'criteo', 'infolinks', 'bidvertiser', 'ad-maven',
    'yllix', 'hilltopads', 'clksite', 'onclickmega', 'directrev', 'adk2x',
    'adxcore', 'vidoomy', 'coinhive', 'monetag', 'adbutler', 'galaksion'
  ];

  function isAdUrl(u) {
    if (!u || typeof u !== 'string') return false;
    var lower = u.toLowerCase();
    for (var i = 0; i < adSignatures.length; i++) {
      if (lower.indexOf(adSignatures[i]) !== -1) return true;
    }
    return false;
  }

  try {
    var origOpen = window.open;
    window.open = function(url, target, features) {
      if (isAdUrl(url)) {
        report('Popup Blocker', 'Neutralized window.open ad popup: ' + (url || 'blank'));
        return { closed: true, close: function(){}, focus: function(){}, postMessage: function(){} };
      }
      return origOpen.apply(this, arguments);
    };
  } catch(e) {}

  function eligible(el) {
    if (!/^(DIV|A|SECTION|SPAN)$/.test(el.tagName) || (el.id || '').startsWith('__ublock_')) return false;
    var identity = (el.id || '') + ' ' + (el.getAttribute('class') || '') + ' ' +
      (el.getAttribute('role') || '') + ' ' + (el.getAttribute('aria-modal') || '');
    return !/cookie|consent|modal|dialog|privacy/i.test(identity);
  }

  function inspectOverlays(nodes) {
    var remove = [], vw, vh;
    nodes.forEach(function(el) {
      if (!eligible(el)) return;
      var style = window.getComputedStyle(el);
      if ((style.position !== 'fixed' && style.position !== 'absolute') || !(parseInt(style.zIndex, 10) >= 1000)) return;
      if (!(parseFloat(style.opacity) <= 0.05) && style.backgroundColor !== 'transparent'
          && style.backgroundColor !== 'rgba(0, 0, 0, 0)') return;
      if (vw === undefined) {
        vw = window.innerWidth || document.documentElement.clientWidth;
        vh = window.innerHeight || document.documentElement.clientHeight;
      }
      var rect = el.getBoundingClientRect();
      if (rect.width >= vw * 0.5 && rect.height >= vh * 0.5) remove.push(el);
    });
    // Batch reads before DOM writes so removing a match does not force layout for the next probe.
    remove.forEach(function(el) {
      if (!el.isConnected) return;
      el.remove();
      report('Click-Jack Defuser', 'Removed invisible full-screen ad overlay');
    });
  }

  try {
    document.addEventListener('click', function(e) {
      var a = e.target && e.target.closest ? e.target.closest('a') : null;
      if (a && a.href) {
        if (isAdUrl(a.href)) {
          e.preventDefault();
          e.stopPropagation();
          e.stopImmediatePropagation();
          report('Ad Link Shield', 'Blocked tap on ad redirect link: ' + a.href);
        }
      }
    }, true);
  } catch(e) {}

  function observeOverlays() {
    __privacyObserveElements(document.documentElement, ['class', 'id', 'style', 'role', 'aria-modal'],
      eligible, inspectOverlays);
  }
  if (document.documentElement) observeOverlays();
  else document.addEventListener('DOMContentLoaded', observeOverlays, {once: true});
})();

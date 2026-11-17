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

  var lastUserGestureTime = 0;
  function noteUserGesture() {
    lastUserGestureTime = Date.now();
  }

  window.addEventListener('click', noteUserGesture, true);
  window.addEventListener('keydown', noteUserGesture, true);
  window.addEventListener('touchend', noteUserGesture, true);

  try {
    var origOpen = window.open;
    window.open = function(url, target, features) {
      var isAd = isAdUrl(url);
      var recentGesture = (Date.now() - lastUserGestureTime) < 1500;
      if (isAd || (!recentGesture && isAdUrl(url))) {
        report('Popup Blocker', 'Neutralized window.open ad popup: ' + (url || 'blank'));
        return { closed: true, close: function(){}, focus: function(){}, postMessage: function(){} };
      }
      return origOpen.apply(this, arguments);
    };
  } catch(e) {}

  function defuseClickjackOverlays() {
    try {
      var all = document.querySelectorAll('div, a, section, span');
      var vw = window.innerWidth || document.documentElement.clientWidth;
      var vh = window.innerHeight || document.documentElement.clientHeight;
      for (var i = 0; i < all.length; i++) {
        var el = all[i];
        if (el.id && el.id.startsWith('__ublock_')) continue;
        var idClass = ((el.id || '') + ' ' + (el.className || '') + ' ' + (el.getAttribute('role') || '') + ' ' + (el.getAttribute('aria-modal') || '')).toLowerCase();
        if (idClass.indexOf('cookie') !== -1 || idClass.indexOf('consent') !== -1 || idClass.indexOf('modal') !== -1 || idClass.indexOf('dialog') !== -1 || idClass.indexOf('privacy') !== -1) continue;
        var style = window.getComputedStyle(el);
        if (style.position === 'fixed' || style.position === 'absolute') {
          var zIndex = parseInt(style.zIndex, 10);
          var opacity = parseFloat(style.opacity);
          var rect = el.getBoundingClientRect();
          if (zIndex >= 1000 && (opacity <= 0.05 || style.backgroundColor === 'transparent' || style.backgroundColor === 'rgba(0, 0, 0, 0)')) {
            if (rect.width >= vw * 0.5 && rect.height >= vh * 0.5) {
              el.remove();
              report('Click-Jack Defuser', 'Removed invisible full-screen ad overlay');
            }
          }
        }
      }
    } catch(e) {}
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

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', defuseClickjackOverlays);
  } else {
    defuseClickjackOverlays();
  }
  setTimeout(defuseClickjackOverlays, 1000);
  setTimeout(defuseClickjackOverlays, 2500);
})();

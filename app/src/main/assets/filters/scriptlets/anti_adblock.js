// Anti-adblock defuser scriptlet
// Neutralizes FuckAdBlock, BlockAdBlock, bait element probes, and anti-adblock modals.
(function() {
  try {
    if (window.PrivacyAntiAdblockBridge && typeof window.PrivacyAntiAdblockBridge.isAdBlockActive === 'function') {
      if (!window.PrivacyAntiAdblockBridge.isAdBlockActive(window.location.href)) return;
    }
  } catch(e) {}
  if (window.__privacy_antiadblock_defused) return;
  window.__privacy_antiadblock_defused = true;

  function report(name, detail) {
    try {
      if (window.PrivacyAntiAdblockBridge && typeof window.PrivacyAntiAdblockBridge.reportAntiAdblockDefeated === 'function') {
        window.PrivacyAntiAdblockBridge.reportAntiAdblockDefeated(name, detail);
      }
    } catch(e) {}
  }

  try {
    function createDummyFAB() {
      var def = function() { return def; };
      def.on = function(detected, fn) {
        if (!detected && typeof fn === 'function') {
          try { setTimeout(fn, 1); } catch(e) {}
          report('FuckAdBlock Hook', 'Suppressed adblock detection callback and fired clean branch');
        }
        return def;
      };
      def.onDetected = function(fn) { return def; };
      def.onNotDetected = function(fn) {
        if (typeof fn === 'function') {
          try { setTimeout(fn, 1); } catch(e) {}
          report('BlockAdBlock Hook', 'Fired clean non-detected callback');
        }
        return def;
      };
      def.check = function() { return false; };
      def.clearEvent = function() {};
      def.setOption = function() {};
      return def;
    };
    var dummy = createDummyFAB();
    window.FuckAdBlock = dummy;
    window.fuckAdBlock = dummy;
    window.BlockAdBlock = dummy;
    window.blockAdBlock = dummy;
    window.Sniffer = dummy;
  } catch(e) {}

  window.canRunAds = true;
  window.isAdBlockActive = false;
  window.adblock = false;
  window.adblocker = false;
  window.google_ad_status = 1;
  window.isAdblock = false;
  window.adBlocker = false;
  window.adBlockDetected = false;

  try {
    window.ga = window.ga || function(){};
    window.gtag = window.gtag || function(){};
    window.dataLayer = window.dataLayer || [];
    window.fbq = window.fbq || function(){};
    window.twq = window.twq || function(){};
    window.ttq = window.ttq || function(){};
    window.snaptr = window.snaptr || function(){};
  } catch(e) {}

  try {
    if (!window.adsbygoogle) window.adsbygoogle = [];
    window.adsbygoogle.push = function(arg) {
      report('Google AdSense Probe', 'Intercepted adsbygoogle.push probe and supplied clean stub');
      return 1;
    };
    window.adsbygoogle.loaded = true;
  } catch(e) {}

  try {
    var baitRegex = /(ad[_-]?banner|adsbox|ad[_-]?holder|ad[_-]?sense|ad[_-]?frame|carbonads|pub_300x250)/i;
    var origOffsetHeight = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight');
    if (origOffsetHeight && origOffsetHeight.get) {
      Object.defineProperty(HTMLElement.prototype, 'offsetHeight', {
        get: function() {
          var idOrClass = (this.id || '') + ' ' + (this.className || '');
          if (baitRegex.test(idOrClass)) {
            report('Bait Element Probe', 'Spoofed non-zero offsetHeight for: ' + (this.id || this.className));
            return 250;
          }
          return origOffsetHeight.get.call(this);
        },
        configurable: true
      });
    }
  } catch(e) {}

  function neutralizeModals() {
    try {
      var sel = '[id*="adblock-modal"], [class*="adblock-modal"], [id*="adblock-notice"], [class*="adblock-notice"], .adblock-overlay, #ab-message, .sp-message-container, div[id*="adblock_detected"], div[class*="adblock_detected"], .ad-block-warning, #adblock-popup, .anti-adblock, [class*="fc-ab-root"]';
      var list = document.querySelectorAll(sel);
      if (list && list.length > 0) {
        for (var i = 0; i < list.length; i++) {
          list[i].style.setProperty('display', 'none', 'important');
        }
        if (document.body) {
          document.body.style.setProperty('overflow', 'auto', 'important');
          document.body.style.setProperty('position', 'static', 'important');
        }
        if (document.documentElement) {
          document.documentElement.style.setProperty('overflow', 'auto', 'important');
        }
        report('Anti-Adblock Modal Barrier', 'Removed blocking overlay modal and restored page scrolling');
      }
    } catch(e) {}
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', neutralizeModals);
  } else {
    neutralizeModals();
  }
  setTimeout(neutralizeModals, 1000);
  setTimeout(neutralizeModals, 2500);
})();

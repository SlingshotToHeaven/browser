// uBlock Origin-grade YouTube ad & anti-adblock defuser scriptlet
(function() {
  try {
    if (window.PrivacyAntiAdblockBridge && typeof window.PrivacyAntiAdblockBridge.isAdBlockActive === 'function') {
      if (!window.PrivacyAntiAdblockBridge.isAdBlockActive(window.location.href)) return;
    }
  } catch(e) {}

  if (window.__privacy_ubo_yt_defuser_v2) return;
  window.__privacy_ubo_yt_defuser_v2 = true;

  // 1. Recursive cleaner for YouTube player responses (matches uBlock Origin json-prune)
  function cleanPlayerResponse(obj) {
    if (!obj || typeof obj !== 'object') return obj;
    try {
      if (obj.adPlacements) delete obj.adPlacements;
      if (obj.adSlots) delete obj.adSlots;
      if (obj.playerAds) delete obj.playerAds;
      if (obj.adBreakHeartbeatParams) delete obj.adBreakHeartbeatParams;
      if (obj.legacyImportant) delete obj.legacyImportant;

      if (obj.playerResponse && typeof obj.playerResponse === 'object') {
        cleanPlayerResponse(obj.playerResponse);
      }
      if (Array.isArray(obj)) {
        for (var i = 0; i < obj.length; i++) {
          cleanPlayerResponse(obj[i]);
        }
      }
      if (obj.playbackTracking && typeof obj.playbackTracking === 'object') {
        if (obj.playbackTracking.videostatsPlaybackUrl) delete obj.playbackTracking.videostatsPlaybackUrl;
        if (obj.playbackTracking.videostatsDelayplayUrl) delete obj.playbackTracking.videostatsDelayplayUrl;
        if (obj.playbackTracking.videostatsWatchtimeUrl) delete obj.playbackTracking.videostatsWatchtimeUrl;
      }
      if (obj.endpoint && obj.endpoint.watchEndpoint && obj.endpoint.watchEndpoint.unserializedPrefetchData) {
        cleanPlayerResponse(obj.endpoint.watchEndpoint.unserializedPrefetchData);
      }
    } catch (e) {}
    return obj;
  }

  // 2. Intercept window.ytInitialPlayerResponse
  var _ytInitialPlayerResponse = window.ytInitialPlayerResponse ? cleanPlayerResponse(window.ytInitialPlayerResponse) : undefined;
  try {
    Object.defineProperty(window, 'ytInitialPlayerResponse', {
      get: function() { return _ytInitialPlayerResponse; },
      set: function(val) { _ytInitialPlayerResponse = cleanPlayerResponse(val); },
      configurable: true,
      enumerable: true
    });
  } catch(e) {
    if (window.ytInitialPlayerResponse) cleanPlayerResponse(window.ytInitialPlayerResponse);
  }

  // 3. Intercept window.ytInitialData for feed ads & sponsored videos
  var _ytInitialData = window.ytInitialData ? cleanPlayerResponse(window.ytInitialData) : undefined;
  try {
    Object.defineProperty(window, 'ytInitialData', {
      get: function() { return _ytInitialData; },
      set: function(val) { _ytInitialData = cleanPlayerResponse(val); },
      configurable: true,
      enumerable: true
    });
  } catch(e) {}

  // 4. Hook window.fetch for /youtubei/v1/player and other watch endpoints
  if (typeof window.fetch === 'function') {
    var origFetch = window.fetch;
    window.fetch = function(input, init) {
      var url = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
      var isPlayerEndpoint = url.indexOf('/player') !== -1 ||
                             url.indexOf('/get_watch') !== -1 ||
                             url.indexOf('/resolve_url') !== -1 ||
                             url.indexOf('/playlist') !== -1;
      var promise = origFetch.apply(this, arguments);
      if (!isPlayerEndpoint) return promise;

      return promise.then(function(res) {
        try {
          var clone = res.clone();
          return clone.json().then(function(data) {
            cleanPlayerResponse(data);
            var cleanJson = JSON.stringify(data);
            var newHeaders = new Headers(res.headers);
            return new Response(cleanJson, {
              status: res.status,
              statusText: res.statusText,
              headers: newHeaders
            });
          }).catch(function() {
            return res;
          });
        } catch(err) {
          return res;
        }
      });
    };
  }

  // 5. Hook window.XMLHttpRequest for XHR player responses
  if (typeof window.XMLHttpRequest === 'function') {
    var origOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(method, url) {
      this.__ytUrl = url || '';
      return origOpen.apply(this, arguments);
    };

    var descResponseText = Object.getOwnPropertyDescriptor(XMLHttpRequest.prototype, 'responseText');
    var descResponse = Object.getOwnPropertyDescriptor(XMLHttpRequest.prototype, 'response');

    if (descResponseText && descResponseText.get) {
      Object.defineProperty(XMLHttpRequest.prototype, 'responseText', {
        get: function() {
          var text = descResponseText.get.apply(this);
          if (this.__ytUrl && (this.__ytUrl.indexOf('/player') !== -1 || this.__ytUrl.indexOf('/get_watch') !== -1)) {
            try {
              var parsed = JSON.parse(text);
              cleanPlayerResponse(parsed);
              return JSON.stringify(parsed);
            } catch(e) {}
          }
          return text;
        },
        configurable: true,
        enumerable: true
      });
    }

    if (descResponse && descResponse.get) {
      Object.defineProperty(XMLHttpRequest.prototype, 'response', {
        get: function() {
          var res = descResponse.get.apply(this);
          if (this.__ytUrl && (this.__ytUrl.indexOf('/player') !== -1 || this.__ytUrl.indexOf('/get_watch') !== -1)) {
            if (typeof res === 'string') {
              try {
                var parsed = JSON.parse(res);
                cleanPlayerResponse(parsed);
                return JSON.stringify(parsed);
              } catch(e) {}
            } else if (res && typeof res === 'object') {
              cleanPlayerResponse(res);
            }
          }
          return res;
        },
        configurable: true,
        enumerable: true
      });
    }
  }

  // 6. Hook JSON.parse as universal safety net
  try {
    var origJsonParse = JSON.parse;
    JSON.parse = function() {
      var res = origJsonParse.apply(this, arguments);
      if (res && typeof res === 'object') {
        if (res.adPlacements || res.playerAds || res.adSlots || (res.playerResponse && (res.playerResponse.adPlacements || res.playerResponse.adSlots))) {
          cleanPlayerResponse(res);
        }
      }
      return res;
    };
  } catch(e) {}

  // 7. Video Ad Skipping & Anti-Adblock Overlay Nullifier
  function defuseYouTubeAds() {
    try {
      var video = document.querySelector('video');
      var adShowing = document.querySelector('.ad-showing, .ad-interrupting, .video-ads, [class*="ytp-ad-module"]');

      if (adShowing && video) {
        video.muted = true;
        video.playbackRate = 16.0;
        if (isFinite(video.duration) && video.duration > 0) {
          video.currentTime = video.duration + 1;
        }
      }

      // Auto-click any skip button
      var skipBtns = document.querySelectorAll(
        'button.ytp-ad-skip-button, button.ytp-ad-skip-button-modern, .ytp-skip-ad-button, ' +
        '.videoAdUiSkipButton, .ytp-ad-overlay-close-button, .ytp-ad-skip-button-container button, ' +
        '.ytm-ad-badge, button[class*="skip"]'
      );
      for (var i = 0; i < skipBtns.length; i++) {
        skipBtns[i].click();
      }

      // Remove anti-adblock warning modals and restore video playback
      var enforcementDialog = document.querySelector(
        'ytd-enforcement-message-view-model, ' +
        'tp-yt-paper-dialog:has(ytd-enforcement-message-view-model), ' +
        'yt-playability-error-supported-renderers#error-screen'
      );
      if (enforcementDialog) {
        enforcementDialog.remove();
        document.body.style.overflow = 'auto';
        if (video && video.paused) {
          video.play();
        }
      }
    } catch(e) {}
  }

  // 8. MutationObserver for instant reaction to ad appearances
  try {
    var obs = new MutationObserver(function() {
      defuseYouTubeAds();
    });
    obs.observe(document.documentElement, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['class']
    });
  } catch(e) {}
  setInterval(defuseYouTubeAds, 200);

  // 9. Instant Cosmetic CSS for YouTube ad slots and promos
  try {
    if (!document.getElementById('__privacy_yt_css')) {
      var style = document.createElement('style');
      style.id = '__privacy_yt_css';
      style.textContent = 
        'ytd-ad-slot-renderer, ytd-in-feed-ad-layout-renderer, ytd-banner-promo-renderer, ' +
        'ytm-promoted-sparkles-web-renderer, ytm-companion-ad-renderer, ytm-promoted-video-renderer, ' +
        'ytd-promoted-video-renderer, ytd-display-ad-renderer, ytd-statement-banner-renderer, ' +
        'ytd-player-legacy-desktop-watch-ads-renderer, .sparkles-light-cta, #player-ads, ' +
        'ytd-ad-engagement-panel-renderer, ytd-enforcement-message-view-model, ' +
        'tp-yt-paper-dialog:has(ytd-enforcement-message-view-model), ' +
        'yt-playability-error-supported-renderers#error-screen, ' +
        '.ytp-ad-overlay-container, .ytp-ad-module, ytm-ad-slot-renderer, ' +
        '#masthead-ad, ytm-companion-ad-renderer, ' +
        '#contents > ytd-rich-item-renderer:has(> ytd-ad-slot-renderer), ' +
        '#shorts-inner-container > .ytd-shorts:has(> .ytd-reel-video-renderer > ytd-ad-slot-renderer) ' +
        '{ display: none !important; height: 0 !important; min-height: 0 !important; visibility: hidden !important; }';
      (document.head || document.documentElement).appendChild(style);
    }
  } catch(e) {}
})();

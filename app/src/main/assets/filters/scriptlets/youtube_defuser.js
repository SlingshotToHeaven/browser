// YouTube player scriptlet. Keep this check even when the provider also limits origins.
(function () {
  if (!['https://youtube.com', 'https://www.youtube.com', 'https://m.youtube.com',
        'https://www.youtube-nocookie.com', 'https://youtube-nocookie.com'].includes(location.origin)) return;
  const page = new URL(location.href);
  const child = window.top !== window;
  const inScope = () => !child || location.pathname.startsWith('/embed/');
  if (page.username || page.password || !inScope() || window.__privacy_ubo_yt_defuser_v3) return;
  window.__privacy_ubo_yt_defuser_v3 = true;

  function playerRequest(input) {
    if (!input || !inScope()) return false;
    try {
      const url = new URL(input, location.href);
      return !url.username && !url.password && url.origin === location.origin
          && (url.pathname === '/youtubei/v1/player' || url.pathname === '/get_watch');
    } catch (_) { return false; }
  }

  function cleanPlayerResponse(value, depth = 0, budget = { left: 256 }) {
    if (!inScope() || !value || typeof value !== 'object' || depth >= 6 || budget.left-- <= 0) return value;
    try {
      delete value.adPlacements;
      delete value.adSlots;
      delete value.playerAds;
      delete value.adBreakHeartbeatParams;
      if (value.playerResponse) cleanPlayerResponse(value.playerResponse, depth + 1, budget);
      if (Array.isArray(value)) {
        for (let i = 0; i < Math.min(value.length, 128) && budget.left > 0; i++) {
          cleanPlayerResponse(value[i], depth + 1, budget);
        }
      }
    } catch (_) { /* Leave values we cannot modify alone. */ }
    return value;
  }

  try {
    let initial = cleanPlayerResponse(window.ytInitialPlayerResponse);
    Object.defineProperty(window, 'ytInitialPlayerResponse', {
      get: () => initial,
      set: value => { initial = cleanPlayerResponse(value); },
      configurable: true,
      enumerable: true
    });
  } catch (_) { /* A non-configurable property still keeps its original value. */ }

  if (typeof window.fetch === 'function') {
    const fetch = window.fetch;
    window.fetch = function (input) {
      const result = fetch.apply(this, arguments);
      let matches;
      try { matches = playerRequest(typeof input === 'string' || input instanceof URL ? input : input && input.url); }
      catch (_) { return result; }
      if (!matches) return result;
      return result.then(response => {
        // Check the final response URL too: a redirect does not transfer permission to rewrite.
        if (response.ok && playerRequest(response.url)) {
          try {
            // Preserve the response's identity, URL, headers, status and stream. Do not clone,
            // pre-read the body, or patch Response.prototype or JSON.parse for the whole page.
            const json = response.json;
            Object.defineProperty(response, 'json', {
              configurable: true, writable: true,
              value() { return json.apply(this, arguments).then(data => cleanPlayerResponse(data)); }
            });
          } catch (_) { /* Frozen response, etc. */ }
        }
        return response;
      });
    };
  }

  if (typeof window.XMLHttpRequest === 'function') {
    const proto = window.XMLHttpRequest.prototype;
    const open = proto.open;
    const requests = new WeakMap();
    proto.open = function (method, url) {
      requests.delete(this);
      const result = open.apply(this, arguments);
      if (playerRequest(url)) requests.set(this, {});
      return result;
    };

    function completedPlayerRequest(xhr) {
      return requests.get(xhr) && xhr.readyState === 4 && xhr.status >= 200 && xhr.status < 300
          && playerRequest(xhr.responseURL);
    }

    for (const property of ['response', 'responseText']) {
      const descriptor = Object.getOwnPropertyDescriptor(proto, property);
      if (!descriptor || !descriptor.configurable || !descriptor.get) continue;
      Object.defineProperty(proto, property, {
        ...descriptor,
        get() {
          const original = descriptor.get.call(this);
          if (!completedPlayerRequest(this)) return original;
          const state = requests.get(this);
          if (typeof original === 'string' && original.length <= 2 * 1024 * 1024) {
            if (state.text === original) return state.cleaned;
            let cleaned = original;
            try { cleaned = JSON.stringify(cleanPlayerResponse(JSON.parse(original))); } catch (_) {}
            state.text = original;
            state.cleaned = cleaned;
            return cleaned;
          }
          if (this.responseType === 'json' && original !== state.json) {
            state.json = cleanPlayerResponse(original);
          }
          return original;
        }
      });
    }
  }

  // Follow the player, not the document tree. One pending frame per player change, no polling.
  let player, observer, frame = null, oldPlayback;
  function restorePlayback() {
    if (!oldPlayback) return;
    const { video, muted, rate } = oldPlayback;
    oldPlayback = undefined;
    video.muted = muted;
    video.playbackRate = rate;
  }
  function updatePlayer() {
    frame = null;
    const video = player && player.querySelector('video');
    if (!inScope() || !video || (!player.classList.contains('ad-showing') && !player.classList.contains('ad-interrupting'))) {
      restorePlayback();
      return;
    }
    if (!oldPlayback || oldPlayback.video !== video) {
      restorePlayback();
      oldPlayback = { video, muted: video.muted, rate: video.playbackRate };
    }
    video.muted = true;
    try {
      video.playbackRate = 16;
      if (Number.isFinite(video.duration) && video.duration > 0) video.currentTime = video.duration;
    } catch (_) { /* Some media elements are not seekable. */ }
    const skip = player.querySelector('button.ytp-ad-skip-button, button.ytp-ad-skip-button-modern, .ytp-skip-ad-button');
    if (skip) skip.click();
  }
  function schedule() {
    if (frame === null) frame = requestAnimationFrame(updatePlayer);
  }
  function detachPlayer() {
    if (observer) observer.disconnect();
    observer = null;
    if (frame !== null) cancelAnimationFrame(frame);
    frame = null;
    restorePlayback();
    player = null;
  }
  function ensurePlayer() {
    const next = inScope() && (document.getElementById('movie_player') || document.querySelector('.html5-video-player'));
    if (next !== player) {
      detachPlayer();
      player = next || null;
      if (player && typeof MutationObserver === 'function') {
        observer = new MutationObserver(schedule);
        observer.observe(player, { attributes: true, attributeFilter: ['class'], childList: true });
      }
    }
    if (player) schedule();
  }

  const cosmetic = typeof privacyBrowserYouTubeCosmetic === 'undefined' || privacyBrowserYouTubeCosmetic;
  function installStyle() {
    if (!cosmetic || !inScope() || document.getElementById('__privacy_yt_css')) return;
    const root = document.head || document.documentElement;
    if (!root) return;
    const style = document.createElement('style');
    style.id = '__privacy_yt_css';
    style.textContent = 'ytd-ad-slot-renderer, ytd-in-feed-ad-layout-renderer, ytd-banner-promo-renderer, '
        + 'ytm-promoted-sparkles-web-renderer, ytm-companion-ad-renderer, ytm-promoted-video-renderer, '
        + 'ytd-promoted-video-renderer, ytd-display-ad-renderer, ytd-statement-banner-renderer, '
        + 'ytd-player-legacy-desktop-watch-ads-renderer, #player-ads, #masthead-ad, .ytp-ad-overlay-container, '
        + '.ytp-ad-module, ytm-ad-slot-renderer '
        + '{ display: none !important; height: 0 !important; min-height: 0 !important; visibility: hidden !important; }';
    root.appendChild(style);
  }
  function onPageReady() {
    installStyle();
    ensurePlayer();
  }
  window.addEventListener('pagehide', detachPlayer);
  window.addEventListener('pageshow', onPageReady);
  document.addEventListener('DOMContentLoaded', onPageReady, { once: true });
  document.addEventListener('yt-navigate-finish', onPageReady);
  document.addEventListener('loadedmetadata', event => {
    if (event.target.tagName === 'VIDEO') ensurePlayer();
  }, true);
  onPageReady();
})();

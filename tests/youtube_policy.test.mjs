import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const code = readFileSync(new URL('../app/src/main/assets/filters/scriptlets/youtube_defuser.js', import.meta.url), 'utf8');
const playerUrl = 'https://www.youtube.com/youtubei/v1/player?fixture=1';
const responseData = { adSlots: [1], streamingData: { fixture: true } };
const cleanData = { streamingData: { fixture: true } };

function load(url, { subframe = false, player = null, hasRoot = true, cosmetic = true, respond } = {}) {
  const stats = { intervals: 0, observed: [], events: new Map(), frames: new Map(), disconnects: 0, styles: [] };
  let nextFrame = 0;
  const root = { appendChild: style => stats.styles.push(style) };
  const document = {
    getElementById: id => id === 'movie_player' ? player : stats.styles.find(style => style.id === id),
    querySelector: () => null,
    addEventListener: (event, callback) => stats.events.set(event, callback),
    createElement: () => ({}),
    head: hasRoot ? root : null
  };
  const location = new URL(url);
  let lastFetch;
  const fetch = (input) => (lastFetch = Promise.resolve(respond ? respond(input) : input));
  class FixtureXhr {
    open() { this.readyState = 1; }
    get response() { return this.data; }
    get responseText() { return this.data; }
    finish(data, responseURL, { status = 200, responseType = '' } = {}) {
      Object.assign(this, { data, responseURL, status, responseType, readyState: 4 });
    }
  }
  const window = { fetch, XMLHttpRequest: FixtureXhr, addEventListener: (event, callback) => stats.events.set(event, callback) };
  window.top = subframe ? {} : window;
  class MutationObserver {
    constructor(callback) { this.callback = callback; }
    observe(node, options) { stats.observed.push({ node, options, notify: this.callback }); }
    disconnect() { stats.disconnects++; }
  }
  const context = vm.createContext({ window, location, document, URL, MutationObserver, JSON,
    privacyBrowserYouTubeCosmetic: cosmetic, setInterval: () => ++stats.intervals,
    requestAnimationFrame: fn => { stats.frames.set(++nextFrame, fn); return nextFrame; },
    cancelAnimationFrame: id => stats.frames.delete(id) });
  const originalOpen = FixtureXhr.prototype.open;
  const oldParse = JSON.parse;
  vm.runInContext(code, context);
  return { window, stats, fetch, context, oldParse, originalOpen, location, FixtureXhr,
    setPlayer: value => { player = value; }, attachRoot: () => { document.head = root; }, lastFetch: () => lastFetch,
    flushFrame: () => { for (const [id, fn] of stats.frames) { stats.frames.delete(id); fn(); } } };
}

test('off-site pages, aliases, credentials, wrong ports and unsupported child pages install nothing', () => {
  for (const [url, subframe] of [
    ['https://example.invalid/youtube.com/watch', false],
    ['https://www.youtube.com.example.invalid/', false],
    ['https://user@www.youtube.com/watch', false],
    ['https://www.youtube.com:8443/watch', false],
    ['http://youtube.com/watch', false],
    ['https://youtu.be/watch', false],
    ['https://www.youtube.com/watch', true]
  ]) {
    const { window, stats, fetch, oldParse, originalOpen, FixtureXhr } = load(url, { subframe });
    assert.equal(window.fetch, fetch);
    assert.equal(FixtureXhr.prototype.open, originalOpen);
    assert.equal(window.__privacy_ubo_yt_defuser_v3, undefined);
    assert.equal(stats.intervals, 0);
    assert.equal(stats.observed.length, 0);
    assert.equal(stats.events.size, 0);
    assert.equal(JSON.parse, oldParse);
  }
});

test('embed only observes its player, follows replacement, and cancels work on pagehide', () => {
  const video = { muted: false, playbackRate: 1.25, duration: 2, currentTime: 0 };
  let inAd = true;
  const player = { querySelector: selector => selector === 'video' ? video : null, classList: { contains: () => inAd } };
  const view = load('https://www.youtube-nocookie.com/embed/demo', { subframe: true, player });
  const { window, stats, fetch, context, oldParse } = view;
  assert.notEqual(window.fetch, fetch);
  assert.equal(JSON.parse, oldParse);
  assert.equal(stats.intervals, 0);
  assert.equal(stats.observed.length, 1);
  assert.equal(stats.observed[0].node, player);
  assert.notEqual(stats.observed[0].options.subtree, true);
  stats.observed[0].notify();
  assert.equal(stats.frames.size, 1);
  view.flushFrame();
  assert.equal(video.muted, true);
  assert.equal(video.currentTime, 2);
  inAd = false;
  stats.observed[0].notify();
  view.flushFrame();
  assert.equal(video.playbackRate, 1.25);
  assert.equal(video.muted, false);
  vm.runInContext(code, context);
  assert.equal(stats.observed.length, 1);
  const next = { ...player };
  view.setPlayer(next);
  stats.events.get('yt-navigate-finish')();
  assert.equal(stats.observed[1].node, next);
  assert.equal(stats.disconnects, 1);
  assert.equal(stats.frames.size, 1);
  stats.events.get('pagehide')();
  assert.equal(stats.disconnects, 2);
  assert.equal(stats.frames.size, 0);
  stats.events.get('pageshow')();
  assert.equal(stats.observed.length, 3);
});

test('document-start waits for a CSS root, honors cosmetics off and needs no document observer', () => {
  const view = load('https://m.youtube.com/watch?v=demo', { hasRoot: false });
  const { window, stats } = view;
  assert.equal(window.__privacy_ubo_yt_defuser_v3, true);
  assert.equal(stats.styles.length, 0);
  assert.equal(stats.observed.length, 0);
  assert.equal(stats.frames.size, 0);
  assert.equal(stats.intervals, 0);
  view.attachRoot();
  stats.events.get('DOMContentLoaded')();
  stats.events.get('yt-navigate-finish')();
  assert.equal(stats.styles.length, 1);
  const noCosmetics = load('https://m.youtube.com/watch?v=demo', { cosmetic: false });
  assert.equal(noCosmetics.stats.styles.length, 0);
  assert.notEqual(noCosmetics.window.fetch, noCosmetics.fetch);
});

function response(url) {
  const res = new Response(JSON.stringify(responseData), { headers: { 'Content-Type': 'application/json' } });
  return Object.defineProperty(res, 'url', { value: url });
}

test('fetch only decorates JSON reads of same-origin player responses and leaves the response intact', async () => {
  let current;
  const view = load('https://www.youtube.com/watch?v=demo', { respond: () => current });
  current = response(playerUrl);
  const originalJson = Response.prototype.json;
  const result = await view.window.fetch('/youtubei/v1/player?fixture=1');
  assert.equal(result, current);
  assert.equal(result.url, playerUrl);
  assert.equal(result.bodyUsed, false);
  assert.equal(Response.prototype.json, originalJson);
  assert.deepEqual(await result.json(), cleanData);
  current = response(playerUrl);
  const text = await view.window.fetch(new URL(playerUrl));
  assert.deepEqual(JSON.parse(await text.text()), responseData);
  for (const [requested, final] of [
    ['/other', playerUrl],
    [playerUrl, 'https://outside.invalid/youtubei/v1/player'],
    [playerUrl, 'https://www.youtube.com/non-player'],
    ['https://outside.invalid/youtubei/v1/player', playerUrl],
    ['/youtubei/v1/player-substring', playerUrl]
  ]) {
    current = response(final);
    const received = view.window.fetch(requested);
    if (!requested.startsWith(playerUrl)) assert.equal(received, view.lastFetch());
    assert.deepEqual(await (await received).json(), responseData);
  }
});

test('XHR checks completion, status and final URL and does not retain a prior request scope', () => {
  const { FixtureXhr } = load('https://www.youtube.com/watch?v=demo');
  const xhr = new FixtureXhr();
  const encoded = JSON.stringify(responseData);
  xhr.open('POST', playerUrl);
  xhr.finish(encoded, playerUrl);
  assert.deepEqual(JSON.parse(xhr.responseText), cleanData);
  assert.deepEqual(JSON.parse(xhr.response), cleanData);
  xhr.readyState = 3;
  assert.equal(xhr.responseText, encoded);
  for (const [requested, final, status] of [
    ['/other', playerUrl, 200], [playerUrl, 'https://outside.invalid/youtubei/v1/player', 200], [playerUrl, playerUrl, 404]
  ]) {
    xhr.open('GET', requested);
    xhr.finish(encoded, final, { status });
    assert.equal(xhr.responseText, encoded);
  }
  xhr.open('GET', playerUrl);
  const object = { ...responseData };
  xhr.finish(object, playerUrl, { responseType: 'json' });
  assert.equal(xhr.response, object);
  assert.deepEqual(object, cleanData);
});

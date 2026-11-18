package com.privacy.browser.comic;

import org.json.JSONObject;

/** Scope a cheap reader check (and a reader-only observer) to the native foreground document. */
public final class ComicDetector {
    private ComicDetector() {}

    public static final String DETECTOR_JS = """
            (function () {
              if (window.__privacyComicDetector) return;
              const LIMIT = 150;
              const READER = '.reading-content, #readerarea, .chapter-content, .chapter-images, .comic-wrap, #comic-wrap, '
                + '.manga-read, .container-chapter-reader, [data-comic-reader]';
              const MARKED = 'img.comic-page, img.reader-image, img#comic-image, img.manga-image';
              const SOURCE_ATTRS = ['data-src', 'data-original', 'data-full-res', 'data-full', 'data-zoom-src',
                'data-highres', 'data-origin', 'data-lazy-src', 'data-url', 'data-pic', 'data-echo', 'data-file', 'data-orig', 'data-img'];
              let session = null, root = null, parent = null, observer = null, timer = 0;

              function normalize(value) {
                if (typeof value !== 'string' || value.length > 8192 || !value.trim()) return null;
                try {
                  const url = new URL(value.trim(), document.baseURI);
                  if (/^https?:$/.test(url.protocol) && !url.username && !url.password && url.href.length <= 8192) return url.href;
                } catch (_) {}
                return null;
              }

              function knownManifest() {
                const arrays = [window.ts_reader?.params?.sources, window.chapter_data?.images,
                  window.chapterImages, window.comicPages, window.pData?.imgList, window.lstImages,
                  window.comic_pages, window.chapter_images];
                return arrays.find(a => Array.isArray(a) && a.length > 0);
              }

              function declared(array) {
                if (array.length > LIMIT) return [];
                const urls = [];
                for (const item of array) {
                  const url = normalize(typeof item === 'string' ? item : item && (item.src || item.url || item.image));
                  if (!url) return []; // A gap is not a smaller, successfully detected chapter.
                  urls.push(url);
                }
                return urls;
              }

              function highRes(img) {
                const anchor = img.closest ? img.closest('a') : null;
                const href = anchor && anchor.href;
                if (href && href.length <= 8192 && (/\\.(jpe?g|png|webp|avif|gif)(\\?.*)?$/i.test(href)
                    || /\\/(?:pages?|p)\\/\\d+\\/?(?:[?#].*)?$/i.test(href) || /[?&](?:pages?|p)=\\d+/i.test(href))) return href;
                for (const attr of SOURCE_ATTRS) {
                  const value = img.getAttribute(attr);
                  if (value && value.length > 5) return value;
                }
                const set = img.srcset;
                if (typeof set === 'string' && set.length <= 16384) {
                  let best = null, bestWidth = 0;
                  for (const entry of set.split(',', 16)) {
                    const parts = entry.trim().split(/\\s+/, 2);
                    const width = parseInt(parts[1], 10) || 1;
                    if (parts[0] && width >= bestWidth) { best = parts[0]; bestWidth = width; }
                  }
                  if (best) return best;
                }
                return img.currentSrc || img.src;
              }

              function pagesFromReader(images) {
                if (images[LIMIT]) return [];
                const urls = [];
                for (let i = 0; i < LIMIT && images[i]; i++) {
                  const url = normalize(highRes(images[i]));
                  if (!url) return [];
                  urls.push(url);
                }
                return urls;
              }

              function paginated(reader) {
                const address = window.location.href;
                if (address.length > 8192) return null;
                const path = address.match(/^(https?:\\/\\/[^?#]+?\\/)(pages?\\/|p\\/)?(\\d+)(\\/?(?:[?#].*)?)$/i);
                const query = address.match(/^(https?:\\/\\/[^?#]+(?:\\?[^#]*?&|\\?)(?:pages?|p)=)(\\d+)((?:[&#].*)?)$/i);
                if (!query && (!path || (!reader && !path[2]))) return null;
                // A page-number-shaped URL alone is not a reader. Require a scoped page control or reader.
                const selects = document.getElementsByTagName('select');
                let total = 0;
                for (let i = 0; i < 12 && selects[i]; i++) {
                  const el = selects[i], options = el.options;
                  if (!options || options.length < 2) continue;
                  const first = options[0];
                  const label = (first.text || first.value || '').slice(0, 64).trim();
                  if (!/page/i.test(el.name || el.id || '') && !/^(?:page\\s*)?\\d+$/i.test(label)) continue;
                  if (options.length > LIMIT) return [];
                  total = Math.max(total, options.length);
                  for (let j = 0; j < options.length; j++) {
                    const match = (options[j].text || '').slice(0, 64).trim().match(/^(?:page\\s*)?(\\d+)$/i)
                      || (options[j].value || '').slice(0, 16).match(/^(\\d+)$/);
                    if (match) total = Math.max(total, Number(match[1]));
                  }
                }
                const nav = document.querySelector('.pagination, .pager, [data-page-count], .page-nav');
                if (nav) {
                  const label = (nav.textContent || '').slice(0, 512);
                  const match = label.match(/(?:page\\s*)?\\d+\\s*(?:\\/|of)\\s*(\\d+)/i);
                  const count = Number(nav.getAttribute('data-page-count') || (match && match[1]) || 0);
                  if (Number.isSafeInteger(count)) total = Math.max(total, count);
                }
                if (total > LIMIT) return [];
                if (total < 2) return null;
                const urls = [];
                for (let i = 1; i <= total; i++) {
                  const url = normalize(query ? query[1] + i + query[3] : path[1] + (path[2] || '') + i + path[4]);
                  if (!url) return [];
                  urls.push(url);
                }
                return urls;
              }

              function gallery() {
                const images = document.images;
                if (!images || images[512]) return []; // Don't silently offer a truncated gallery.
                const urls = [];
                for (let i = 0; i < 512 && images[i]; i++) {
                  const img = images[i], width = img.naturalWidth || Number(img.getAttribute('width')),
                    height = img.naturalHeight || Number(img.getAttribute('height'));
                  if ((width > 0 && width < 100) || (height > 0 && height < 100)) continue;
                  const url = normalize(highRes(img));
                  if (url && !/logo|avatar|icon|banner/i.test(new URL(url).pathname)) {
                    urls.push(url);
                    if (urls.length > LIMIT) return [];
                  }
                }
                return urls.length >= 2 ? urls : [];
              }

              function report(urls, manualToken) {
                let contents = JSON.stringify(urls);
                if (contents.length > 127 * 1024) { urls = []; contents = '[]'; }
                if (!manualToken && contents === session.last) return;
                session.last = contents;
                session.report(manualToken ? 'comic-manual' : 'comic', urls, manualToken || session.key);
              }

              function scan(manualToken) {
                if (!session || document.hidden) return;
                if (session.address !== window.location.href) { stop(session.key); return; }
                const array = knownManifest();
                const reader = document.querySelector(READER);
                const marked = !reader && document.querySelector(MARKED);
                // No page-wide observer. A known reader region (or one explicitly marked image) is enough.
                watch(reader || marked || null);
                const paged = !array && paginated(reader || marked);
                session.recognized = Boolean(array || reader || marked || paged);
                if (array) report(declared(array), manualToken);
                else if (paged) report(paged, manualToken);
                else if (reader) report(pagesFromReader(reader.getElementsByTagName('img')), manualToken);
                else if (marked) report(pagesFromReader(document.querySelectorAll(MARKED)), manualToken);
                else if (manualToken) report(gallery(), manualToken);
                else if (session.last !== null) report([], null); // Reader disappeared on the same native page.
              }

              function containsImage(nodes) {
                if (nodes.length > 16) return true;
                for (let i = 0; i < nodes.length; i++) {
                  const node = nodes[i];
                  if (node.tagName === 'IMG' || node.tagName === 'SOURCE' || node.querySelector?.('img, source')) return true;
                }
                return false;
              }

              function mutations(records) {
                if (!session || !root || timer || document.hidden) return;
                if (session.address !== window.location.href) { stop(session.key); return; }
                let dirty = root.isConnected === false || records.length > 96;
                for (let i = 0; !dirty && i < Math.min(records.length, 96); i++) {
                  const r = records[i];
                  if (r.type === 'attributes') {
                    const tag = r.target.tagName;
                    dirty = (tag === 'IMG' || tag === 'SOURCE' || tag === 'A') && r.target.getAttribute(r.attributeName) !== r.oldValue;
                  } else if (r.target !== parent || root.tagName === 'IMG') {
                    dirty = containsImage(r.addedNodes) || containsImage(r.removedNodes);
                  }
                }
                if (dirty) timer = setTimeout(() => { timer = 0; scan(null); }, 160);
              }

              function watch(reader) {
                if (reader === root) return;
                observer?.disconnect();
                root = reader;
                parent = root && root.parentElement;
                if (!root || typeof MutationObserver !== 'function') return;
                observer = new MutationObserver(mutations);
                observer.observe(root, {subtree: true, childList: true, attributes: true, attributeOldValue: true,
                  attributeFilter: ['src', 'srcset', 'href', ...SOURCE_ATTRS]});
                if (parent) observer.observe(parent, {childList: true});
              }

              function stop(key) {
                if (!session || session.key !== key) return;
                observer?.disconnect();
                if (timer) clearTimeout(timer);
                observer = root = parent = session = null;
                timer = 0;
              }

              function start(key, finalCheck, settleRoute) {
                if (session && session.key === key) {
                  if (finalCheck && !session.finalChecked) {
                    session.finalChecked = true;
                    if (!session.recognized) scan(null); // One native finish check for a late signal.
                  }
                  return;
                }
                if (session) stop(session.key);
                const report = window.__privacyPageReport;
                if (typeof report !== 'function') return;
                // Capture the per-commit bridge. An obsolete observer cannot use a replacement token.
                session = {key, report, address: window.location.href, last: null, recognized: false, finalChecked: finalCheck};
                // A native SPA history callback can arrive before the route has updated the reader DOM.
                if (settleRoute) timer = setTimeout(() => { timer = 0; scan(null); }, 180);
                else scan(null);
              }

              window.__privacyComicDetector = {
                start, stop,
                manual(key, token) {
                  if (session && session.key === key) scan(token);
                }
              };
            })();
            """;

    public static String start(String key, boolean finalCheck, boolean settleRoute) {
        return DETECTOR_JS + "window.__privacyComicDetector.start(" + JSONObject.quote(key)
                + "," + finalCheck + "," + settleRoute + ");";
    }

    public static String recheck(String key) {
        return "if(window.__privacyComicDetector) window.__privacyComicDetector.start(" + JSONObject.quote(key) + ",true);";
    }

    public static String stop(String key) {
        return "if(window.__privacyComicDetector) window.__privacyComicDetector.stop(" + JSONObject.quote(key) + ");";
    }

    public static String manual(String key, String token) {
        return "if(window.__privacyComicDetector) window.__privacyComicDetector.manual(" + JSONObject.quote(key) + "," + JSONObject.quote(token) + ");";
    }
}

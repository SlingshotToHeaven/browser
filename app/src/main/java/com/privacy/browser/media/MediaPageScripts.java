package com.privacy.browser.media;

/** Read-only, user-triggered DOM snapshots. No fetch/XHR patches, listeners, polling or network calls. */
public final class MediaPageScripts {
    private MediaPageScripts() {}

    public static final String OPTIONS_JS = """
            (function () {
              const rows = [], seen = new Map();
              let chars = 0;
              function add(url, type) {
                if (typeof url !== 'string' || url.length > 8192 || !/^https?:/i.test(url)) return;
                const existing = seen.get(url);
                if (existing) {
                  if (type === 'hls' || type === 'dash') existing.type = type;
                  return;
                }
                const row = {url, type};
                const length = JSON.stringify(row).length + 1;
                if (rows.length >= 64 || chars + length > 80 * 1024) return;
                rows.push(row);
                seen.set(url, row);
                chars += length;
              }
              for (const tag of ['video', 'audio']) {
                const media = document.getElementsByTagName(tag);
                for (let i = 0; i < 32 && media[i]; i++) {
                  const element = media[i];
                  add(element.currentSrc, tag);
                  add(element.src, tag);
                  const sources = element.getElementsByTagName('source');
                  for (let j = 0; j < 8 && sources[j]; j++) {
                    const type = sources[j].type || '';
                    add(sources[j].src, /mpegurl/i.test(type) ? 'hls' : /dash[+]xml/i.test(type) ? 'dash' : tag);
                  }
                }
              }
              const frames = document.getElementsByTagName('iframe');
              for (let i = 0; i < 16 && frames[i]; i++) add(frames[i].src, 'embed');
              return rows;
            })()
            """;

    public static final String LINKS_JS = """
            (function () {
              const rows = [], seen = new Set();
              let chars = 0;
              function add(url, title, fallback) {
                if (typeof url !== 'string' || url.length > 8192 || !/^https?:/i.test(url) || seen.has(url)) return;
                const path = url.split(/[?#]/, 1)[0].toLowerCase();
                const type = /[.](mp4|mkv|webm|avi|mov|m3u8|mpd)$/.test(path) ? 'video'
                  : /[.](mp3|m4a|aac|flac|wav|ogg|opus)$/.test(path) ? 'audio'
                  : /[.](zip|rar|7z|tar|gz|bz2|xz|apk|iso)$/.test(path) ? 'archive'
                  : /[.](pdf|epub|mobi|doc|docx|xls|xlsx|ppt|pptx)$/.test(path) ? 'document'
                  : /[.](jpe?g|png|webp|gif|svg|bmp)$/.test(path) ? 'image' : fallback;
                const row = {url, type, title: (title || '').slice(0, 100).trim()};
                const length = JSON.stringify(row).length + 1;
                if (rows.length >= 160 || chars + length > 80 * 1024) return;
                rows.push(row);
                seen.add(url);
                chars += length;
              }
              for (const tag of ['video', 'audio', 'source', 'track', 'iframe', 'img', 'a']) {
                const nodes = document.getElementsByTagName(tag);
                const limit = tag === 'a' ? 256 : 48;
                for (let i = 0; i < limit && nodes[i]; i++) {
                  const el = nodes[i];
                  const title = el.getAttribute('title') || el.getAttribute('alt') || el.getAttribute('label') || '';
                  add(tag === 'a' ? el.href : el.currentSrc || el.src, title, tag === 'img' ? 'image'
                    : tag === 'audio' || tag === 'video' ? tag : 'link');
                }
              }
              return rows;
            })()
            """;
}

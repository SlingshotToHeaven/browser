#!/usr/bin/env python3
"""Disposable WebView data fixture (adb reverse tcp:8013 tcp:8013)."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

PAGE = b'''<!doctype html><meta charset="utf-8"><title>Storage fixture</title>
<h1>WebView storage fixture</h1>
<p>Use both http://127.0.0.1:8013/ and http://localhost:8013/ on the device.</p>
<button id="plant">Write dummy markers</button> <button id="read">Inspect markers</button>
<button id="http">Request cacheable resource (check server log)</button><pre id="output"></pre>
<script>
const marker = location.hostname;
function dbMarker(write) {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open('browser-remediation-fixture', 1);
    req.onupgradeneeded = () => req.result.createObjectStore('values');
    req.onerror = () => reject(req.error);
    req.onsuccess = () => {
      const db = req.result;
      const tx = db.transaction('values', write ? 'readwrite' : 'readonly');
      const values = tx.objectStore('values');
      const op = write ? values.put(marker, 'mark') : values.get('mark');
      tx.oncomplete = () => { resolve(write ? marker : op.result); db.close(); };
      tx.onerror = () => { reject(tx.error); db.close(); };
    };
  });
}
async function value(fn) { try { return await fn() ?? '(missing)'; } catch (e) { return String(e); } }
async function inspect() {
  const result = { origin: location.origin, cookie: document.cookie || '(missing)' };
  result.localStorage = await value(() => localStorage.getItem('remediation-marker'));
  result.indexedDB = await value(() => dbMarker(false));
  result.cacheStorage = await value(async () => (await caches.match('/fixture-marker'))?.text());
  result.serviceWorker = await value(async () => (await navigator.serviceWorker.getRegistration())?.active?.scriptURL);
  output.textContent = JSON.stringify(result, null, 2);
}
plant.onclick = async () => {
  document.cookie = 'remediation_dummy=' + marker + '; Path=/; Max-Age=3600';
  localStorage.setItem('remediation-marker', marker);
  const attempts = await Promise.allSettled([
    dbMarker(true),
    caches.open('browser-remediation-fixture').then(cache => cache.put('/fixture-marker', new Response(marker))),
    navigator.serviceWorker?.register('/fixture-sw.js')
  ]);
  if (attempts.some(x => x.status === 'rejected')) console.info(attempts);
  await inspect();
};
read.onclick = inspect;
http.onclick = async () => { output.textContent = await value(async () => (await fetch('/cacheable')).text()); };
inspect();
</script>'''


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        path = urlsplit(self.path).path
        cache = 'no-store'
        if path == '/':
            payload, mime = PAGE, 'text/html; charset=utf-8'
        elif path == '/fixture-sw.js':
            payload, mime = b"self.addEventListener('install', event => event.waitUntil(self.skipWaiting()));", 'application/javascript'
        elif path == '/cacheable':
            payload, mime, cache = b'cacheable fixture\n', 'text/plain', 'public, max-age=3600'
        else:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header('Content-Type', mime)
        self.send_header('Cache-Control', cache)
        self.send_header('Content-Length', str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


if __name__ == '__main__':
    with ThreadingHTTPServer(('127.0.0.1', 8013), Handler) as server:
        print('Visit http://127.0.0.1:8013/ and http://localhost:8013/ after adb reverse.', flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass

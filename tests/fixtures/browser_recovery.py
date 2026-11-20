"""Synthetic two-origin browser recovery fixture; bind to loopback, never log header values."""
from collections import Counter
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from threading import Event, Lock, Thread
from urllib.parse import urlsplit

PORT = 8026
COOKIE = "recovery_fixture=1"
HOSTS = {"127.0.0.1": "a", "localhost": "b"}
PAGES = {
    "/session/one": "Committed page one",
    "/session/two": "Committed page two",
    "/session/held": "Held navigation; was not committed at the cut",
    "/renderer/sibling": "Renderer sibling",
    "/renderer/active": "Renderer active fixture",
    "/private-denied": "Private intent should never load this",
}
STORAGE = r"""<script>
const key = 'synthetic-recovery-marker';
function indexed(write) {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open('recovery-fixture-db', 1);
    req.onupgradeneeded = () => req.result.createObjectStore('markers');
    req.onerror = () => reject(req.error);
    req.onsuccess = () => {
      const db = req.result;
      const tx = db.transaction('markers', write ? 'readwrite' : 'readonly');
      const store = tx.objectStore('markers');
      const op = write ? store.put(key, key) : store.get(key);
      tx.oncomplete = () => { resolve(op.result); db.close(); };
      tx.onerror = tx.onabort = () => { reject(tx.error); db.close(); };
    };
  });
}
async function observed(fn) {
  try { return (await fn()) ? 'present' : 'absent'; }
  catch (_) { return 'unavailable'; }
}
window.inspectFixture = async () => {
  window.fixtureStorage = null;
  const [local, indexedDB, cacheStorage, worker] = await Promise.all([
    observed(() => localStorage.getItem(key) === key),
    observed(async () => (await indexed(false)) === key),
    observed(async () => (await (await caches.match('/privacy/cache-marker'))?.text()) === key),
    observed(() => navigator.serviceWorker.getRegistration('/privacy/')),
  ]);
  window.fixtureStorage = {local, indexedDB, cacheStorage, worker};
};
window.plantFixture = async (advanced) => {
  localStorage.setItem(key, key);
  if (advanced) await Promise.allSettled([
    indexed(true),
    (async () => (await caches.open('recovery-fixture-cache')).put('/privacy/cache-marker', new Response(key)))(),
    (async () => navigator.serviceWorker.register('/privacy/sw.js'))(),
  ]);
  await window.inspectFixture();
};
window.fetchFixture = async () => {
  window.fixtureCache = null;
  try { window.fixtureCache = await (await fetch('/privacy/cacheable')).text(); }
  catch (_) { window.fixtureCache = 'error'; }
};
</script>"""


class State:
    def __init__(self):
        self.lock = Lock()
        self.counts = Counter()
        self.held = Event()

    def reset(self):
        with self.lock:
            self.held.set()
            self.held = Event()
            self.counts.clear()

    def snapshot(self):
        with self.lock:
            return dict(self.counts)

    def record(self, origin, method, path, signed):
        # Only called with allowlisted paths. Record the fixture cookie match as a count.
        with self.lock:
            route = f"{origin} {method} {path}"
            self.counts[route] += 1
            if signed and path in ("/session/one", "/session/two", "/privacy/markers"):
                self.counts[route + " signed"] += 1
            return self.held


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_HEAD(self):
        self.route()

    def do_GET(self):
        self.route()

    def route(self):
        origin = HOSTS.get(self.headers.get("Host", "").split(":")[0].lower())
        path = urlsplit(self.path).path
        state = self.server.fixture
        if origin is None:
            self.reply(400)
            return
        if path == "/control/counts":
            self.reply(200, json.dumps(state.snapshot()).encode(), "application/json")
            return
        if path == "/control/release":
            state.held.set()
            self.reply(200, b"released")
            return
        targets = {"/session/login": "/session/one", "/privacy/login": "/privacy/markers"}
        if path not in PAGES and path not in targets and path not in {
            "/privacy/markers", "/privacy/cacheable", "/privacy/sw.js", "/renderer/clip.mp4"
        }:
            self.reply(404)
            return
        signed = COOKIE in {part.strip() for part in self.headers.get("Cookie", "").split(";")}
        gate = state.record(origin, self.command, path, signed)
        if path in targets:
            self.reply(303, headers={"Location": targets[path], "Set-Cookie": COOKIE + "; Path=/; Max-Age=3600; HttpOnly; SameSite=Lax"})
        elif path == "/privacy/cacheable":
            self.reply(200, b"cache-fixture", cache="public, max-age=3600")
        elif path == "/privacy/sw.js":
            self.reply(200, b"self.addEventListener('install',()=>self.skipWaiting());", "application/javascript")
        elif path == "/renderer/clip.mp4":
            if self.command == "HEAD":
                self.reply(200, mime="video/mp4", length=12_000)
            else:
                self.reply(404)  # only metadata; no playback or external requests
        else:
            if path == "/session/held" and not gate.wait(35):
                # The host must either cut the process while held or explicitly release the response.
                self.reply(503)
                return
            name = PAGES.get(path, "Privacy marker fixture")
            extra = STORAGE if path == "/privacy/markers" else (
                '<video preload="none" src="/renderer/clip.mp4"></video>' if path == "/renderer/active" else "")
            flag = "yes" if signed else "no"
            html = (f'<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">'
                    f'<title>{name}</title><body data-signed="{flag}"><h1>{name}</h1>{extra}').encode()
            self.reply(200, html, "text/html; charset=utf-8")

    def reply(self, status, body=b"", mime="text/plain", cache="no-store", headers=None, length=None):
        try:
            self.send_response(status)
            self.send_header("Content-Type", mime)
            self.send_header("Content-Length", str(len(body) if length is None else length))
            self.send_header("Cache-Control", cache)
            for name, value in (headers or {}).items():
                self.send_header(name, value)
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass  # expected when the force-stop closes a held request


@contextmanager
def serve(port=PORT):
    state = State()
    with ThreadingHTTPServer(("127.0.0.1", port), Handler) as server:
        server.fixture = state
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            yield state, server.server_port
        finally:
            state.held.set()
            server.shutdown()
            thread.join()

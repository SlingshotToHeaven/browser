#!/usr/bin/env python3
"""Synthetic document retirement: serve only loopback UX fixtures and metadata, never a media body."""
from collections import Counter
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from threading import Event, Lock, Thread

PORT = 8028
OLD = {"media": "/media-old", "picker": "/picker-old"}
ASSETS = {"/clip/old.mp4", "/clip/new.mp4"}
HEADER = '<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">'
BRIDGES = """<p id="bridges">Checking bridge exposure</p><script>
  document.getElementById('bridges').textContent = 'Legacy=' + typeof window.AndroidInterface +
      ', Home=' + typeof window.PrivacyHome;
</script>"""


def old_page(case, title, content):
    # Stay on the old document until the host releases *this* case, including while a native dialog is open.
    return (f'<title>{title}</title><h1>{title}</h1>{content}'
            '<p id="wait">Waiting for fixture replacement</p><script>'
            f'const check = "/control/ready/{case}", next = "/{case}-new";'
            "let attempts = 0; async function waitForReplacement() {"
            "if (++attempts > 240) { document.getElementById('wait').textContent = 'Fixture wait expired'; return; }"
            "try { const r = await fetch(check, {cache:'no-store'});"
            "if (r.ok && (await r.text()) === 'go') { location.assign(next); return; } } catch (_) {}"
            "setTimeout(waitForReplacement, 500); } waitForReplacement();</script>")


PAGES = {
    OLD["media"]: old_page("media", "Old media document", '<video preload="none" src="/clip/old.mp4"></video>'),
    "/media-new": '<title>New media document</title><h1>New media document</h1><video preload="none" src="/clip/new.mp4"></video>',
    OLD["picker"]: old_page("picker", "Old picker document", '<div id="fixture-target">Select this synthetic element</div>'),
    "/picker-new": ("<title>New picker document</title><h1>New picker document</h1>"
                    "<button id=\"check\">Test ordinary tap</button><p id=\"result\">Not tapped</p>"
                    "<script>document.getElementById('check').onclick = () => {"
                    "document.getElementById('result').textContent = 'New document tap accepted';};</script>"),
    "/after-home": '<title>Ordinary page after Home</title><h1>Ordinary page after Home</h1>' + BRIDGES,
}


class State:
    def __init__(self):
        self.lock, self.counts, self.released = Lock(), Counter(), set()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_HEAD(self):
        self.route()

    def do_GET(self):
        self.route()

    def do_POST(self):
        state = self.server.fixture
        case = self.path.removeprefix('/control/advance/')
        if self.path != '/control/advance/' + case or case not in OLD:
            return self.reply(404)
        with state.lock:
            allowed = state.counts['GET ' + OLD[case]] == 1 and case not in state.released
            if allowed:
                state.released.add(case)
        if allowed:
            self.reply(200, b'released')
        else:
            self.reply(409, b'no fresh single old page')

    def route(self):
        state = self.server.fixture
        if self.path == '/control/counts':
            with state.lock:
                body = json.dumps({'requests': dict(state.counts), 'released': sorted(state.released)}).encode()
            return self.reply(200, body, 'application/json')
        case = self.path.removeprefix('/control/ready/')
        if self.path == '/control/ready/' + case and case in OLD:
            with state.lock:
                ready = case in state.released
            return self.reply(200, b'go' if ready else b'wait')
        if self.path not in PAGES and self.path not in ASSETS:
            return self.reply(404)
        # Fixed synthetic routes only: no cookies, request headers, queries or browsing logs.
        with state.lock:
            state.counts[f'{self.command} {self.path}'] += 1
        if self.path in ASSETS:
            return self.reply(200, mime='video/mp4', length=12_000) if self.command == 'HEAD' else self.reply(404)
        self.reply(200, (HEADER + PAGES[self.path]).encode(), 'text/html; charset=utf-8')

    def reply(self, status, body=b'', mime='text/plain', length=None):
        try:
            self.send_response(status)
            self.send_header('Content-Type', mime)
            self.send_header('Content-Length', str(len(body) if length is None else length))
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            if self.command != 'HEAD':
                self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass  # The old document may leave while its control request is draining.


@contextmanager
def serve(port=PORT):
    with ThreadingHTTPServer(('127.0.0.1', port), Handler) as server:
        server.fixture = State()
        worker = Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            yield server.server_port
        finally:
            server.shutdown()
            worker.join(timeout=3)


if __name__ == '__main__':
    with serve() as port:
        print(f'Browser retirement fixture listening on host loopback {port}', flush=True)
        try:
            Event().wait()
        except KeyboardInterrupt:
            pass

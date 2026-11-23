#!/usr/bin/env python3
"""Bounded, loopback-only verification and retirement; no route permits a complete CBZ."""
from collections import Counter
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from threading import Event, Lock, Thread
from time import monotonic

from comic_archives import png


PORT = 8030
CASES = ('retry', 'retire')
RELEASE_LIMIT_S = 45  # Before the app's 60 s verifier wait, even measured from the denial.
FIRST_IMAGE = png((31, 93, 185))
RETRY_IMAGE = png((225, 153, 29))


def html(body):
    return ('<!doctype html><meta charset="utf-8">'
            '<meta name="viewport" content="width=device-width, initial-scale=1">' + body).encode()


def chapter(case):
    heading = f'COMIC-01 wireless {case} fixture'
    plan = json.dumps([f'/{case}/first.png', f'/{case}/gate', f'/{case}/missing.png'])
    link = '<p><a href="/retire">Open verifier retirement fixture</a></p>' if case == 'retry' else ''
    poll = '' if case == 'retry' else '''
let polling = false;
const timer = setInterval(async () => {
  if (polling) return;
  polling = true;
  try {
    const r = await fetch('/control/retire-ready', {cache:'no-store', credentials:'omit'});
    if (r.ok && await r.text() === 'go') {
      clearInterval(timer);
      location.replace('/after-retire');
    }
  } catch (_) { /* Leave the old page in place if the fixture is gone. */ }
  finally { polling = false; }
}, 500);
setTimeout(() => clearInterval(timer), 240000);
'''
    # The chapter declares a plan, not <img> elements that could fetch before native approval.
    return html(f'<title>{heading}</title><h1>{heading}</h1><p>Three declared positions.</p>{link}'
                f'<script>window.chapterImages = {plan};\n{poll}</script>')


def verifier(case):
    return html(f'''<title>Synthetic {case} verifier</title>
<h1 id="challenge-stage">Synthetic {case} verifier</h1>
<p>This is a local fixture, not a provider or a real verification service.</p>
<p id="marker">Waiting for local verifier witness</p>
<script>
fetch('/witness/{case}', {{method:'POST', credentials:'omit', cache:'no-store'}}).then(response => {{
  if (response.status === 204) document.getElementById('marker').textContent = 'Local verifier witness recorded';
}}).catch(() => {{}});
</script>''')


AFTER = html('<title>Comic verifier retired</title><h1>Comic verifier retired</h1><p>New same-tab fixture document.</p>')


class State:
    def __init__(self):
        self.lock, self.counts = Lock(), Counter()
        self.denial_time, self.release_age_ms = {}, {}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def count(self, result=''):
        # Only fixed fixture paths reach this method; never record headers, queries or user data.
        self.server.state.counts[f'{self.command} {self.path}{result}'] += 1

    def do_GET(self):
        state = self.server.state
        path = self.path
        if path == '/control/counts':
            with state.lock:
                body = json.dumps({'requests': dict(state.counts), 'release_age_ms': state.release_age_ms}, sort_keys=True).encode()
            return self.reply(200, body, 'application/json')
        if path == '/control/retire-ready':
            with state.lock:
                ready = 'retire' in state.release_age_ms
            return self.reply(200, b'go' if ready else b'wait')
        if path == '/after-retire':
            with state.lock:
                ready = 'retire' in state.release_age_ms
                if ready: self.count()
            return self.reply(200, AFTER, 'text/html; charset=utf-8') if ready else self.reply(409)
        for case in CASES:
            base = '/' + case
            if path == base:
                return self.observed(200, chapter(case), 'text/html; charset=utf-8')
            if path == base + '/first.png':
                return self.observed(200, FIRST_IMAGE, 'image/png')
            if path == base + '/gate':
                return self.gate(case)
            if path == base + '/missing.png':
                return self.observed(404, suffix=' 404')
        self.reply(404)

    def gate(self, case):
        state = self.server.state
        with state.lock:
            if case not in state.denial_time:
                state.denial_time[case] = monotonic()
                self.count(' 403')
                status, body, mime = 403, b'fixture verification required', 'text/plain'
            elif case == 'retry' and case in state.release_age_ms:
                self.count(' 200 png')
                status, body, mime = 200, RETRY_IMAGE, 'image/png'
            else:
                self.count(' 200 html')
                status, body, mime = 200, verifier(case), 'text/html; charset=utf-8'
        self.reply(status, body, mime)

    def do_POST(self):
        state = self.server.state
        for case in CASES:
            witness = f'/witness/{case}'
            reviewed = f'GET /{case}/gate 200 html'
            if self.path == witness:
                with state.lock:
                    allowed = state.counts[reviewed] == 1 and state.counts[f'POST {witness}'] == 0
                    if allowed: self.count()
                return self.reply(204 if allowed else 409)
            if self.path == f'/control/release/{case}':
                with state.lock:
                    age = monotonic() - state.denial_time.get(case, float('-inf'))
                    allowed = (case not in state.release_age_ms and age < RELEASE_LIMIT_S and
                               state.counts[f'GET /{case}'] == 1 and state.counts[f'GET /{case}/first.png'] == 1 and
                               state.counts[f'GET /{case}/missing.png 404'] == 0 and
                               state.counts[reviewed] == 1 and state.counts[f'POST {witness}'] == 1)
                    if allowed: state.release_age_ms[case] = int(age * 1000)
                return self.reply(200, b'released') if allowed else self.reply(409, b'no fresh verifier witness')
        self.reply(404)

    def observed(self, status, body=b'', mime='text/plain', suffix=''):
        with self.server.state.lock:
            self.count(suffix)
        self.reply(status, body, mime)

    def reply(self, status, body=b'', mime='text/plain'):
        self.send_response(status)
        self.send_header('Content-Type', mime)
        if status != 204: self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        try:
            if body: self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass  # A retired document may leave while the last fixture request drains.


@contextmanager
def serve(port=PORT):
    with ThreadingHTTPServer(('127.0.0.1', port), Handler) as server:
        server.state = State()
        worker = Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            yield server.server_port
        finally:
            server.shutdown()
            worker.join(timeout=3)


if __name__ == '__main__':
    with serve() as port:
        print(f'Comic verification fixture on host loopback {port}', flush=True)
        try:
            Event().wait()
        except KeyboardInterrupt:
            pass

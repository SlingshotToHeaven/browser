#!/usr/bin/env python3
"""Local, disposable device fixture. Reverse TCP 8011 and 8012 via adb."""
import base64
import io
import math
import struct
import threading
import time
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit


SESSION_PAGE = b'''<!doctype html><meta charset="utf-8"><title>Tab session fixture</title>
<h1>Tab session fixture</h1><pre id="status"></pre>
<p><button id="push">Push same-URL entry</button> <button id="repeat">Repeat unchanged replaceState</button></p>
<p><a href="/session?other=1">Visit another address</a> | <a href="/session-cookie">Dummy authenticated page</a></p>
<script>
const statusText = document.getElementById('status');
function show() {
  statusText.textContent = JSON.stringify({url: location.href, length: history.length, state: history.state});
}
document.getElementById('push').onclick = () => {
  history.pushState({step: (history.state?.step ?? 0) + 1}, '', location.href);
  show();
};
document.getElementById('repeat').onclick = () => {
  let count = 0;
  const timer = setInterval(() => {
    history.replaceState(history.state, '', location.href);
    if (++count === 25) { clearInterval(timer); show(); }
  }, 100);
};
window.addEventListener('popstate', show);
show();
</script>'''


def live_tab_page():
    # One second of synthetic audio, embedded so playback does not depend on another service.
    output = io.BytesIO()
    rate = 8000
    with wave.open(output, 'wb') as tone:
        tone.setparams((1, 2, rate, 0, 'NONE', 'not compressed'))
        tone.writeframes(struct.pack('<' + 'h' * rate, *(
            int(3000 * math.sin(2 * math.pi * 440 * i / rate)) for i in range(rate))))
    audio = base64.b64encode(output.getvalue()).decode('ascii')
    return f'''<!doctype html><meta charset="utf-8"><title>Live tab fixture</title>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <h1>Live tab fixture</h1>
    <p><label>Disposable draft <textarea rows="4" autocomplete="off"></textarea></label></p>
    <p>Quiet synthetic loop (tap Play): <audio controls loop src="data:audio/wav;base64,{audio}"></audio></p>
    <p><a href="/session">Open a second address</a></p>'''


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if urlsplit(self.path).path != '/script-landing':
            self.send_error(404)
            return
        length = int(self.headers.get('Content-Length', 0))
        if not 0 <= length <= 1024:
            self.send_error(413)
            return
        self.rfile.read(length)
        self.script_probe('POST')

    def script_probe(self, method, with_links=False):
        html = f'''<!doctype html><title>{method}: script did not run</title>
        <p id="mark">{method}: JavaScript did not run.</p>
        <script>
        document.title='{method}: script ran';
        document.getElementById('mark').textContent='{method}: JavaScript ran.';
        </script>'''
        if with_links:
            html += '''<a href="/script-redirect">GET redirect to the other host</a>
            <form method="post" action="http://localhost:8012/script-landing">
            <input type="hidden" name="fixture" value="1"><button>POST to the other host</button></form>
            <iframe src="http://localhost:8012/script-landing"></iframe>'''
        data = html.encode('utf-8')
        self.send_response(200)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        path = urlsplit(self.path).path
        if path == '/session-cookie/start':
            self.send_response(303)
            self.send_header('Location', '/session-cookie')
            self.send_header('Set-Cookie', 'tab_session_fixture=1; Path=/session-cookie; HttpOnly; Max-Age=3600; SameSite=Lax')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Length', '0')
            self.end_headers()
            return
        if path == '/slow':
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            self.wfile.write(b'<title>Slow tab</title><p>Loading slow tab')
            self.wfile.flush()
            time.sleep(8)
            self.wfile.write(b' &mdash; finished.</p>')
            return
        if path == '/script-redirect':
            self.send_response(302)
            self.send_header('Location', 'http://localhost:8012/script-landing')
            self.end_headers()
            return
        if path in ('/script-probe', '/script-landing'):
            self.script_probe('GET', with_links=path == '/script-probe')
            return
        if path == '/assistant-turn':
            page = '''<!doctype html><meta charset="utf-8"><title>Assistant turn fixture</title>
            <p id="fixture-first">Fixture first document text</p>
            <p id="fixture-second">Fixture second document text</p>'''
        elif path == '/session':
            page = SESSION_PAGE.decode('utf-8')
        elif path == '/tab-live':
            page = live_tab_page()
        elif path == '/session-cookie':
            signed_in = 'tab_session_fixture=1' in [part.strip() for part in self.headers.get('Cookie', '').split(';')]
            result = 'Signed in' if signed_in else 'Signed out'
            page = f'''<!doctype html><title>Dummy authenticated page</title>
            <p>{result} (fixture cookie only)</p><a href="/session-cookie/start">Set dummy login cookie</a>'''
        elif path == '/frame':
            page = '''<p id="result">Waiting…</p><script>
            result.textContent = 'Cross-origin frame: legacy=' + typeof window.AndroidInterface +
              ', home=' + typeof window.PrivacyHome;
            </script>'''
        else:
            page = '''<title>Tab trust fixture</title>
            <h1>Tab trust fixture</h1><p id="result">Waiting…</p>
            <p><a href="/slow">Slow page</a> (long-press to open in background)</p>
            <p><button onclick="history.pushState({}, '', '/spa#local'); document.title='SPA path';">Native history change</button></p>
            <iframe src="http://127.0.0.1:8012/frame"></iframe>
            <iframe src="https://home.privacy-browser.invalid/"></iframe>
            <script>
            result.textContent = 'Ordinary top frame: legacy=' + typeof window.AndroidInterface +
              ', home=' + typeof window.PrivacyHome;
            </script>'''
        self.send_response(200)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(page.encode())


if __name__ == '__main__':
    with ThreadingHTTPServer(('127.0.0.1', 8011), Handler) as main, \
            ThreadingHTTPServer(('127.0.0.1', 8012), Handler) as frame:
        threading.Thread(target=frame.serve_forever, daemon=True).start()
        print('Visit http://127.0.0.1:8011/ on a test device after adb reverse.', flush=True)
        try:
            main.serve_forever()
        except KeyboardInterrupt:
            pass
        frame.shutdown()

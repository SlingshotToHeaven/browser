#!/usr/bin/env python3
"""Disposable PERF-02 fixture. Serves only loopback or named synthetic HTTP hosts; never forwards."""
import argparse
import json
import re
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

LOOPBACK = '127.0.0.1:8014'
SITE = 'startup.perf02.invalid'
EXEMPT = 'excepted.perf02.invalid'
TRACKER = 'tracker.perf02.invalid'
ORIGINS = tuple('http://' + host for host in (LOOPBACK, SITE, EXEMPT))
TAB_PATH = re.compile(r'/tab/([1-9]|[12][0-9]|30)')
PROBE_PATH = re.compile(r'/(network/(?:blocked|allowed)|custom)/(incoming|tab-(?:[1-9]|[12][0-9]|30))\.svg')


def index_fixture(count, origin):
    rows = [{
        'id': str(uuid.uuid5(uuid.NAMESPACE_URL, f'perf-02-tab-{i}')),
        'url': f'{origin}/tab/{i}',
        'title': f'PERF-02 tab {i}',
        'desktop': False,
        'renderer_lost': False,
    } for i in range(1, count + 1)]
    return {'version': 1, 'selected': rows[-1]['id'], 'tabs': rows}


def page(label, tag, probes):
    # Fixed, obvious visual endpoint before any user script or delayed load.
    img = ''.join(f'<img alt="{name}" width="12" height="12" src="http://{TRACKER}/{path}/{tag}.svg">'
                  for name, path in (('Network blocked', 'network/blocked'), ('Network allowed', 'network/allowed'),
                                     ('Custom blocked', 'custom'))) if probes else ''
    return f'''<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    <title>PERF-02 {label}</title><style>body {{ background:#ffe04a; color:#151515; font:22px sans-serif; }}</style>
    <h1>PERF-02 {label}</h1><p class="perf02-hide">Cosmetic fixture marker</p>
    <p id="script">No page JavaScript ran</p>
    <script>document.getElementById('script').textContent = window.__privacyFingerprintInstalled
        ? 'Fingerprint guard present before inline script' : 'Inline script ran without document-start guard';</script>
    {img}'''


class Handler(BaseHTTPRequestHandler):
    def log_message(self, _format, *args):
        # Only log the fixture routes below, never credentials or other proxy requests.
        pass

    def reply(self, mime, body):
        data = body.encode('utf-8')
        self.send_response(200)
        self.send_header('Content-Type', mime)
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        try:
            target = urlsplit(self.path)
            host = target.netloc if target.scheme == 'http' else self.headers.get('Host')
        except ValueError:
            self.send_error(400)
            return
        tab = TAB_PATH.fullmatch(target.path)
        if host in (LOOPBACK, SITE, EXEMPT) and (tab or target.path == '/incoming'):
            number = tab[1] if tab else None
            label, tag = (f'tab {number}', f'tab-{number}') if number else ('incoming link', 'incoming')
            print(f'GET {host}/{tag}', flush=True)
            self.reply('text/html; charset=utf-8', page(label, tag, host != LOOPBACK))
        elif host == TRACKER and PROBE_PATH.fullmatch(target.path):
            print(f'GET {host}{target.path}', flush=True)
            self.reply('image/svg+xml', '<svg xmlns="http://www.w3.org/2000/svg" width="12" height="12">'
                                       '<rect width="12" height="12" fill="navy"/></svg>')
        else:
            self.send_error(404)


if __name__ == '__main__':
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument('--index', type=int, choices=(1, 10, 30), help='Print a disposable URL-only session index instead of serving')
    args.add_argument('--origin', choices=ORIGINS, default=ORIGINS[0], help='Origin to use for --index')
    options = args.parse_args()
    if options.index:
        print(json.dumps(index_fixture(options.index, options.origin)))
    else:
        with ThreadingHTTPServer(('127.0.0.1', 8014), Handler) as server:
            print('PERF-02 fixture on 127.0.0.1:8014', flush=True)
            try:
                server.serve_forever()
            except KeyboardInterrupt:
                pass

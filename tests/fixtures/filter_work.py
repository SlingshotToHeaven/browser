#!/usr/bin/env python3
"""Fixed local PERF-05/06 corpus. HTTP only, known hosts/routes only, never forwards."""
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

PAGE = {'page.perf17.invalid', 'excepted.perf17.invalid', '127.0.0.1:8015'}
TRACKER = 'tracker.perf17.invalid'
HTML = Path(__file__).with_suffix('.html').read_bytes()
SVG = b'<svg xmlns="http://www.w3.org/2000/svg" width="32" height="16"><rect width="32" height="16" fill="navy"/></svg>'


class Handler(BaseHTTPRequestHandler):
    def log_message(self, _format, *args):
        pass  # no headers, proxy URLs or unrecognized routes in the log

    def reply(self, host, path, mime, body, csp=None):
        print(f'GET {host}{path}', flush=True)
        self.send_response(200)
        self.send_header('Content-Type', mime)
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Cache-Control', 'no-store')
        self.send_header('Content-Length', str(len(body)))
        if csp:
            self.send_header('Content-Security-Policy', csp)
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        try:
            target = urlsplit(self.path)
            host = target.netloc if target.scheme == 'http' else self.headers.get('Host')
        except ValueError:
            self.send_error(400)
            return
        path = target.path
        if host in PAGE and path in ('/static', '/mutations', '/strict'):
            csp = "default-src 'none'; script-src 'unsafe-inline'; style-src 'none'; connect-src 'self' http://tracker.perf17.invalid" if path == '/strict' else None
            self.reply(host, path, 'text/html; charset=utf-8', HTML, csp)
        elif host == TRACKER and (path == '/deny/allowed.json' or re.fullmatch(r'/deny/item-(?:[0-9]|[1-9][0-9]|[12][0-9]{2})\.json', path)):
            self.reply(host, path, 'application/json', b'{"fixture":"server"}')
        elif host == TRACKER and path == '/deny/logo.svg':
            self.reply(host, path, 'image/svg+xml', SVG)
        elif host == TRACKER and path == '/deny/frame':
            self.reply(host, path, 'text/html', b'<h1>FIXTURE NETWORK FRAME</h1>')
        elif host in PAGE and path in ('/fonts/sample.woff2', '/media/poster.png'):
            self.reply(host, path, 'text/plain', b'server')
        else:
            self.send_error(404)


if __name__ == '__main__':
    with ThreadingHTTPServer(('127.0.0.1', 8015), Handler) as server:
        print('PERF-05/06 fixture on 127.0.0.1:8015 (non-forwarding)', flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass

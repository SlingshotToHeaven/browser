#!/usr/bin/env python3
"""Synthetic loopback comic reader; for a disposable Android install only."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import struct
import time
from urllib.parse import urlsplit
import zlib


PORT = 8014
VERIFIED = 'comic_fixture_verified=1'


def png(rgb):
    def chunk(name, data):
        return struct.pack('>I', len(data)) + name + data + struct.pack('>I', zlib.crc32(name + data))

    width, height = 120, 160
    pixels = (b'\0' + bytes(rgb) * width) * height
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b''))


IMAGES = {f'/images/{name}.png': png(color) for name, color in {
    'red': (210, 30, 30), 'green': (30, 175, 65), 'blue': (30, 85, 215),
}.items()}
RED, BLUE = '/images/red.png', '/images/blue.png'
SCENARIOS = {
    '/ok': [RED, '/reader/green', RED, BLUE],
    '/missing': [RED, '/missing-page', BLUE],
    '/short': [RED, '/short-page', BLUE],
    '/slow': [RED, '/slow-page', BLUE],
    '/verify': [RED, '/gated-page', BLUE],
    '/known-gap': [RED, None, BLUE],
    '/oversized': [RED] * 151,
}


class Fixture(BaseHTTPRequestHandler):
    def do_GET(self):
        path = urlsplit(self.path).path
        # Do not log headers, queries, or credentials.
        print('Comic fixture GET', path, flush=True)
        if path == '/':
            links = ''.join(f'<li><a href="{name}">{name}</a></li>' for name in SCENARIOS)
            self.html(200, f'<h1>Comic archive fixture</h1><ul>{links}</ul>')
        elif path in SCENARIOS:
            pages = json.dumps(SCENARIOS[path])
            reset = (f"document.cookie='{VERIFIED}; Max-Age=0; Path=/gated-page';" if path == '/verify' else '')
            self.html(200, f'<script>{reset}window.chapterImages={pages};</script><h1>{path[1:]} comic fixture</h1>'
                           '<p>Use the browser’s comic button. The page itself does not request image files.</p><a href="/">All fixtures</a>')
        elif path == '/reader/green':
            self.html(200, '<img class="reader-image" src="/images/green.png">')
        elif path == '/gated-page':
            if VERIFIED in self.headers.get('Cookie', '').split('; '):
                self.respond(200, 'image/png', IMAGES[BLUE])
            else:
                self.html(403, '<h1>Synthetic manual verification</h1>'
                               f'''<button onclick="document.cookie='{VERIFIED}; Path=/gated-page; SameSite=Lax'; this.disabled=true">
                               Set the fixture cookie, then press Verification Done in the browser</button>''')
        elif path == '/short-page':
            body = IMAGES[BLUE]
            self.respond(200, 'image/png', body[:24], declared=len(body))
        elif path == '/slow-page':
            self.respond(200, 'image/png', IMAGES[BLUE], stall=True)
        elif path in IMAGES:
            self.respond(200, 'image/png', IMAGES[path])
        else:
            self.html(404, '<h1>No such page</h1>')

    def html(self, status, content):
        self.respond(status, 'text/html; charset=utf-8', ('<!doctype html><meta charset=utf-8>' + content).encode())

    def respond(self, status, content_type, body, declared=None, stall=False):
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(body) if declared is None else declared))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        try:
            if stall:
                self.wfile.write(body[:24])
                self.wfile.flush()
                time.sleep(12)
                body = body[24:]
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass  # Expected on cancellation or idle timeout.

    def log_message(self, *_args):
        pass


if __name__ == '__main__':
    with ThreadingHTTPServer(('127.0.0.1', PORT), Fixture) as server:
        print(f'Open http://127.0.0.1:{PORT}/ in the disposable browser (adb reverse tcp:{PORT} tcp:{PORT})', flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass

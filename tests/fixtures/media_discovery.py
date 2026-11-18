#!/usr/bin/env python3
"""Disposable PERF-08 pages and request counters; no real media payloads."""
import argparse
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import threading
import time
from urllib.parse import urlsplit

PORT = 8016
CLIP = '/f/Clip.mp4?SIG=A%2f&part=7'
HLS = '/f/Hls/Master.m3u8?SIG=B%2F'
MASTER = (b'#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=300000,RESOLUTION=640x360\nLow.m3u8?SIG=Low\n'
          b'#EXT-X-STREAM-INF:BANDWIDTH=950000,RESOLUTION=1280x720\nHigh.m3u8?SIG=High\n')
LINKS = {path: label for path, label in [
    ('/ordinary', 'Ordinary image-heavy page, no media'),
    ('/options', 'Duplicate direct MP4, HLS and range fallback'),
    ('/slow', 'Open options, then navigate, close or switch during delayed HEAD'),
    ('/reader', 'Reader change burst and SPA replacement'),
    ('/gallery', 'Ordinary gallery, explicit Find Comic Pages only'),
]}
ASSETS = {'/f/Clip.mp4', '/f/Hls/Master.m3u8', '/f/Hls/Low.m3u8', '/f/Hls/High.m3u8',
          '/f/Range.mp4', '/f/hold.mp4', '/f/next.mp4'}
STATS = Counter()
LOCK = threading.Lock()


class Fixture(BaseHTTPRequestHandler):
    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        path = urlsplit(self.path).path
        if path in ASSETS:
            # Count presence only. No request headers, query strings or browsing URLs go to logs.
            key = f'{self.command} {path}' + (' (range)' if self.headers.get('Range') else '')
            with LOCK:
                STATS[key] += 1
                for header in ('Cookie', 'Referer', 'Authorization'):
                    if self.headers.get(header):
                        STATS[f'{key} [{header} present]'] += 1
            print(key, flush=True)
            self.asset(path)
        elif path == '/stats':
            with LOCK:
                self.reply(200, 'application/json', json.dumps(STATS, sort_keys=True).encode())
        elif path == '/reset':
            with LOCK:
                STATS.clear()
            self.reply(200, 'text/plain', b'Our loopback counters are now empty.\n')
        elif path == '/':
            self.html('<h1>Media discovery performance fixture</h1><ul>' + ''.join(
                f'<li><a href="{url}">{name}</a></li>' for url, name in LINKS.items()) + '</ul>')
        elif path in ('/ordinary', '/gallery'):
            # data-src permits measuring inspection, not network loading. Repeated pages in /gallery are intentional.
            count = 120 if path == '/ordinary' else 3
            images = ''.join(f'<img width="120" height="120" data-src="/page-{i if count != 3 else i % 2}.png">'
                             for i in range(count))
            self.html('<h1>Ordinary gallery</h1><p>No media. The comic button must not light up automatically.</p>' + images)
        elif path == '/options':
            self.html(f'''<h1>Options: direct + HLS</h1><p>Wait without tapping first. Open the faded download button twice.</p>
                <video preload="none" src="{CLIP}"></video><video preload="none" src="{CLIP}#t=2"></video>
                <video preload="none"><source src="{HLS}" type="application/vnd.apple.mpegurl"></video>
                <video preload="none" src="/f/Range.mp4"></video>
                <script>document.cookie='perf19_dummy=1; Path=/; SameSite=Lax';</script>''')
        elif path == '/slow':
            self.html('''<h1>Cancellation: two direct media hints</h1>
                <video preload="none" src="/f/hold.mp4"></video><video preload="none" src="/f/next.mp4"></video>
                <button id="spa-after-options" onclick="setTimeout(() => {
                    history.pushState({}, '', '/ordinary');
                    document.body.replaceChildren(document.createTextNode('Replaced native route'));
                }, 1400)">Change route in 1.4 seconds</button>''')
        elif path in ('/reader', '/reader-next'):
            self.html('''<h1>Reader: two repeated slots and one different image</h1>
                <div class="reading-content">
                <img width="120" height="160" data-src="/page-a.png">
                <img width="120" height="160" data-src="/page-b.png">
                <img width="120" height="160" data-src="/page-a.png"></div>
                <button onclick="for(let i=0;i<75;i++)document.querySelector('img').setAttribute('data-src','/page-a.png')">Unchanged writes</button>
                <button onclick="for(let i=0;i<75;i++)document.querySelector('img').setAttribute('data-src','/page-'+i+'.png')">Change burst</button>
                <button onclick="history.pushState({}, '', '/reader-next');
                    const reader=document.querySelector('.reading-content');
                    reader.replaceChildren(...Array.from({length:2}, () => {
                    const img=document.createElement('img');img.setAttribute('data-src','/page-d.png');return img;}));">Next chapter (SPA)</button>''')
        elif path.startswith('/page-') and path.endswith('.png'):
            # Asset URLs are only hints for discovery/ordering in this fixture, not image archives.
            self.reply(404, 'text/plain', b'Use comic_archives.py for playable CBZ fixture images.\n')
        else:
            self.reply(404, 'text/plain', b'Synthetic fixture only\n')

    def asset(self, path):
        if path == '/f/Hls/Master.m3u8':
            self.reply(200, 'application/vnd.apple.mpegurl', MASTER)
        elif path == '/f/Range.mp4' and self.command == 'HEAD':
            self.reply(405, 'text/plain', b'')
        elif path == '/f/Range.mp4' and self.headers.get('Range') == 'bytes=0-0':
            self.reply(206, 'video/mp4', b'x', extra={'Content-Range': 'bytes 0-0/98321'})
        elif path == '/f/hold.mp4' and self.command == 'HEAD':
            time.sleep(4)
            self.reply(200, 'video/mp4', b'', length=123456)
        elif self.command == 'HEAD':
            self.reply(200, 'video/mp4', b'', length=123456)
        else:
            self.reply(404, 'text/plain', b'No playback bytes in the metadata fixture\n')

    def html(self, body):
        self.reply(200, 'text/html; charset=utf-8', ('<!doctype html><meta charset=utf-8>' + body).encode())

    def reply(self, status, content_type, body, length=None, extra=None):
        try:
            self.send_response(status)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(len(body) if length is None else length))
            self.send_header('Cache-Control', 'no-store')
            for name, value in (extra or {}).items():
                self.send_header(name, value)
            self.end_headers()
            if self.command != 'HEAD':
                self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass  # The client can leave while a metadata response is pending.

    def log_message(self, *_args):
        pass


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--preview', action='store_true',
                        help='Listen on all interfaces for the Arena preview instead of loopback.')
    preview = parser.parse_args().preview
    host = '0.0.0.0' if preview else '127.0.0.1'
    with ThreadingHTTPServer((host, PORT), Fixture) as server:
        note = 'Arena preview' if preview else f'adb reverse tcp:{PORT} tcp:{PORT}'
        print(f'PERF-08 fixture on http://{host}:{PORT}/ ({note})', flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass

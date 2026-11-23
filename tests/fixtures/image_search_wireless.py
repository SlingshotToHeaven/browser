#!/usr/bin/env python3
"""Loopback-only images for a handset UI test that never confirms a share."""
from base64 import b64encode
from collections import Counter
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import struct
from threading import Event, Lock, Thread
import zlib


PORT = 8029
PHOTO = '/image/Case%2FCard.png?Marker=MiXeD&v=01'


def patch_png():
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))

    row = b'\0' + bytes((39, 126, 215)) * 24 + bytes((250, 176, 49)) * 24
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 48, 32, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(row * 32)) + chunk(b'IEND', b''))


PNG = patch_png()
ENCODED = b64encode(PNG).decode('ascii')


def page(kind, heading, source):
    # Use fixture links, not the omnibox, to move to the next same-tab case.
    next_link = {
        'review': '<p><a href="/data">Open inline fixture</a></p>',
        'data': '<p><a href="/blob">Open blob fixture</a></p>',
    }.get(kind, '')
    # The HTTP page is the native-review positive control. The two other pages
    # contain no HTTP(S) image for the picker to choose by proximity instead.
    witness = '' if kind == 'review' else f'''
let reported = false;
image.addEventListener('click', async event => {{
  if (reported || !event.isTrusted || !image.complete || !image.naturalWidth ||
      !document.getElementById('__image_search_picker_banner')) return;
  reported = true;
  try {{
    const response = await fetch('/witness/{kind}', {{method:'POST', credentials:'omit', cache:'no-store'}});
    if (response.status === 204) status.textContent = 'Normal tap recorded while picker active';
  }} catch (_) {{ /* The wire witness is required; no offline success marker. */ }}
}});
'''
    return f'''<!doctype html>
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{heading}</title>
<style>
  body {{ margin: 16px; font: 18px system-ui; }}
  h1 {{ font-size: 23px; }}
  figure {{ margin: 105px 0 0; }}
  img {{ display: block; width: min(60vw, 240px); height: auto; touch-action: manipulation; }}
</style>
<h1>{heading}</h1>
<figure>
  <img id="fixture_image" alt="Blue and amber synthetic image" width="240" height="160">
  <figcaption id="fixture_status">Waiting for fixture image</figcaption>
</figure>
{next_link}
<script>
(() => {{
  const image = document.getElementById('fixture_image');
  const status = document.getElementById('fixture_status');
  image.addEventListener('load', () => {{ status.textContent = 'Synthetic image ready'; }});
  {witness}
  image.src = {source};
}})();
</script>
'''.encode()


PAGES = {
    '/review': page('review', 'Image review fixture', json.dumps(PHOTO)),
    '/data': page('data', 'Inline image fixture', json.dumps('data:image/png;base64,' + ENCODED)),
    '/blob': page('blob', 'Blob image fixture',
                  f"URL.createObjectURL(new Blob([Uint8Array.from(atob('{ENCODED}'), c => c.charCodeAt(0))], {{type:'image/png'}}))"),
}
WITNESSES = {'/witness/data', '/witness/blob'}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def count(self):
        # Only called for exact allowlisted synthetic paths, never headers or arbitrary query data.
        with self.server.lock:
            self.server.counts[self.command + ' ' + self.path] += 1

    def do_GET(self):
        if self.path == '/control/counts':
            with self.server.lock:
                body = json.dumps(dict(self.server.counts), sort_keys=True).encode()
            return self.reply(200, body, 'application/json')
        if self.path in PAGES:
            self.count()
            return self.reply(200, PAGES[self.path], 'text/html; charset=utf-8')
        self.serve_photo()

    def do_HEAD(self):
        self.serve_photo()

    def serve_photo(self):
        if self.path != PHOTO:
            return self.reply(404)
        self.count()
        self.reply(200, PNG, 'image/png')

    def do_POST(self):
        if self.path not in WITNESSES:
            return self.reply(404)
        self.count()
        self.reply(204)

    def reply(self, status, body=b'', mime='text/plain'):
        self.send_response(status)
        self.send_header('Content-Type', mime)
        if status != 204:
            self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        if body and self.command != 'HEAD':
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass  # The test tab may navigate away during a closed request.


@contextmanager
def serve(port=PORT):
    with ThreadingHTTPServer(('127.0.0.1', port), Handler) as server:
        server.lock, server.counts = Lock(), Counter()
        worker = Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            yield server.server_port
        finally:
            server.shutdown()
            worker.join(timeout=3)


if __name__ == '__main__':
    with serve() as port:
        print(f'Image search fixture listening on host loopback {port}', flush=True)
        try:
            Event().wait()
        except KeyboardInterrupt:
            pass

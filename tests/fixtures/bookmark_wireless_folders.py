#!/usr/bin/env python3
"""Give a wireless folder-UI run an identifiable tab and a fresh folder namespace."""
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import re
from secrets import token_hex
from threading import Event, Lock, Thread


PORT = 8031
TAG = re.compile(r'ArenaF[0-9a-f]{10}')


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        state = self.server
        if self.path == f'/folders/{state.tag}':
            with state.lock:
                state.entry_gets += 1
            return self.reply(200, state.page, 'text/html; charset=utf-8')
        if self.path == '/control/counts':
            with state.lock:
                body = json.dumps({'tag': state.tag, 'entry_gets': state.entry_gets}).encode()
            return self.reply(200, body, 'application/json')
        self.reply(404, b'', 'text/plain')

    def reply(self, status, body, mime):
        self.send_response(status)
        self.send_header('Content-Type', mime)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        try:
            if body:
                self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass


@contextmanager
def serve(port=PORT, tag=None):
    if tag is None:
        tag = 'ArenaF' + token_hex(5)
    if not TAG.fullmatch(tag):
        raise ValueError('Expected a synthetic ArenaF tag followed by ten hex digits.')
    with ThreadingHTTPServer(('127.0.0.1', port), Handler) as server:
        server.tag, server.lock, server.entry_gets = tag, Lock(), 0
        server.page = (f'<!doctype html><meta name="viewport" content="width=device-width, initial-scale=1">'
                       f'<title>{tag}-Tab</title><h1>{tag}-Tab</h1><p>Bookmark folder fixture tab.</p>').encode()
        worker = Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            yield server.server_port, tag
        finally:
            server.shutdown()
            worker.join(timeout=3)


if __name__ == '__main__':
    with serve() as (port, tag):
        print(f'Tag: {tag}\nFixture tab: http://127.0.0.1:{port}/folders/{tag}', flush=True)
        try:
            Event().wait()
        except KeyboardInterrupt:
            pass

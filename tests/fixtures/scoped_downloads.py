#!/usr/bin/env python3
"""Two loopback origins for manual Android tests; logs presence, never cookie/header values."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import re
from threading import Thread
from urllib.parse import urlsplit

PRIMARY = 8001
OTHER = 8002
COOKIE = "scoped_download_fixture=synthetic"
ETAG = '"step06-fixture"'
BODY = b"row,message\n" + b"0,step 06 synthetic download\n" * 4096
PAGE = b"""<!doctype html><meta charset=utf-8><title>Scoped download fixture</title>
<h1>Scoped download fixture</h1>
<p>Click a link in the normal or private tab being tested, then check the device and server output.</p>
<ul>
<li><a href="/session/local.csv">Same-origin range download</a></li>
<li><a href="/session/redirect.csv">Redirect to another port (different origin)</a></li>
</ul>
"""


class Fixture(BaseHTTPRequestHandler):
    def handle_request(self):
        port = self.server.server_port
        path = urlsplit(self.path).path
        referrer = self.headers.get("Referer")
        cookie = COOKIE in self.headers.get("Cookie", "").split("; ")
        ranged = self.headers.get("Range")
        print(f"{port} {self.command} {path} fixture_cookie={cookie} "
              f"referer_present={referrer is not None} origin_only={referrer == f'http://127.0.0.1:{PRIMARY}/'} "
              f"range={ranged is not None} conditional={self.headers.get('If-Range') is not None}", flush=True)
        if port == PRIMARY and path == "/start":
            self.respond(200, PAGE, [("Content-Type", "text/html; charset=utf-8"),
                                     ("Set-Cookie", COOKIE + "; Path=/session/; SameSite=Lax")])
        elif port == PRIMARY and path == "/session/redirect.csv":
            self.respond(302, b"", [("Location", f"http://127.0.0.1:{OTHER}/session/foreign.csv")])
        elif (port, path) in {(PRIMARY, "/session/local.csv"), (OTHER, "/session/foreign.csv")}:
            headers = [("Content-Type", "text/csv"), ("ETag", ETAG)]
            if ranged and self.headers.get("If-Range", ETAG) == ETAG:
                match = re.fullmatch(r"bytes=([0-9]+)-([0-9]+)", ranged)
                if match:
                    start, end = map(int, match.groups())
                    if 0 <= start <= end < len(BODY):
                        self.respond(206, BODY[start:end + 1], headers + [("Content-Range", f"bytes {start}-{end}/{len(BODY)}")])
                        return
                self.respond(416, b"", [("Content-Range", f"bytes */{len(BODY)}")])
                return
            self.respond(200, BODY, headers)
        else:
            self.respond(404, b"", [])

    do_GET = handle_request
    do_HEAD = handle_request

    def respond(self, status, body, headers):
        self.send_response(status)
        for name, value in headers:
            self.send_header(name, value)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass  # Expected when a metadata probe closes without reading the body.

    def log_message(self, *_args):
        pass


if __name__ == "__main__":
    with ThreadingHTTPServer(("127.0.0.1", PRIMARY), Fixture) as primary, \
            ThreadingHTTPServer(("127.0.0.1", OTHER), Fixture) as other:
        Thread(target=other.serve_forever, daemon=True).start()
        print(f"Fixture: http://127.0.0.1:{PRIMARY}/start?query=not-a-referer", flush=True)
        try:
            primary.serve_forever()
        except KeyboardInterrupt:
            other.shutdown()

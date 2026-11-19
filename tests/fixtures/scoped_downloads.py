#!/usr/bin/env python3
"""Loopback-only HTTP download fixtures. Log decisions, not request URLs or header values."""
from contextlib import contextmanager
from hashlib import sha256
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import re
from threading import Thread

HOST = "127.0.0.1"
PRIMARY = 8001
OTHER = 8002
COOKIE = "scoped_download_fixture=synthetic"
ETAG = '"step06-fixture"'
BODY = b"row,message\n" + b"0,step 06 synthetic download\n" * 4096
BLOB_PAGE = Path(__file__).with_name("blob_download.html").read_bytes()
PAGE = b"""<!doctype html><meta charset=utf-8><title>Scoped download fixture</title>
<h1>Scoped download fixture</h1>
<p>In a disposable regular tab, long-press each CSV link and choose Download Link with Manager.
Do not navigate to the links: regular WebView requests are not the native downloader.</p>
<ul>
<li><a href="/session/local.csv">Same-origin range download</a></li>
<li><a href="/session/redirect.csv">Redirect to another port (different origin)</a></li>
<li><a href="/session/round-trip.csv">Redirect to another origin and back</a></li>
<li><a href="/blob_download.html">Blob download fixture</a></li>
</ul>
"""
CSV_PATHS = {(True, "/session/local.csv"), (False, "/session/foreign.csv"), (True, "/session/return.csv")}
REDIRECTS = {
    (True, "/session/redirect.csv"): (False, "/session/foreign.csv"),
    (True, "/session/round-trip.csv"): (False, "/session/bounce.csv"),
    (False, "/session/bounce.csv"): (True, "/session/return.csv"),
}


class Fixture(BaseHTTPRequestHandler):
    def handle_request(self):
        primary = self.server.server_port == self.server.primary_port
        path = self.path.partition("?")[0]
        route = (primary, path)
        if route == (True, "/start"):
            self.respond(path, 200, PAGE, [("Content-Type", "text/html; charset=utf-8"),
                                           ("Set-Cookie", COOKIE + "; Path=/session/; SameSite=Lax")])
        elif route == (True, "/blob_download.html"):
            self.respond(path, 200, BLOB_PAGE, [("Content-Type", "text/html; charset=utf-8")])
        elif route in REDIRECTS:
            target_primary, target = REDIRECTS[route]
            port = self.server.primary_port if target_primary else self.server.other_port
            self.respond(path, 302, b"", [("Location", f"http://{HOST}:{port}{target}")])
        elif route in CSV_PATHS:
            self.csv_response(path)
        else:
            self.respond("unmatched", 404, b"", [])

    do_GET = handle_request
    do_HEAD = handle_request

    def csv_response(self, path):
        headers = [("Content-Type", "text/csv"), ("ETag", ETAG), ("Accept-Ranges", "bytes")]
        ranged = self.headers.get("Range")
        if ranged is not None and self.headers.get("If-Range", ETAG) == ETAG:
            match = re.fullmatch(r"bytes=([0-9]{1,9})-([0-9]{1,9})", ranged)
            if match:
                start, end = map(int, match.groups())
                if 0 <= start <= end < len(BODY):
                    self.respond(path, 206, BODY[start:end + 1],
                                 headers + [("Content-Range", f"bytes {start}-{end}/{len(BODY)}")])
                    return
            self.respond(path, 416, b"", headers + [("Content-Range", f"bytes */{len(BODY)}")])
            return
        self.respond(path, 200, BODY, headers)

    def respond(self, route, status, body, headers):
        cookies = self.headers.get_all("Cookie", [])
        fixture_cookie = any(part.strip() == COOKIE for value in cookies for part in value.split(";"))
        referers = self.headers.get_all("Referer", [])
        origin_only = referers == [f"http://{HOST}:{self.server.primary_port}/"]
        ranged = self.headers.get("Range")
        print(f"{self.server.server_port} {self.command} {route} status={status} "
              f"fixture_cookie={fixture_cookie} cookie_present={bool(cookies)} "
              f"referer_present={bool(referers)} origin_only={origin_only} "
              f"range={ranged is not None} probe={ranged == 'bytes=0-0'} "
              f"conditional={self.headers.get('If-Range') is not None}", flush=True)
        self.send_response(status)
        for name, value in headers:
            self.send_header(name, value)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass  # A probe may close without reading the response body.

    def log_message(self, *_args):
        pass


@contextmanager
def loopback_servers(primary_port=PRIMARY, other_port=OTHER):
    with ThreadingHTTPServer((HOST, primary_port), Fixture) as primary, \
            ThreadingHTTPServer((HOST, other_port), Fixture) as other:
        primary.primary_port = other.primary_port = primary.server_port
        primary.other_port = other.other_port = other.server_port
        yield primary, other


def main():
    with loopback_servers() as (primary, other):
        thread = Thread(target=other.serve_forever, daemon=True)
        thread.start()
        print(f"Fixture: http://{HOST}:{PRIMARY}/start?query=not-a-referer", flush=True)
        print(f"Expected CSV: {len(BODY)} bytes, SHA-256 {sha256(BODY).hexdigest()}", flush=True)
        try:
            primary.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            other.shutdown()
            thread.join()


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Two paced, byte-verifiable HTTP jobs for the disposable Android restart check."""
from hashlib import sha256, shake_256
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import re
import time

HOST = "127.0.0.1"
PORT = 8019
LENGTH = 4 * 1024 * 1024
CHUNK = 16 * 1024
DELAY = 0.25  # About 64 seconds per uninterrupted file with one connection.
COOKIE = "download_recovery_fixture=synthetic"
CASES = ("manual-pause.bin", "interrupted.bin")
PAYLOAD = {name: shake_256(f"browser-download-recovery-v1:{name}".encode("ascii")).digest(LENGTH) for name in CASES}
ETAGS = {name: f'"download-recovery-v1-{name}"' for name in CASES}
PAGE = b"""<!doctype html><meta charset=utf-8><title>Download recovery fixture</title>
<h1>Download recovery fixture</h1>
<p>Use a disposable regular tab. Long-press each link and choose Download Link with Manager.
Select one connection, separate test filenames and app-owned storage.</p>
<ul><li><a href="/jobs/manual-pause.bin">Manually paused job</a></li>
<li><a href="/jobs/interrupted.bin">Interrupted active job</a></li></ul>
"""


class RecoveryServer(ThreadingHTTPServer):
    def __init__(self, port=PORT, delay=DELAY):
        self.delay = delay
        super().__init__((HOST, port), Fixture)


class Fixture(BaseHTTPRequestHandler):
    def do_HEAD(self):
        self.handle_request(False)

    def do_GET(self):
        self.handle_request(True)

    def log_message(self, *_args):
        pass

    def handle_request(self, send_body):
        path = self.path.partition("?")[0]
        if path == "/start":
            self.write_response(200, PAGE, send_body, [("Content-Type", "text/html; charset=utf-8"),
                                                        ("Set-Cookie", COOKIE + "; Path=/jobs/; SameSite=Lax")])
            return
        name = path.removeprefix("/jobs/") if path.startswith("/jobs/") else None
        if name not in PAYLOAD:
            self.write_response(404, b"", send_body, [])
            return

        ranged = self.headers.get("Range")
        if_range = self.headers.get("If-Range")
        validator_match = if_range == ETAGS[name]
        start, end, status = 0, LENGTH - 1, 200
        headers = [("Content-Type", "application/octet-stream"), ("ETag", ETAGS[name]), ("Accept-Ranges", "bytes"),
                   ("Content-Disposition", f'attachment; filename="{name}"')]
        if ranged is not None and (if_range is None or validator_match):
            match = re.fullmatch(r"bytes=([0-9]{1,9})-([0-9]{0,9})", ranged)
            if match:
                start = int(match[1])
                end = min(end, int(match[2])) if match[2] else end
            if match and 0 <= start <= end < LENGTH:
                status = 206
                headers.append(("Content-Range", f"bytes {start}-{end}/{LENGTH}"))
            else:
                start, end, status = None, None, 416
                headers.append(("Content-Range", f"bytes */{LENGTH}"))

        cookies = self.headers.get_all("Cookie", [])
        marker = any(part.strip() == COOKIE for value in cookies for part in value.split(";"))
        referers = self.headers.get_all("Referer", [])
        origin = f"http://{HOST}:{self.server.server_port}/"
        probe = self.command == "GET" and status == 206 and start == end == 0 and if_range is None
        # Report decisions and served offsets, never URLs, arbitrary paths or header values.
        print(f"{self.command} {name} status={status} start={start} end={end} probe={probe} "
              f"conditional={if_range is not None} validator_match={validator_match} "
              f"fixture_cookie={marker} cookie_present={bool(cookies)} referer_present={bool(referers)} "
              f"origin_only={referers == [origin]}", flush=True)
        body = memoryview(PAYLOAD[name])[start:end + 1] if status != 416 else b""
        paced = send_body and self.server.delay > 0 and not probe and status in (200, 206)
        self.write_response(status, body, send_body, headers, paced=paced, name=name)

    def write_response(self, status, body, send_body, headers, *, paced=False, name=None):
        self.send_response(status)
        for key, value in headers:
            self.send_header(key, value)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if not send_body:
            return
        sent = 0
        stopped = False
        try:
            for offset in range(0, len(body), CHUNK):
                sent += self.wfile.write(body[offset:offset + CHUNK])
                if paced and sent < len(body):
                    time.sleep(self.server.delay)
        except (BrokenPipeError, ConnectionResetError):
            stopped = True
        finally:
            if paced:
                print(f"body_end {name} sent={sent} peer_closed={stopped}", flush=True)


def main():
    with RecoveryServer() as server:
        print(f"Open http://{HOST}:{PORT}/start on the disposable test device.", flush=True)
        for name in CASES:
            print(f"{name}: {LENGTH} bytes, SHA-256 {sha256(PAYLOAD[name]).hexdigest()}", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()

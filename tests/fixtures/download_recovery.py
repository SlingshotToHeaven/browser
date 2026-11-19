#!/usr/bin/env python3
"""Paced, byte-verifiable HTTP jobs for disposable Android download checks."""
from argparse import ArgumentParser
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
ROTATING_JOB = CASES[0]
REPLACEMENT = shake_256(f"browser-download-recovery-v2:{ROTATING_JOB}".encode("ascii")).digest(LENGTH)
REPLACEMENT_ETAG = f'"download-recovery-v2-{ROTATING_JOB}"'
PAGE = b"""<!doctype html><meta charset=utf-8><title>Download recovery fixture</title>
<h1>Download recovery fixture</h1>
<p>Use a disposable regular tab. Long-press each link and choose Download Link with Manager.
Select one connection, separate test filenames and app-owned storage.</p>
<ul><li><a href="/jobs/manual-pause.bin">Manually paused job</a></li>
<li><a href="/jobs/interrupted.bin">Interrupted active job</a></li></ul>
"""


class RecoveryServer(ThreadingHTTPServer):
    def __init__(self, port=PORT, delay=DELAY, *, replace_paused=False):
        self.delay = delay
        self.replace_paused = replace_paused
        super().__init__((HOST, port), Fixture)

    def representation(self, name):
        if self.replace_paused and name == ROTATING_JOB:
            return 2, REPLACEMENT, REPLACEMENT_ETAG
        return 1, PAYLOAD[name], ETAGS[name]


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

        version, payload, etag = self.server.representation(name)
        ranged = self.headers.get("Range")
        match = re.fullmatch(r"bytes=([0-9]{1,9})-([0-9]{0,9})", ranged or "")
        requested_start = int(match[1]) if match else None
        if_range = self.headers.get("If-Range")
        validator_match = if_range == etag
        if if_range is None:
            if_range_version = "none"
        elif if_range == ETAGS[name]:
            if_range_version = 1
        elif name == ROTATING_JOB and if_range == REPLACEMENT_ETAG:
            if_range_version = 2
        else:
            if_range_version = "other"
        length = len(payload)
        start, end, status = 0, length - 1, 200
        headers = [("Content-Type", "application/octet-stream"), ("ETag", etag), ("Accept-Ranges", "bytes"),
                   ("Content-Disposition", f'attachment; filename="{name}"')]
        if ranged is not None and (if_range is None or validator_match):
            if match:
                start = requested_start
                end = min(end, int(match[2])) if match[2] else end
            if match and 0 <= start <= end < length:
                status = 206
                headers.append(("Content-Range", f"bytes {start}-{end}/{length}"))
            else:
                start, end, status = None, None, 416
                headers.append(("Content-Range", f"bytes */{length}"))

        cookies = self.headers.get_all("Cookie", [])
        marker = any(part.strip() == COOKIE for value in cookies for part in value.split(";"))
        referers = self.headers.get_all("Referer", [])
        origin = f"http://{HOST}:{self.server.server_port}/"
        probe = self.command == "GET" and status == 206 and start == end == 0 and if_range is None
        # Report only bounded request offsets and fixture labels, not URLs or raw headers.
        print(f"{self.command} {name} status={status} start={start} end={end} probe={probe} "
              f"conditional={if_range is not None} validator_match={validator_match} "
              f"fixture_cookie={marker} cookie_present={bool(cookies)} referer_present={bool(referers)} "
              f"origin_only={referers == [origin]} version={version} range_present={ranged is not None} "
              f"requested_start={requested_start} if_range_version={if_range_version}", flush=True)
        body = memoryview(payload)[start:end + 1] if status != 416 else b""
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
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("--replace-paused", action="store_true",
                        help=f"Serve version 2 of {ROTATING_JOB} at the same URL; restart the host fixture to switch.")
    args = parser.parse_args()
    with RecoveryServer(replace_paused=args.replace_paused) as server:
        print(f"Open http://{HOST}:{PORT}/start on the disposable test device.", flush=True)
        for name in CASES:
            version, data, _ = server.representation(name)
            print(f"{name} version {version}: {len(data)} bytes, SHA-256 {sha256(data).hexdigest()}", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Matched disposable download history seeds and a paced loopback transfer for PERF-09."""
import argparse
from contextlib import closing
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import re
import sqlite3
import sys
import time

# Reuse the production DDL extraction used by the SQLite regression tests.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from test_download_queue_sql import create

PORT = 8017
LENGTH = 12 * 1024 * 1024
BLOCK = b"P" * (16 * 1024)
ETAG = '"perf20-fixed-fixture"'


def seed(path, size, version):
    if not 0 <= size <= 100_000:
        raise ValueError("choose 0 to 100,000 synthetic rows")
    # Never overwrite any existing database, even one from a previous test.
    path.touch(exist_ok=False)
    with closing(sqlite3.connect(path)) as db, db:
        create(db, version)
        columns = ("id,url,file_name,dest_path,destination_key,status,total_bytes,downloaded_bytes,created_time"
                   + (",name_search,name_order" if version == 8 else ""))
        values = ",".join("?" for _ in columns.split(","))

        def rows():
            for i in range(size):
                name = f"perf20-group{i % 100:02d}-{i:06d}.{('ZIP', 'mp4', 'TXT', 'pdf', 'ogg')[i % 5]}"
                destination = "/perf20-synthetic-not-on-device/" + name
                count = 1024 * (1 + i % 128)
                row = (f"ff202020-0000-4000-a000-{i:012x}", "https://example.test/synthetic", name,
                       destination, destination.lower(), "COMPLETED", count, count, 1_700_000_000_000 + i // 4)
                yield row + (name.lower(), name.lower()) if version == 8 else row

        db.executemany(f"INSERT INTO downloads ({columns}) VALUES ({values})", rows())
        db.execute(f"PRAGMA user_version={version}")
    print(f"{path.name}: schema {version}, {size} completed synthetic rows (no real files)")


class Fixture(BaseHTTPRequestHandler):
    def do_HEAD(self):
        self.transfer(False)

    def do_GET(self):
        self.transfer(True)

    def log_message(self, *_args):
        pass

    def transfer(self, body):
        if self.path != "/perf20-transfer.bin":
            self.send_error(404)
            return
        start, end, code = 0, LENGTH - 1, 200
        byte_range = self.headers.get("Range")
        if byte_range and self.headers.get("If-Range", ETAG) == ETAG:
            match = re.fullmatch(r"bytes=([0-9]{1,9})-([0-9]{0,9})", byte_range)
            if not match:
                self.send_error(416)
                return
            start = int(match[1])
            end = min(int(match[2]), end) if match[2] else end
            if end < start:
                self.send_error(416)
                return
            code = 206
        self.send_response(code)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Disposition", 'attachment; filename="perf20-transfer.bin"')
        self.send_header("ETag", ETAG)
        self.send_header("Accept-Ranges", "bytes")
        remaining = end - start + 1
        self.send_header("Content-Length", str(remaining))
        if code == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{LENGTH}")
        self.end_headers()
        if not body:
            return
        try:
            while remaining > 0:
                size = min(remaining, len(BLOCK))
                self.wfile.write(BLOCK[:size])
                remaining -= size
                if remaining:
                    time.sleep(0.12)
        except (BrokenPipeError, ConnectionResetError):
            pass  # pause/cancel (or a metadata-only probe)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    command = commands.add_parser("seed")
    command.add_argument("database", type=Path)
    command.add_argument("--rows", type=int, default=0)
    command.add_argument("--version", type=int, choices=(7, 8), default=7)
    commands.add_parser("serve")
    args = parser.parse_args()
    if args.command == "seed":
        seed(args.database, args.rows, args.version)
    else:
        with ThreadingHTTPServer(("127.0.0.1", PORT), Fixture) as server:
            print(f"Serving synthetic paced downloads at http://127.0.0.1:{PORT}/perf20-transfer.bin", flush=True)
            server.serve_forever()


if __name__ == "__main__":
    main()

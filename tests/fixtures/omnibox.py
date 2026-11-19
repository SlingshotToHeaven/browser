#!/usr/bin/env python3
"""Synthetic SQLite seeds, host work counts, and a local PERF-07 trace pilot page."""
import argparse
from contextlib import closing
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
from pathlib import Path
import re
import sqlite3
import time


JAVA = (Path(__file__).resolve().parents[2] /
        "app/src/main/java/com/privacy/browser/OmniboxStore.java").read_text(encoding="utf-8")


def java_constant(name):
    # These SQL constants are concatenations of literals and named constants, not Java code.
    expression = re.search(r"\b(?:int|String) " + name + r"\s*=\s*(.*?);", JAVA, re.DOTALL).group(1)
    parts = re.findall(r'"(?:[^"\\]|\\.)*"|\w+|\+', expression)
    assert parts and all(part == "+" for part in parts[1::2]), expression
    return "".join(json.loads(part) if part.startswith('"') else
                   part if part.isdecimal() else java_constant(part) for part in parts[::2])


WINDOW = int(java_constant("WINDOW_ROWS"))
QUERY = {"history": java_constant("HISTORY_SQL"), "bookmarks": java_constant("BOOKMARK_SQL")}
# The previous omnibox issued these list queries (not including bookmark folder child counts).
OLD_QUERY = {
    "history": "SELECT * FROM history WHERE title LIKE ? OR url LIKE ? ORDER BY visited_at DESC LIMIT 3",
    "bookmarks": "SELECT * FROM bookmarks WHERE title LIKE ? OR url LIKE ? ORDER BY is_folder DESC, created_at DESC LIMIT 3",
}
# History v1 and bookmarks v3. Step 18 introduces no schema version bump or index build.
SCHEMA = {
    "history": """
        CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL,
            url TEXT NOT NULL, visited_at INTEGER NOT NULL);
        CREATE INDEX idx_history_visited_at ON history (visited_at DESC);
        PRAGMA user_version=1;
    """,
    "bookmarks": """
        CREATE TABLE bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL,
            url TEXT, created_at INTEGER NOT NULL, parent_folder_id INTEGER NOT NULL DEFAULT 0,
            is_folder INTEGER NOT NULL DEFAULT 0);
        CREATE INDEX bookmarks_parent_idx ON bookmarks (parent_folder_id);
        PRAGMA user_version=3;
    """,
}
TERMS = {"recent": "perf18recent", "common": "perf18common", "miss": "perf18absent"}
TRACE_HOST, TRACE_PORT = "127.0.0.1", 8020
TRACE_TERM = "perf18tracehit"
TRACE_PATH = "/" + TRACE_TERM
TRACE_PAGE = (f'<!doctype html><meta charset="utf-8"><title>Fixture {TRACE_TERM}</title>'
              f'<h1>Fixture {TRACE_TERM}</h1><p>Local address-bar trace pilot; no external resources.</p>').encode("ascii")


class TracePageHandler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass  # Don't echo arbitrary paths or client data.

    def do_GET(self):
        found = self.path == TRACE_PATH
        data = TRACE_PAGE if found else b""
        self.send_response(200 if found else 404)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def entries(store, size):
    for i in range(1, size + 1):
        folder = store == "bookmarks" and i % 16 == 0 and i < size - 8
        word = "perf18common" if i != 1 else "perf18archive"
        if i > size - 4:
            word = "perf18recent"
        if i == size - 6:
            word = r"route_50%\quoted's"
        # One duplicated URL across both stores, with unique titles. No URL is routable.
        url = "https://shared.perf18.invalid/final" if i == size else f"https://{store}.perf18.invalid/{i:06}"
        row = (f"Fixture {word} {store} {i:06}", "" if folder else url, 1720000000000 + i)
        yield row + (0, int(folder)) if store == "bookmarks" else row


def seed(db, store, size):
    db.executescript(SCHEMA[store])
    insert = ("INSERT INTO history (title,url,visited_at) VALUES (?,?,?)" if store == "history" else
              "INSERT INTO bookmarks (title,url,created_at,parent_folder_id,is_folder) VALUES (?,?,?,?,?)")
    with db:
        db.executemany(insert, entries(store, size))


def args_for(term, literal=True):
    if literal:
        term = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    return ("%" + term + "%",) * 2


def plan(db, query, args):
    return db.execute("EXPLAIN QUERY PLAN " + query, args).fetchall()


def measured_steps(db, query, args):
    steps = 0

    def tick():
        nonlocal steps
        steps += 1
        return 0

    db.set_progress_handler(tick, 1)
    try:
        rows = db.execute(query, args).fetchall()
    finally:
        db.set_progress_handler(None, 0)
    return steps, rows


def benchmark(store, size, repeats):
    with closing(sqlite3.connect(":memory:")) as db:
        seed(db, store, size)
        print(f"\n{store}: {size:,} rows")
        for label, term in TERMS.items():
            for variant, query in (("before", OLD_QUERY[store]), ("after ", QUERY[store])):
                args = args_for(term, literal=variant != "before")
                steps, rows = measured_steps(db, query, args)
                samples = []
                for _ in range(repeats):
                    start = time.perf_counter_ns()
                    db.execute(query, args).fetchall()
                    samples.append((time.perf_counter_ns() - start) / 1e6)
                samples.sort()
                p50 = samples[math.ceil(len(samples) * .5) - 1]
                p95 = samples[math.ceil(len(samples) * .95) - 1]
                print(f"  {label:6} {variant}: {len(rows)} rows, {steps:7,} VM ops, {p50:.3f}/{p95:.3f} ms p50/p95")
                for node in plan(db, query, args):
                    print(f"      {node[0]:>2}/{node[1]:>2} {node[3]}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    seed_parser = commands.add_parser("seed", help="write disposable debug-profile history.db and bookmarks.db")
    seed_parser.add_argument("directory", type=Path)
    bench_parser = commands.add_parser("benchmark", help="print plans, work counts and warm host-only timings")
    bench_parser.add_argument("--repeats", type=int, default=100)
    commands.add_parser("serve-trace-pilot", help="serve one inert page for a test-only address-bar hit")
    for command in (seed_parser, bench_parser):
        command.add_argument("--history", type=int, default=50_000)
        command.add_argument("--bookmarks", type=int, default=20_000)
    args = parser.parse_args()
    if args.command == "serve-trace-pilot":
        with ThreadingHTTPServer((TRACE_HOST, TRACE_PORT), TracePageHandler) as server:
            print(f"Trace pilot page: http://{TRACE_HOST}:{TRACE_PORT}{TRACE_PATH}", flush=True)
            try:
                server.serve_forever()
            except KeyboardInterrupt:
                pass
        return

    sizes = {"history": args.history, "bookmarks": args.bookmarks}
    if min(sizes.values()) < 10:
        parser.error("use at least ten rows per store")
    if args.command == "benchmark":
        if args.repeats < 1:
            parser.error("repeats must be positive")
        print(f"Host SQLite {sqlite3.sqlite_version}; in-memory, warm, {args.repeats} repetitions; not Android timings")
        for store, size in sizes.items():
            benchmark(store, size, args.repeats)
    else:
        args.directory.mkdir(parents=True, exist_ok=True)
        paths = {store: args.directory / f"{store}.db" for store in sizes}
        if any(path.exists() for path in paths.values()):
            parser.error("refusing to overwrite a seed; use a new directory")
        for store, path in paths.items():
            with closing(sqlite3.connect(path)) as db:
                seed(db, store, sizes[store])
                assert db.execute("PRAGMA integrity_check").fetchone() == ("ok",)
            print(f"{path}: {sizes[store]:,} synthetic rows")


if __name__ == "__main__":
    main()

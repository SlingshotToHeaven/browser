#!/usr/bin/env python3
"""Installed v7 -> v8 downloads and actual Open/Share on a disposable CI AVD."""
import argparse
from contextlib import closing, contextmanager
from dataclasses import dataclass
from hashlib import file_digest, sha256
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import sys
from tempfile import TemporaryDirectory
from threading import Event, Lock, Thread
import time
from uuid import NAMESPACE_URL, uuid5

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from tools.check_android_download_publication import (
    ACTIVITY, DB, DOWNLOADS, PACKAGE, CheckFailed, Emulator, bound_path, find_view,
    prepare, ready_to_enqueue, require, snapshot_bytes, until,
)

RECIPIENT = "com.browser.fixture.receiver"
RECIPIENT_LABEL = "Fixture Receiver"
PROBE = RECIPIENT + ".PROBE"
AUTHORITY = PACKAGE + ".completed-downloads"
SYSTEM_RESOLVERS = {"android", "com.android.intentresolver", "com.google.android.intentresolver"}
MARKER = "files/download-upgrade.marker"
MARKER_BYTES = b"Disposable v7 download-upgrade profile.\n"
SHA = r"[a-f0-9]{64}"
UUID = r"[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"
LEGACY = {"cookies": "fixture_legacy_cookie=never-send", "user_agent": "FixtureLegacyAgent/1",
          "referrer": "http://legacy-download-fixture.invalid/never-send"}
STATES = {"queued": "PENDING", "active": "RUNNING", "paused": "PAUSED"}
STAGE_RE = r"download-upgrade-(?:active|paused)\.part"


@dataclass(frozen=True)
class Job:
    name: str
    body: bytes
    offset: int
    etag: str

    @property
    def digest(self):
        return sha256(self.body).hexdigest()


def job(role, length, offset=0):
    line = f"Synthetic {role} fixture; not user data.\n".encode()
    body = (line * (length // len(line) + 1))[:length]
    return Job(role.title() + ".txt", body, offset, f'"upgrade-{role}-one"')


JOBS = {"history": job("history", 12_271), "queued": job("queued", 51_889),
        "active": job("active", 213_557, 73_421), "paused": job("paused", 174_719, 51_913)}
ROLES = {entry.name: role for role, entry in JOBS.items()}
ALL_JOBS = set(JOBS)
AUTO = {"history": ["probe", "range"], "queued": ["probe", "range"], "active": ["range"], "paused": []}


def wire_reply(entry, method, headers):
    """Permit only the fixture probe or exact conditional suffix. Never log header values."""
    if (any(name in headers for name in ("Cookie", "Authorization", "Proxy-Authorization", "Referer"))
            or any(LEGACY["user_agent"] in agent for agent in headers.get_all("User-Agent", []))):
        return "credentials", None
    if method != "GET" or entry is None:
        return "unexpected", None
    interval = headers.get_all("Range", [])
    validator = headers.get_all("If-Range", [])
    end = len(entry.body) - 1
    if entry.offset == 0 and interval == ["bytes=0-0"] and not validator:
        return "probe", (0, 0)
    if interval == [f"bytes={entry.offset}-{end}"] and validator == [entry.etag]:
        return "range", (entry.offset, end)
    return "wrong-range", None


class UpgradeFixture(ThreadingHTTPServer):
    def __init__(self):
        super().__init__(("127.0.0.1", 0), FixtureRequest)
        self.gates = {role: Event() for role in ("paused", "active")}
        self._events = []
        self._lock = Lock()

    def url(self, role):
        return f"http://127.0.0.1:{self.server_port}/jobs/{JOBS[role].name}"

    def record(self, role, event):
        with self._lock:
            if len(self._events) < 16:
                self._events.append((role, event))
                return True
            if len(self._events) == 16:
                self._events.append(("unknown", "overflow"))
            return False

    def events(self):
        with self._lock:
            return self._events[:]


class FixtureRequest(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def do_GET(self):
        role = ROLES.get(self.path.removeprefix("/jobs/")) if self.path.startswith("/jobs/") else None
        entry = JOBS.get(role)
        event, interval = wire_reply(entry, self.command, self.headers)
        if not self.server.record(role or "unknown", event) or interval is None:
            self.send_error(400)
            return
        # Hold the resumed suffix briefly, so a stable DB/file observation can be
        # made after the request but before any bytes of that response arrive.
        gate = self.server.gates.get(role)
        if gate is not None and not gate.wait(16):
            self.server.record(role, "gate-timeout")
            self.send_error(503)
            return
        start, end = interval
        data = entry.body[start:end + 1]
        self.send_response(206)
        self.send_header("Content-Type", "text/plain")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Content-Range", f"bytes {start}-{end}/{len(entry.body)}")
        self.send_header("ETag", entry.etag)
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            # The independent file digest and completion checks still have to pass.
            pass

    def do_HEAD(self):
        self.do_GET()


@contextmanager
def fixture():
    with UpgradeFixture() as server:
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            yield server
        finally:
            for gate in server.gates.values():
                gate.set()
            server.shutdown()
            thread.join()


def network(server, expected):
    by_role = {role: [] for role in JOBS}
    for role, event in server.events():
        require(role in by_role and event in ("probe", "range"), f"Unexpected fixture HTTP event: {role}/{event}")
        by_role[role].append(event)
    require(by_role == expected, f"Wrong fixture HTTP sequence: {by_role}")


def wait_range(server, role):
    def arrived():
        events = server.events()
        require(all(key in JOBS and event in ("probe", "range") for key, event in events),
                f"Unexpected fixture HTTP events: {events}")
        return (role, "range") in events
    until(arrived, f"exact conditional Range from {JOBS[role].offset} for {role}", seconds=25)


@dataclass
class Profile:
    schema: int
    indexes: set
    rows: dict
    segments: dict


def read_profile(db):
    db.row_factory = sqlite3.Row
    schema = db.execute("PRAGMA user_version").fetchone()[0]
    indexes = {row[1] for row in db.execute("PRAGMA index_list(downloads)")}
    rows = {}
    for row in db.execute("SELECT * FROM downloads"):
        role = ROLES.get(row["file_name"])
        require(role is not None and role not in rows and re.fullmatch(UUID, row["id"]), "Unrecognized row in fixture DB")
        rows[role] = dict(row)
    roles_by_id = {row["id"]: role for role, row in rows.items()}
    segments = {role: [] for role in rows}
    for row in db.execute("SELECT * FROM download_segments ORDER BY segment_index"):
        require(row["task_id"] in roles_by_id, "Unexpected orphan segment in fixture DB")
        segments[roles_by_id[row["task_id"]]].append(tuple(row[key] for key in (
            "segment_index", "start_byte", "end_byte", "current_byte", "status")))
    return Profile(schema, indexes, rows, segments)


@contextmanager
def db_copy(images, work, writable=False):
    main, wal = images
    require(main.startswith(b"SQLite format 3\0") and len(main) + len(wal) < 512 * 1024,
            "Missing or oversized emulator SQLite fixture")
    with TemporaryDirectory(prefix="upgrade-sqlite-", dir=work) as temp:
        path = Path(temp) / "downloads.db"
        path.write_bytes(main)
        if wal:
            path.with_name("downloads.db-wal").write_bytes(wal)
        uri = path.as_uri() + ("?mode=rw" if writable else "?mode=ro")
        with closing(sqlite3.connect(uri, uri=True)) as db:
            yield db, path


def snapshot(android, work):
    with db_copy(snapshot_bytes(android), work) as (db, _path):
        return read_profile(db)


def stage_name(role):
    return f"download-upgrade-{role}.part"


def fixture_id(value):
    return str(uuid5(NAMESPACE_URL, "android-download-upgrade-fixture:" + value))


def seed_rows(db, profile, origin):
    require(profile.schema == 7 and set(profile.rows) == {"history"}, "Only the app-created v7 history may be seeded")
    history = profile.rows["history"]
    dest_parent = Path(history["dest_path"]).parent
    key_parent = Path(history["destination_key"]).parent
    with db:
        for i, (role, status) in enumerate(STATES.items(), start=1):
            entry = JOBS[role]
            url = f"{origin}/jobs/{entry.name}"
            row = dict(LEGACY, id=fixture_id(role), file_name=entry.name, status=status, url=url, original_url=url,
                       created_time=history["created_time"] + i, finished_time=0, mime_type="text/plain",
                       dest_path=str(dest_parent / entry.name), destination_key=str(key_parent / entry.name.lower()),
                       expected_sha256=entry.digest, downloaded_bytes=entry.offset, num_segments=1, wifi_only=0)
            if entry.offset:
                row.update(total_bytes=len(entry.body), temp_path=str(dest_parent / stage_name(role)),
                           etag=entry.etag, verified_range_url=url, supports_range=1, attempt_token=fixture_id(role + "-old"))
            db.execute(f"INSERT INTO downloads ({','.join(row)}) VALUES ({','.join('?' for _ in row)})", tuple(row.values()))
            if entry.offset:
                db.execute("INSERT INTO download_segments (task_id,segment_index,start_byte,end_byte,current_byte,status)"
                           " VALUES (?,0,0,?,?,?)", (row["id"], len(entry.body) - 1, entry.offset, status))


def install_seed(android, work, directory, server, history):
    require(android.pid() is None, "Seed writes require a stopped old browser")
    with db_copy(snapshot_bytes(android), work, writable=True) as (db, path):
        require(db.execute("PRAGMA journal_mode=DELETE").fetchone()[0] == "delete", "Could not checkpoint the v7 fixture copy")
        require(read_profile(db) == history, "v7 data changed before seeding")
        seed_rows(db, history, f"http://127.0.0.1:{server.server_port}")
        seeded = read_profile(db)
        data = path.read_bytes()
    for role in ("active", "paused"):
        stage = bound_path(android, directory, seeded.rows[role]["temp_path"], STAGE_RE)
        android.write_new(stage, JOBS[role].body[:JOBS[role].offset])
    android.write_new(MARKER, MARKER_BYTES)
    android.write_new(DB + ".seed", data)
    android.app("rm", "-f", DB + "-wal", DB + "-shm")  # no app process or hot journal
    android.app("mv", DB + ".seed", DB)
    require(snapshot(android, work) == seeded, "Stopped Android v7 profile did not receive the fixture correctly")
    return seeded


def require_v8(profile, roles):
    expected_indexes = {"downloads_" + name for name in ("destination_key", "status_date", "date", "size", "name",
                        "completed_size", "completed_name", "incomplete_name", "unkeyed_paths")}
    require(profile.schema == 8 and expected_indexes <= profile.indexes and set(profile.rows) == roles,
            "Android didn't migrate the existing fixture and history indexes to SQLite v8")
    for role, row in profile.rows.items():
        require(row["name_search"] == row["name_order"] == JOBS[role].name.lower(), f"Wrong v8 name backfill: {role}")


def same_row(after, before, role):
    require({k: v for k, v in after.rows[role].items() if k not in ("name_search", "name_order")} == before.rows[role]
            and after.segments[role] == before.segments[role], f"Migration/recovery changed the saved {role} row or segments")


def path_for(android, directory, profile, role):
    row = profile.rows[role]
    return bound_path(android, directory, row["dest_path"], re.escape(JOBS[role].name))


def completed(android, directory, profile, role, seed=None):
    row = profile.rows[role]
    entry = JOBS[role]
    require(row["status"] == "COMPLETED" and re.fullmatch(UUID, row["attempt_token"] or "")
            and row["finished_time"] > 0 and row["temp_path"] is None and row["error_message"] is None
            and row["total_bytes"] == row["downloaded_bytes"] == len(entry.body), f"No committed completion for {role}")
    if seed is not None:
        require(all(row[key] == seed[key] for key in ("id", "created_time", "file_name", "url", "dest_path", "destination_key"))
                and row["attempt_token"] != seed["attempt_token"], f"{role} completed as a different row or with a stale token")
    path = path_for(android, directory, profile, role)
    result = android.file(path)
    require(result == (row["published_identity"], entry.digest), f"Wrong bytes or publication identity: {role}")
    return result


def check_prefix(android, directory, row, role):
    entry = JOBS[role]
    stage = bound_path(android, directory, row["temp_path"], re.escape(stage_name(role)))
    identity, digest = android.file(stage)
    require(int(identity.rsplit(":", 1)[-1]) == row["downloaded_bytes"] == entry.offset
            and digest == sha256(entry.body[:entry.offset]).hexdigest(), f"Changed staged checkpoint for {role}")
    return identity


def held_checkpoint(android, directory, profile, seed, role, identity):
    row, old = profile.rows[role], seed.rows[role]
    require(row["status"] == "RUNNING" and row["attempt_token"] and row["attempt_token"] != old["attempt_token"]
            and all(row[key] == old[key] for key in ("id", "temp_path", "etag", "verified_range_url", "downloaded_bytes")),
            f"{role} did not recover the old checkpoint under a new attempt")
    require([s[:4] for s in profile.segments[role]] == [s[:4] for s in seed.segments[role]]
            and check_prefix(android, directory, row, role) == identity, f"{role} did not retain its staged bytes and offset")


def find_card(root, role, target="card_text_filename", badge=None):
    name = JOBS[role].name
    filename = find_view(root, "card_text_filename", name)
    if filename is None:
        return None
    parents = {child: parent for parent in root.iter() for child in parent}
    row = parents.get(filename)
    # The compressed hierarchy may drop a non-accessible layout. Find a common
    # ancestor, never accept another card's badge or action under the list root.
    while row is not None:
        names = [n for n in row.iter("node") if n.get("resource-id", "").endswith(":id/card_text_filename")]
        if len(names) != 1:
            return None
        control = find_view(row, target, name if target == "card_text_filename" else None)
        if control is not None and (badge is None or find_view(row, "card_text_status_badge", badge) is not None):
            return control
        row = parents.get(row)
    return None


def card(android, role, target="card_text_filename", badge=None):
    def visible():
        root = android.hierarchy()
        return None if root is None else find_card(root, role, target, badge)
    return until(visible, f"{role} Downloads card: {badge or 'any status'}, {target}")


def action(android, role, label):
    android.tap_view(card(android, role, badge="COMPLETED"), long=True)
    android.tap("action_item_text", label)


def apk_info(sdk, apk, expected):
    tools = sdk / "build-tools/34.0.0"
    badging = subprocess.check_output([tools / "aapt2", "dump", "badging", apk], text=True, timeout=30)
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)'", badging, re.M)
    require(match and match[1] == expected, f"Unexpected package in {apk.name}")
    certs = subprocess.check_output([tools / "apksigner", "verify", "--print-certs", apk], text=True, timeout=30)
    digests = re.findall(rf"^Signer #\d+ certificate SHA-256 digest: ({SHA})$", certs, re.M)
    require(len(digests) == 1, f"Expected one verified APK signer for {expected}")
    with apk.open("rb") as stream:
        return {"version_code": int(match[2]), "apk_sha256": file_digest(stream, "sha256").hexdigest(), "signer": digests[0]}


def installed_apk_path(response, package):
    # Android 14 uses /data/app/~~<nonce>/<package>-<nonce>/base.apk.
    nonce = r"[A-Za-z0-9_-]+={0,2}"
    match = re.fullmatch(rf"package:(/data/app/~~{nonce}/{re.escape(package)}-{nonce}/base\.apk)", response)
    require(match, f"Unrecognized PackageManager base APK path for {package} ({len(response.splitlines())} lines)")
    return match[1]


def install(android, package, apk, info, replace=False):
    android.verify_target()
    flags = ("-r",) if replace else ()
    result = android.command("install", *flags, "--user", "0", str(apk), timeout=90).stdout.decode()
    require("Success" in result.splitlines(), f"PackageManager did not install {package}")
    path = installed_apk_path(android.text("pm", "path", "--user", "0", package), package)
    uid = android.text("run-as", package, "id", "-u")
    installed_hash = android.text("run-as", package, "sha256sum", path).split()
    require(installed_hash and installed_hash[0] == info["apk_sha256"] and uid.isdecimal() and int(uid) >= 10000,
            f"Wrong installed APK bytes or UID for {package}")
    return int(uid)


def uri_for(row, token=None):
    token = row["attempt_token"] if token is None else token
    require(re.fullmatch(UUID, row["id"]) and re.fullmatch(UUID, token or ""), "Invalid synthetic row/attempt identity")
    return f"content://{AUTHORITY}/file/{row['id']}/{token}"


def validate_receipt(receipt, uid, selected=(), action=None, unavailable=()):
    selected, unavailable = set(selected), set(unavailable)
    require(receipt.get("uid") == uid and "error" not in receipt, f"Offline recipient did not issue a valid receipt: {receipt.get('error')}")
    if action is not None:
        require(receipt.get("action") == "android.intent.action." + action and set(receipt.get("selected", ())) == selected,
                "Incorrect Android delivery action or selected URI set")
    access = receipt["access"]
    require(set(access) == {"history", "active", "queued", "staging", "stale", "descendant"}, "Incomplete recipient access probes")
    require(receipt.get("privateStage") in ("denied", "hidden"), "Separate UID opened the private checkpoint")
    for label, result in access.items():
        require(result.get("readGrant") is (label in selected) and result.get("writeGrant") is False
                and result.get("write") in ("denied", "missing"), f"Wrong URI grant/write access for {label}: {result}")
        if label in unavailable:
            require(result.get("read") == "missing", f"A still-granted {label} URI could reopen a replaced/removed file")
        elif label not in selected:
            require(result.get("read") in ("denied", "missing"), f"Unselected URI was readable: {label}")
        else:
            entry = JOBS[label]
            require((result.get("read"), result.get("name"), result.get("bytes"), result.get("sha256"))
                    == ("opened", entry.name, len(entry.body), entry.digest), f"Recipient read the wrong bytes or metadata: {label}")


class Recipient:
    def __init__(self, android, uid, profile):
        self.android = android
        self.uid = uid
        uris = {role: uri_for(profile.rows[role]) for role in ("history", "active", "queued")}
        uris["staging"] = uri_for(profile.rows["paused"])
        uris["stale"] = uri_for(profile.rows["history"], fixture_id("stale-token"))
        require(uris["stale"] != uris["history"], "Fixture has no wrong-token control")
        uris["descendant"] = uris["history"] + "/other"
        self.app("mkdir", "-p", "files")
        config = json.dumps({"uris": uris, "privateStage": profile.rows["paused"]["temp_path"]}).encode()
        self.app("sh", "-c", "set -C; cat > files/probe.json", data=config)

    def app(self, *args, data=None):
        return self.android.shell("run-as", RECIPIENT, *args, data=data).stdout

    def clear(self):
        self.app("rm", "-f", "files/result.json")

    def receipt(self, phase):
        data = self.app("sh", "-c", "if [ -e files/result.json ]; then cat files/result.json; fi")
        if not data:
            return None
        require(len(data) <= 3072, "Oversized recipient receipt")
        result = json.loads(data)
        require(result.get("phase") == phase and "error" not in result, f"Unexpected {phase} receipt: {result.get('error')}")
        return result

    def probe(self, phase, selected=(), unavailable=()):
        self.clear()
        self.android.shell("am", "broadcast", "--include-stopped-packages", "-a", PROBE,
                           "-n", f"{RECIPIENT}/.FixtureReceiver$Probe", "--es", "phase", phase, timeout=20)
        receipt = until(lambda: self.receipt(phase), f"separate UID {phase} probe", seconds=10)
        validate_receipt(receipt, self.uid, selected, unavailable=unavailable)

    def reset(self):
        self.android.shell("am", "force-stop", RECIPIENT)
        self.probe("before")  # verify zero retained grants before every delivery
        self.clear()


def foreground(android):
    resumed = re.search(r"topResumedActivity=.*?\bu0 ([\w.]+/[\w.$]+)", android.text("dumpsys", "activity", "activities"))
    return resumed[1] if resumed else None


def picker_nodes(root):
    if root is None:
        return []
    return [n for n in root.iter("node") if n.get("package") in SYSTEM_RESOLVERS and n.get("enabled") == "true"]


def once_button(nodes):
    return next((n for n in nodes if n.get("resource-id", "").endswith(":id/button_once")
                 and n.get("text", "").casefold() == "just once"), None)


def picker_option(nodes, view):
    # Android's resolver may represent the last-used activity in its title
    # instead of listing it as a row. Never confirm an unlabeled default.
    if view and any(n.get("text") == "Open with " + RECIPIENT_LABEL for n in nodes):
        once = once_button(nodes)
        if once is not None:
            return once, True
    for node in nodes:
        if RECIPIENT_LABEL in (node.get("text"), node.get("content-desc")):
            return node, False
    return None


def select_picker(android, showing, view):
    seen = []
    expansions = 0

    def choose():
        nonlocal seen, expansions
        require(foreground(android) == showing, "Android picker was dismissed before selection")
        nodes = picker_nodes(android.hierarchy())
        seen = [n.get("text") or n.get("content-desc") for n in nodes if n.get("text") or n.get("content-desc")]
        option = picker_option(nodes, view)
        if option is None and nodes and not view and expansions < 2:
            # Sharesheet can start collapsed. Scroll only in the confirmed
            # system chooser, and never select an app by coordinate alone.
            sizes = re.findall(r"(?:Physical|Override) size: (\d+)x(\d+)", android.text("wm", "size"))
            require(sizes, "Unknown emulator screen size")
            width, height = map(int, sizes[-1])
            x = str(width // 2)
            android.shell("input", "swipe", x, str(height * 85 // 100), x, str(height * 30 // 100), "450")
            expansions += 1
        return option

    try:
        option, already_once = until(choose, "fixture app in Android picker")
    except CheckFailed as failure:
        raise CheckFailed(f"{failure}; {showing}; system labels: {', '.join(seen)[:160]}") from failure
    android.tap_view(option)
    if view and not already_once:
        def once_or_arrived():
            if (foreground(android) or "").startswith(RECIPIENT + "/"):
                return True
            return once_button(picker_nodes(android.hierarchy()))
        once = until(once_or_arrived, "Open resolver selection")
        if once is not True:
            android.tap_view(once)


def deliver(android, recipient, intent_action, selected):
    # The browser action posts from a DB reader. A tap isn't proof of delivery.
    def dispatched():
        shown = foreground(android)
        return shown if shown != f"{PACKAGE}/{DOWNLOADS}" else None

    showing = until(dispatched, "Android resolver or recipient after Downloads action")
    package, activity = showing.split("/", 1)
    if intent_action != "VIEW":
        require(package in SYSTEM_RESOLVERS and activity.endswith("ChooserActivity"), f"No Android sharesheet: {showing}")
    if package in SYSTEM_RESOLVERS:
        require(activity.endswith(("ResolverActivity", "ChooserActivity")), f"Unexpected resolver activity: {showing}")
        select_picker(android, showing, view=intent_action == "VIEW")
    else:
        require(package == RECIPIENT, f"Open reached an unexpected app: {showing}")
    until(lambda: (foreground(android) or "").startswith(RECIPIENT + "/"), "fixture receiver in Android foreground")
    receipt = until(lambda: recipient.receipt("delivery"), f"actual Android {intent_action} delivery", seconds=10)
    validate_receipt(receipt, recipient.uid, selected, intent_action)


def no_dispatch(android, recipient, trigger):
    trigger()
    deadline = time.monotonic() + 2.5
    while time.monotonic() < deadline:
        require(foreground(android) == f"{PACKAGE}/{DOWNLOADS}" and recipient.receipt("delivery") is None,
                "Replaced file launched an activity or delivered a stream")
        time.sleep(0.2)
    recipient.probe("before")
    recipient.clear()


def downloads(android, completed_only=False):
    android.start(DOWNLOADS)
    until(lambda: foreground(android) == f"{PACKAGE}/{DOWNLOADS}", "Downloads in Android foreground")
    # Some launches reuse the activity whose selection toolbar is still open.
    root = until(android.hierarchy, "Downloads hierarchy")
    close = find_view(root, "downloads_btn_close_selection")
    if close is not None:
        android.tap_view(close)
    android.tap("pill_completed" if completed_only else "pill_all")


def phase(report, name):
    report["phase"] = name
    print(f"Upgrade check: {name}", flush=True)


def run_case(android, args, server, report, old_apk, new_apk, recipient_apk):
    work = args.work
    phase(report, "install real v7 and complete a download in that app")
    uid = install(android, PACKAGE, args.baseline, old_apk)
    android.shell("pm", "grant", "--user", "0", PACKAGE, "android.permission.POST_NOTIFICATIONS")
    directory = prepare(android)
    history = JOBS["history"]
    android.tap_view(ready_to_enqueue(android, directory, server.url("history"), history.name))
    card(android, "history", badge="COMPLETED")
    android.shell("am", "force-stop", PACKAGE)
    until(lambda: android.pid() is None, "old browser stopped")
    original = snapshot(android, work)
    require(original.schema == 7 and set(original.rows) == {"history"}, "Installed baseline did not create its own v7 history")
    history_file = completed(android, directory, original, "history")
    network(server, dict(AUTO, queued=[], active=[]))

    phase(report, "seed stopped v7 and replace APK without clearing data")
    seed = install_seed(android, work, directory, server, original)
    markers = {role: check_prefix(android, directory, seed.rows[role], role) for role in ("active", "paused")}
    preinstall = snapshot_bytes(android)
    fresh_uid = install(android, PACKAGE, args.candidate, new_apk, replace=True)
    require(fresh_uid == uid and android.pid() is None and android.app("cat", MARKER) == MARKER_BYTES
            and snapshot_bytes(android) == preinstall, "Package replacement did not leave the stopped v7 profile intact")
    report["replacement"] = {"baseline": "restored step19", "same_uid": True, "install_r": True, "v7_intact_before_launch": True}
    recipient_uid = install(android, RECIPIENT, args.recipient, recipient_apk)
    require(recipient_uid != uid, "Recipient does not have a separate UID")

    phase(report, "browser relaunch migrates v7 and automatically resumes eligible rows")
    android.start(ACTIVITY)
    wait_range(server, "active")
    live = snapshot(android, work)
    require_v8(live, ALL_JOBS)
    same_row(live, seed, "history")
    same_row(live, seed, "paused")
    held_checkpoint(android, directory, live, seed, "active", markers["active"])
    server.gates["active"].set()

    def auto_done():
        state = snapshot(android, work)
        require_v8(state, ALL_JOBS)
        require(all(state.rows[key]["status"] != "FAILED" for key in ("queued", "active")), "Automatic job failed in Android")
        return state if all(state.rows[key]["status"] == "COMPLETED" for key in ("queued", "active")) else None

    after = until(auto_done, "automatic queued and interrupted job completion", seconds=35)
    network(server, AUTO)
    same_row(after, seed, "history")
    same_row(after, seed, "paused")
    files = {key: completed(android, directory, after, key, seed.rows[key] if key != "history" else None)
             for key in ("history", "queued", "active")}
    require(files["history"] == history_file and markers["paused"] == check_prefix(android, directory, after.rows["paused"], "paused"),
            "The pre-upgrade completion or manual pause checkpoint changed")
    report["schema"] = [7, 8]
    report["history_preserved"] = True
    report["jobs"] = {role: {"from": STATES.get(role, "COMPLETED"), "bytes": len(JOBS[role].body), "sha256": data[1]}
                      for role, data in files.items()}
    report["jobs"]["active"]["resumed_at"] = JOBS["active"].offset
    paused_since = time.monotonic()

    recipient = Recipient(android, recipient_uid, after)
    report["deliveries"] = {}
    for name, intent_action, trigger in (
        ("open_card", "VIEW", lambda: android.tap_view(card(android, "history", badge="COMPLETED"))),
        ("open_menu", "VIEW", lambda: action(android, "history", "Open File")),
        ("share_one", "SEND", lambda: action(android, "history", "Share File")),
    ):
        phase(report, name)
        recipient.reset()
        downloads(android, completed_only=True)
        trigger()
        deliver(android, recipient, intent_action, ("history",))
        report["deliveries"][name] = ["history"]

    phase(report, "replace a selected file and check both retained and fresh access")
    path = path_for(android, directory, after, "history")
    saved = str(Path(path).with_suffix(".saved"))
    android.app("mv", "-n", path, saved)
    android.write_new(path, b"X" * len(history.body))
    replacement = android.file(path)
    require(android.file(saved) == files["history"] and replacement[0] != history_file[0] and replacement[1] != history_file[1],
            "Did not replace the fixture file with a distinct same-size entry")
    recipient.probe("replaced", selected=("history",), unavailable=("history",))
    recipient.reset()
    downloads(android, completed_only=True)
    no_dispatch(android, recipient, lambda: android.tap_view(card(android, "history", badge="COMPLETED")))
    no_dispatch(android, recipient, lambda: action(android, "history", "Share File"))
    require(android.file(path) == replacement, "A rejected Open/Share changed the replacement")
    android.app("rm", path)
    android.app("mv", "-n", saved, path)
    require(android.file(path) == files["history"], "Could not restore the original download")
    report["replaced"] = {"held_grant_cannot_reopen": True, "fresh_open_and_share_no_delivery": True}

    phase(report, "share two actual selections through the Android sharesheet")
    recipient.reset()
    downloads(android, completed_only=True)
    action(android, "history", "Select")
    android.view("downloads_text_selected_count", "1 selected")
    android.tap_view(card(android, "active", badge="COMPLETED"))
    android.view("downloads_text_selected_count", "2 selected")
    android.tap("downloads_btn_share_selected")
    deliver(android, recipient, "SEND_MULTIPLE", ("history", "active"))
    report["deliveries"]["share_many"] = ["history", "active"]
    report["confinement"] = {"separate_uid": True, "read_only_exact_uris": True, "sibling_staging_wrong_token_descendant_denied": True}

    phase(report, "remove history without deleting files; old grants cannot reopen")
    downloads(android, completed_only=True)  # bring browser forward without finishing recipient
    android.tap("btn_overflow_menu")
    android.tap("menu_option_clear_completed")

    def cleared():
        state = snapshot(android, work)
        return state if set(state.rows) == {"paused"} else None

    remaining = until(cleared, "Clear Completed removed three fixture rows")
    require_v8(remaining, {"paused"})
    same_row(remaining, seed, "paused")
    require(all(android.file(path_for(android, directory, after, key)) == data for key, data in files.items()),
            "Clear Completed unexpectedly removed or changed fixture files")
    recipient.probe("removed", selected=("history", "active"), unavailable=("history", "active"))
    report["removed"] = {"files_preserved": True, "held_grants_cannot_reopen": True}

    phase(report, "explicit Resume for the still-paused checkpoint")
    android.tap("pill_all")
    resume = card(android, "paused", "card_btn_pause_resume", badge="PAUSED")
    require(check_prefix(android, directory, remaining.rows["paused"], "paused") == markers["paused"], "Paused prefix changed before Resume")
    network(server, AUTO)
    report["paused_without_network_seconds"] = int(time.monotonic() - paused_since)
    android.tap_view(resume)
    wait_range(server, "paused")
    resuming = snapshot(android, work)
    held_checkpoint(android, directory, resuming, seed, "paused", markers["paused"])
    server.gates["paused"].set()
    card(android, "paused", badge="COMPLETED")
    final = snapshot(android, work)
    completed(android, directory, final, "paused", seed.rows["paused"])
    network(server, dict(AUTO, paused=["range"]))
    report["jobs"]["paused"] = {"from": "PAUSED", "explicit_resume": True, "resumed_at": JOBS["paused"].offset,
                                "bytes": len(JOBS["paused"].body), "sha256": JOBS["paused"].digest}
    report["legacy_headers_sent"] = False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    for name in ("work", "sdk", "baseline", "candidate", "recipient"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("RUNNER_ENVIRONMENT") != "github-hosted":
        parser.error("This check requires a disposable GitHub-hosted AVD")
    commit = os.environ.get("GITHUB_SHA", "")
    require(re.fullmatch(r"[a-f0-9]{40}", commit), "Missing the tested source commit")
    report = {"commit": commit, "phase": "APK identity and Android target", "result": "not-passed"}
    android = Emulator(args.adb)
    try:
        android.verify_target()
        report["android"] = {"api": 34, "abi": android.text("getprop", "ro.product.cpu.abi"), "storage": "app-private"}
        old = apk_info(args.sdk, args.baseline, PACKAGE)
        new = apk_info(args.sdk, args.candidate, PACKAGE)
        receiver = apk_info(args.sdk, args.recipient, RECIPIENT)
        require(old["signer"] == new["signer"] != receiver["signer"] and old["apk_sha256"] != new["apk_sha256"]
                and new["version_code"] >= old["version_code"], "APK package replacement/signing preconditions failed")
        permissions = subprocess.check_output([args.sdk / "build-tools/34.0.0/aapt2", "dump", "permissions", args.recipient],
                                              text=True, timeout=30)
        require("uses-permission" not in permissions, "Offline fixture recipient requests permissions")
        report["apks"] = {"v7": old, "v8": new, "recipient": receiver}
        with fixture() as server:
            reverse = f"tcp:{server.server_port}"
            android.command("reverse", reverse, reverse)
            try:
                run_case(android, args, server, report, old, new, receiver)
            finally:
                report["http"] = server.events()
                android.command("reverse", "--remove", reverse, check=False)
        phase(report, "complete")
        report["result"] = "pass"
    except Exception as failure:
        report["error"] = str(failure).strip()[-500:]
        raise
    finally:
        data = (json.dumps(report, sort_keys=True, separators=(",", ":")) + "\n").encode()
        require(len(data) < 3400, "Upgrade result exceeded annotation budget")
        (args.work / "proof/download-upgrade.json").write_bytes(data)
        encoded = data.decode().rstrip("\n").replace("%", "%25")
        print(f"::notice title=Android download upgrade result::BROWSER_UPGRADE_RESULT {sha256(data).hexdigest()} {encoded}", flush=True)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""One real Android process-death test. Invoked only by the disposable CI AVD runner."""
import argparse
from contextlib import closing, contextmanager
from hashlib import sha256
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import shlex
import sqlite3
import subprocess
import sys
from tempfile import TemporaryDirectory
from threading import Thread
import time
from xml.etree import ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tests" / "fixtures"))
from download_recovery import ETAGS, PAYLOAD

PACKAGE = "com.browser"
SERIAL = "emulator-5554"
ACTIVITY = "com.privacy.browser.MainActivity"
DOWNLOADS = "com.privacy.downloader.ui.DownloadsActivity"
NAME = "interrupted.bin"
BODY = PAYLOAD[NAME]
ETAG = ETAGS[NAME]
LENGTH = len(BODY)
DIGEST = sha256(BODY).hexdigest()
SENTINEL = b"Preexisting file, not the download.\n"
OCCUPIED = "The reserved destination is occupied. The file was left untouched."
DB = "databases/downloads.db"
PRIVATE_DIR = r"/data/(?:user/0|data)/com\.browser/files/download-publication"
UI_DUMP = "/data/local/tmp/browser-publication.xml"
INITIAL_REQUESTS = ["probe-206", "conditional-206"]


class CheckFailed(RuntimeError):
    pass


def require(ok, explanation):
    if not ok:
        raise CheckFailed(explanation)


def until(read, description, seconds=25):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        result = read()
        # ElementTree leaf nodes (including buttons) are falsey, but are valid results.
        if result is not None and result is not False:
            return result
        time.sleep(0.25)
    raise CheckFailed(f"Timed out: {description}")


class DownloadFixture(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def do_HEAD(self):
        self.server.requests.append("unexpected")
        self.send_error(405)

    def do_GET(self):
        interval = self.headers.get_all("Range", [])
        validator = self.headers.get_all("If-Range", [])
        if (self.path != f"/jobs/{NAME}"
                or any(header in self.headers for header in ("Cookie", "Authorization", "Referer"))):
            event = "unexpected"
        elif interval == ["bytes=0-0"] and not validator:
            event, status, body = "probe-206", 206, BODY[:1]
        elif interval == [f"bytes=0-{LENGTH - 1}"] and validator == [ETAG]:
            event, status, body = "conditional-206", 206, BODY
        elif not interval and not validator:
            event, status, body = "fresh-200", 200, BODY
        else:
            event = "unexpected"
        self.server.requests.append(event)
        if event == "unexpected":
            self.send_error(400)
            return
        self.send_response(status)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Disposition", f'attachment; filename="{NAME}"')
        self.send_header("Content-Length", str(len(body)))
        self.send_header("ETag", ETAG)
        self.send_header("Cache-Control", "no-store")
        if status == 206:
            self.send_header("Content-Range", f"bytes 0-{len(body) - 1}/{LENGTH}")
        self.end_headers()
        self.wfile.write(body)


@contextmanager
def fixture():
    with ThreadingHTTPServer(("127.0.0.1", 0), DownloadFixture) as server:
        server.requests = []
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            yield server
        finally:
            server.shutdown()
            thread.join()


class Emulator:
    def __init__(self, adb):
        self.adb = adb

    def command(self, *args, data=None, check=True, timeout=15):
        result = subprocess.run([self.adb, "-s", SERIAL, *args], input=data, capture_output=True, timeout=timeout)
        if check and result.returncode:
            detail = (result.stderr or result.stdout).decode(errors="replace").strip()[-350:]
            raise CheckFailed(f"ADB {args[0]} failed: {detail}")
        return result

    def shell(self, *words, data=None, check=True, timeout=15):
        # Shell v2 with no TTY: binary-safe and propagates the device's exit code.
        return self.command("shell", "-T", shlex.join(words), data=data, check=check, timeout=timeout)

    def text(self, *words):
        return self.shell(*words).stdout.decode().strip()

    def app(self, *words, data=None):
        return self.shell("run-as", PACKAGE, *words, data=data).stdout

    def verify_target(self):
        devices = self.command("devices").stdout.decode().splitlines()[1:]
        require([line.split() for line in devices if line.strip()] == [[SERIAL, "device"]],
                "Expected exactly one disposable Android emulator")
        require(self.text("getprop", "ro.kernel.qemu") == "1" or self.text("getprop", "ro.boot.qemu") == "1",
                "ADB target did not identify as an emulator")
        require(self.text("getprop", "ro.build.version.sdk") == "34", "Unexpected Android API")

    def pid(self):
        result = self.shell("pidof", PACKAGE, check=False)
        pid = result.stdout.decode().strip()
        if result.returncode == 1 and not pid and not result.stderr:
            return None
        require(result.returncode == 0 and pid.isdigit(), "Expected at most one browser process on the emulator")
        return pid

    def start(self, activity):
        result = self.shell("am", "start", "-W", "-n", f"{PACKAGE}/{activity}", timeout=35).stdout.decode()
        require("Status: ok" in result.splitlines(), f"Could not open {activity}")

    def view(self, name, text=None):
        seen = "no valid window hierarchy"

        def find():
            nonlocal seen
            dump = self.shell("uiautomator", "dump", "--compressed", UI_DUMP, check=False)
            if dump.returncode or b"dumped to:" not in dump.stdout:
                return None
            try:
                root = ET.fromstring(self.shell("cat", UI_DUMP).stdout)
            except ET.ParseError:
                return None
            nodes = list(root.iter("node"))
            app_nodes = [node for node in nodes if node.get("package") == PACKAGE]
            visible_ids = sorted({node.get("resource-id", "").rpartition("/")[2] for node in app_nodes} - {""})
            seen = f"{len(app_nodes)}/{len(nodes)} browser nodes, ids: {','.join(visible_ids)[:200]}"
            return find_view(root, name, text)

        try:
            return until(find, f"browser view {name}")
        except CheckFailed as failure:
            raise CheckFailed(f"{failure}; {seen}") from failure

    def tap(self, name, text=None):
        return self.tap_view(self.view(name, text))

    def tap_view(self, node):
        match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        require(match is not None, "Browser control did not have tappable bounds")
        x1, y1, x2, y2 = map(int, match.groups())
        require(x1 < x2 and y1 < y2, "Browser control is not on the screen")
        self.shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def file(self, path):
        identity = self.app("stat", "-c", "%d:%i:%Y:%s", path).decode().strip()
        require(re.fullmatch(r"\d+:\d+:\d+:\d+", identity), "Could not identify fixture file")
        data = self.app("cat", path)
        require(len(data) == int(identity.split(":")[-1]), "Fixture file changed while it was read")
        return identity, sha256(data).hexdigest()

    def write_new(self, path, data):
        self.app("sh", "-c", f"set -C; cat > {shlex.quote(path)}", data=data)


def find_view(root, name, text=None):
    choices = [node for node in root.iter("node") if node.get("package") == PACKAGE
               and node.get("enabled") == "true" and node.get("resource-id", "").rpartition("/")[2] == name
               and (text is None or node.get("text", "").casefold() == text.casefold())]
    require(len(choices) <= 1, f"Ambiguous browser view: {name}")
    return choices[0] if choices else None


def read_state(database, wal, work):
    with TemporaryDirectory(prefix="sqlite-", dir=work) as tmp:
        copy = Path(tmp) / "downloads.db"
        copy.write_bytes(database)
        if wal:
            copy.with_name("downloads.db-wal").write_bytes(wal)
        with closing(sqlite3.connect(copy.as_uri() + "?mode=ro", uri=True)) as connection:
            connection.row_factory = sqlite3.Row
            rows = connection.execute("SELECT id,file_name,url,status,attempt_token,dest_path,destination_key,"
                                      "temp_path,total_bytes,downloaded_bytes,finished_time,published_identity,"
                                      "error_message,full_download_only FROM downloads").fetchall()
            require(len(rows) == 1, "Expected exactly one download in the fresh app database")
            row = dict(rows[0])
            segments = connection.execute("SELECT start_byte,end_byte,current_byte,status FROM download_segments "
                                          "WHERE task_id=? ORDER BY segment_index", (row["id"],)).fetchall()
            return row, [tuple(segment) for segment in segments]


def snapshot(android, work):
    def files():
        return tuple(android.app("sh", "-c", f"if [ -e {DB}{suffix} ]; then cat {DB}{suffix}; fi")
                     for suffix in ("", "-wal", "-journal"))

    def stable():
        sample = files()
        return sample[:2] if sample[0] and not sample[2] and sample == files() else None

    # No live DB writes: double-read main/WAL around a quiescent point, never ignore a hot journal.
    return read_state(*until(stable, "stable Android SQLite snapshot", seconds=10), work)


def status(android, label):
    android.view("card_text_filename", NAME)
    android.view("card_text_status_badge", label)


def verify_unpublished(row):
    require(row["published_identity"] is None and row["finished_time"] == 0,
            "The download already had committed completion metadata")


def verify_occupied(row):
    verify_unpublished(row)
    require(row["status"] == "FAILED" and row["attempt_token"] is None and OCCUPIED in (row["error_message"] or ""),
            "The reserved destination did not cause a safe occupied-name refusal")


def bound_path(android, configured_dir, path, file_pattern):
    # The app and run-as can see different /data/data versus /data/user/0 aliases.
    # Accept only a known fixture basename in the exact directory that we created.
    require(path and re.fullmatch(PRIVATE_DIR + "/" + file_pattern, path), "Unexpected file path in the fixture row")
    expected_parent = android.app("stat", "-L", "-c", "%d:%i", configured_dir).strip()
    actual_parent = android.app("stat", "-L", "-c", "%d:%i", str(Path(path).parent)).strip()
    require(re.fullmatch(rb"\d+:\d+", expected_parent) and actual_parent == expected_parent,
            "Fixture row does not name the created directory inode")
    return path


def prepare(android):
    require(android.pid() is None, "Clean app has already been launched")
    android.app("test", "!", "-e", DB)
    android.app("mkdir", "-p", "files", "shared_prefs")
    android.app("mkdir", "files/download-publication")
    directory = android.app("realpath", "files/download-publication").decode().strip()
    require(re.fullmatch(PRIVATE_DIR, directory), "Refusing to use anything outside the confirmed app-private fixture folder")
    # Set only the destination in this newly installed disposable profile. No downloads are seeded.
    android.write_new("shared_prefs/downloader_settings.xml", (
        f'<?xml version="1.0" encoding="utf-8"?><map><string name="default_download_dir">{directory}</string>'
        '<boolean name="category_subfolders" value="false" /></map>').encode())
    return directory


def ready_to_enqueue(android, directory, url):
    android.start(DOWNLOADS)
    android.tap("btn_add_download")
    android.tap("add_url_edit_text")
    android.shell("input", "text", url)
    android.view("add_url_edit_text", url)
    if "mInputShown=true" in android.text("dumpsys", "input_method"):
        android.shell("input", "keyevent", "KEYCODE_BACK")
    android.tap("add_url_btn_continue")
    android.view("prompt_edit_filename", NAME)
    android.view("prompt_text_folder_path", directory)
    android.tap("prompt_pill_thread_1")
    return android.view("prompt_btn_download", "Download")


@contextmanager
def debugger(android, work, pid):
    port = android.command("forward", "tcp:0", f"jdwp:{pid}").stdout.decode().strip()
    require(port.isdigit(), "Could not create local ADB debugger forward")
    transcript = work / "debugger.log"
    java = str(Path(os.environ["JAVA_HOME"]) / "bin" / "java")
    with transcript.open("wb") as log:
        dbg = subprocess.Popen([java, "--add-modules", "jdk.jdi", "-cp", str(work / "jdi"),
                                "PublicationPause", port], stdin=subprocess.DEVNULL, stdout=log, stderr=log)
        try:
            yield dbg, transcript
        finally:
            if dbg.poll() is None:
                # A failed assertion must not release a suspended publication in a still-live app.
                android.shell("am", "force-stop", PACKAGE, check=False)
                dbg.terminate()
                dbg.wait(timeout=5)
            android.command("forward", "--remove", f"tcp:{port}")


def wait_marker(dbg, transcript, marker, seconds=30):
    def found():
        text = transcript.read_text(errors="replace")
        require(dbg.poll() in (None, 0), f"Debugger stopped: {text[-550:]}")
        return marker in text.splitlines()
    until(found, f"debugger {marker}", seconds)


def run_case(android, work, server, report):
    port = server.server_port
    url = f"http://127.0.0.1:{port}/jobs/{NAME}"
    directory = prepare(android)
    cut = f"{directory}/uncommitted.saved"
    sentinel = f"{directory}/sentinel.saved"
    android.command("reverse", f"tcp:{port}", f"tcp:{port}")
    try:
        report["phase"] = "real download and exact pre-commit breakpoint"
        confirm = ready_to_enqueue(android, directory, url)
        old_pid = android.pid()
        require(old_pid is not None, "Downloads activity did not start a process")
        with debugger(android, work, old_pid) as (dbg, transcript):
            wait_marker(dbg, transcript, "READY")
            android.tap_view(confirm)
            wait_marker(dbg, transcript, "HELD_BEFORE_DB_WRITE", seconds=45)
            before, segments = snapshot(android, work)
            report["at_cut"] = {"status": before["status"], "downloaded_bytes": before["downloaded_bytes"],
                                "total_bytes": before["total_bytes"], "segments": segments[:4],
                                "http_requests": server.requests[:6]}
            verify_unpublished(before)
            require(before["status"] == "RUNNING" and before["attempt_token"], "No live attempt in the pre-commit row")
            require(before["file_name"] == NAME and before["url"] == url, "The held attempt is not the synthetic job")
            require(before["total_bytes"] == before["downloaded_bytes"] == LENGTH
                    and segments == [(0, LENGTH - 1, LENGTH, "COMPLETED")], "Unexpected pre-commit byte coverage")
            dest = bound_path(android, directory, before["dest_path"], re.escape(NAME))
            bound_path(android, directory, before["destination_key"], re.escape(NAME))
            stage = bound_path(android, directory, before["temp_path"], r"download-[0-9]+\.part")
            original = android.file(dest)
            require(original[1] == DIGEST and original == android.file(stage),
                    "Expected a verified hard link from the real staged file before SQLite completion")
            require(server.requests == INITIAL_REQUESTS, "Unexpected requests before the cut")
            report["at_cut"].update({"published_identity": None, "same_inode": True, "final_file_sha256": original[1]})
            report["phase"] = "force-stop and verify uncommitted state before relaunch"
            require(dbg.poll() is None and android.pid() == old_pid, "Held process disappeared before the cut")
            android.shell("am", "force-stop", PACKAGE)
            until(lambda: android.pid() is None, "force-stopped browser process", seconds=10)
            wait_marker(dbg, transcript, "DISCONNECTED_AFTER_HOLD", seconds=10)
            require(dbg.wait(timeout=5) == 0, "Debugger did not confirm process disconnect")
        require(snapshot(android, work) == (before, segments), "SQLite state changed across the stop before relaunch")
        report["force_stop_before_commit"] = True

        report["phase"] = "relaunch and refuse to adopt the complete-looking file"
        android.start(ACTIVITY)
        new_pid = android.pid()
        require(new_pid is not None and new_pid != old_pid, "Relaunch did not start a new browser process")
        android.start(DOWNLOADS)
        status(android, "FAILED")
        recovered, saved_segments = snapshot(android, work)
        verify_occupied(recovered)
        require(recovered["id"] == before["id"] and recovered["dest_path"] == dest and recovered["temp_path"] == stage
                and recovered["downloaded_bytes"] == LENGTH and saved_segments == segments
                and android.file(dest) == original and android.file(stage) == original,
                "Recovery changed the original reserved file or its checkpoint")
        require(server.requests == INITIAL_REQUESTS, "Occupied name issued requests on recovery")
        report["relaunch"] = {"new_process": True, "ui": "FAILED", "db": "FAILED", "reason": recovered["error_message"],
                              "preserved_final_and_checkpoint": True, "new_requests": 0}

        report["phase"] = "reject an unrelated file at the same reserved name"
        android.app("mv", "-n", dest, cut)
        android.write_new(dest, SENTINEL)
        occupied_file = android.file(dest)
        require(occupied_file[1] == sha256(SENTINEL).hexdigest(), "Could not verify the new occupied entry")
        android.tap("card_btn_pause_resume")
        android.tap("button3", "Start over")

        def refused_start_over():
            row, _ = snapshot(android, work)
            return row if row["full_download_only"] and row["status"] == "FAILED" else None

        blocked = until(refused_start_over, "explicit Start over refused at occupied name")
        verify_occupied(blocked)
        require(blocked["id"] == before["id"] and blocked["dest_path"] == dest and blocked["temp_path"] is None
                and blocked["downloaded_bytes"] == 0 and android.file(dest) == occupied_file
                and android.file(stage) == original and android.file(cut) == original
                and set(android.app("ls", "-1", directory).decode().splitlines()) == {NAME, Path(stage).name, Path(cut).name}
                and server.requests == INITIAL_REQUESTS, "An occupied retry changed files or touched the network")
        report["unrelated_destination"] = {"start_over_refused": True, "bytes_unchanged": len(SENTINEL),
                                           "sha256": occupied_file[1], "new_requests": 0}

        report["phase"] = "move sentinel aside and retry to verified completion"
        android.app("mv", "-n", dest, sentinel)
        require(android.file(sentinel) == occupied_file, "Could not preserve the unrelated entry before retry")
        android.tap("card_btn_pause_resume")
        android.tap("button1", "Retry")
        status(android, "COMPLETED")
        completed, _ = snapshot(android, work)
        final = android.file(dest)
        require(completed["id"] == before["id"] and completed["dest_path"] == dest and completed["status"] == "COMPLETED"
                and completed["attempt_token"] and completed["attempt_token"] != before["attempt_token"]
                and completed["finished_time"] > 0 and completed["temp_path"] is None and completed["error_message"] is None
                and completed["total_bytes"] == completed["downloaded_bytes"] == LENGTH
                and completed["published_identity"] == final[0] and final[1] == DIGEST and final[0] != original[0]
                and android.file(sentinel) == occupied_file and android.file(stage) == original and android.file(cut) == original
                and set(android.app("ls", "-1", directory).decode().splitlines()) == {
                    NAME, Path(stage).name, Path(cut).name, Path(sentinel).name}
                and server.requests == INITIAL_REQUESTS + ["fresh-200"], "Retry did not commit one new, byte-exact file")
        report["retry"] = {"ui": "COMPLETED", "db": "COMPLETED", "bytes": LENGTH, "sha256": final[1],
                           "http": "unconditional 200 from byte zero", "previous_files_preserved": True}
    finally:
        android.command("reverse", "--remove", f"tcp:{port}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--work", required=True, type=Path)
    parser.add_argument("--apk-sha256", required=True)
    args = parser.parse_args()
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("RUNNER_ENVIRONMENT") != "github-hosted":
        parser.error("Use only on the GitHub-hosted disposable emulator runner")
    commit = os.environ["GITHUB_SHA"]
    require(re.fullmatch(r"[a-f0-9]{40}", commit) and re.fullmatch(r"[a-f0-9]{64}", args.apk_sha256),
            "Missing pinned checkout or APK identity")
    report = {"commit": commit, "apk_sha256": args.apk_sha256, "phase": "confirm clean Android 14 emulator",
              "result": "not-passed"}
    android = Emulator(args.adb)
    try:
        android.verify_target()
        report["android"] = {"api": 34, "abi": android.text("getprop", "ro.product.cpu.abi"), "storage": "app-private"}
        with fixture() as server:
            run_case(android, args.work, server, report)
        report["phase"] = "complete"
        report["result"] = "pass"
    except Exception as failure:
        report["error"] = str(failure).strip()[-650:]
        raise
    finally:
        data = (json.dumps(report, sort_keys=True, separators=(",", ":")) + "\n").encode()
        require(len(data) < 3200, "Result report exceeded annotation budget")
        (args.work / "proof" / "download-publication.json").write_bytes(data)
        encoded = data.decode().rstrip("\n").replace("%", "%25")
        print(f"::notice title=Android download publication result::BROWSER_DOWNLOAD_RESULT {sha256(data).hexdigest()} {encoded}", flush=True)


if __name__ == "__main__":
    main()

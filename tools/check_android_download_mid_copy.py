#!/usr/bin/env python3
"""Cut a real Android exclusive copy, then retry from the saved stage on a CI-only AVD."""
import argparse
from contextlib import suppress
from hashlib import sha256
import json
import os
from pathlib import Path
import re
import shlex
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from tools.check_android_download_publication import (
    ACTIVITY, BODY, DIGEST, DOWNLOADS, ETAG, INITIAL_REQUESTS, LENGTH, NAME, PACKAGE, PRIVATE_DIR,
    CheckFailed, Emulator, bound_path, debugger, find_view, fixture, prepare, publication_kind, ready_to_enqueue,
    require, snapshot, status, until, verify_occupied, verify_unpublished, wait_marker,
)

PUBLISHER = Path("downloader/src/main/java/com/privacy/downloader/engine/FilePublication.java")
SHARED_PATH = "/storage/emulated/0/Download/arena-mid-copy-fixture"
SHARED_DIR = r"(?:/storage/emulated/0|/storage/self/primary|/sdcard)/Download/arena-mid-copy-fixture"
PREFERENCES = "shared_prefs/downloader_settings.xml"
NEIGHBOR = "unrelated.bin"
NEIGHBOR_BYTES = b"Unrelated synthetic file. Do not overwrite.\n"
PARKED = "abandoned-copy.bin"
MID_COPY = "HELD_DURING_COPY"
PRE_COMMIT = "HELD_BEFORE_DB_WRITE"


def copy_cut(source):
    """Resolve the JDI location from the exact built source, not a stale line number."""
    update = "copied.update(buffer, 0, count);"
    lines = source.splitlines()
    locations = [i for i, line in enumerate(lines) if line.strip() == update]
    buffer = re.search(r"private static final int BUFFER_SIZE = (\d+) \* 1024;", source)
    require(len(locations) == 1 and buffer, "Copy loop anchors changed; update the test breakpoint")
    i = locations[0]
    require(0 < i < len(lines) - 1 and lines[i - 1].strip() == "while (chunk.hasRemaining()) out.write(chunk);"
            and lines[i + 1].strip() == "written += count;", "Copy cutoff must be after a finished chunk write")
    chunk = int(buffer[1]) * 1024
    require(chunk and 1 <= LENGTH // (2 * chunk) <= 64, "Fixture is not sized for a bounded midpoint cut")
    return i + 1, LENGTH // (2 * chunk), chunk


def size(file):
    return int(file[0].rsplit(":", 1)[1])


def verify_cut(marker, output, stage, bytes_limit):
    require(size(stage) == LENGTH and stage[1] == DIGEST, "The saved stage is not the entire fixture")
    if marker == PRE_COMMIT:
        # An actual atomic hard link never enters the copy loop. Report it only
        # if the exact bytes and same inode prove the link, not an escaped copy.
        require(size(output) == LENGTH and publication_kind(output, stage) == "hard-link",
                "The copy-loop breakpoint was missed; a completed copy is not a mid-copy cut")
        return "hard-link-before-commit"
    require(marker == MID_COPY and 0 < size(output) <= bytes_limit < LENGTH,
            "The copy-loop breakpoint did not leave a nonempty short destination")
    require(output[0].split(":")[:2] != stage[0].split(":")[:2], "The short copy must be a different inode")
    require(output[1] == sha256(BODY[:size(output)]).hexdigest(), "The short copy does not match the fixture prefix")
    return "mid-copy"


def wait_cut(dbg, transcript):
    def held():
        text = transcript.read_text(errors="replace")
        require(dbg.poll() is None, f"Debugger exited before holding the publisher: {text[-360:]}")
        markers = {MID_COPY, PRE_COMMIT}.intersection(text.splitlines())
        require(len(markers) < 2, "Ambiguous publication breakpoint")
        return next(iter(markers), None)
    return until(held, "real copy-loop or hard-link boundary", seconds=45)


def bound_destination(android, directory, row, pattern, shared):
    dest = bound_path(android, directory, row["dest_path"], re.escape(NAME), pattern)
    # The DB key lowercases the canonical path (including the public Download
    # directory). It is a reservation key, not a literal path to mutate.
    key = row["destination_key"]
    require(key and key == key.lower(), "Unexpected destination reservation key")
    key_path = key.replace("/download/", "/Download/", 1) if shared else key
    bound_path(android, directory, key_path, re.escape(NAME), pattern)
    return dest


def require_no_file_actions(root):
    select = find_view(root, "action_item_text", "Select")
    require(find_view(root, "ctx_header_title", NAME) is not None and select is not None,
            "Wrong Downloads context menu")
    labels = [" ".join(n.get("text", "").casefold().split()) for n in root.iter("node") if n.get("package") == PACKAGE
              and n.get("resource-id", "").rpartition("/")[2] == "action_item_text"]
    require("open file" not in labels and "share file" not in labels, "Uncommitted file was offered for Open or Share")
    return select


def downloads_resumed(activity_dump):
    # Android flattens same-prefix components as e.g. com.browser/.SomeActivity.
    current = re.findall(r"^\s*topResumedActivity=ActivityRecord\{[\da-f]+ u0 ([\w.]+/[\w.$]+) ",
                         activity_dump, flags=re.MULTILINE)
    return current == [f"{PACKAGE}/{DOWNLOADS}"]


def share_refused(toasts):
    return re.search(r"^\s*TextToastRecord\{[^\n]+ " + r"\d+:" + re.escape(PACKAGE) +
                     r"/[^\n]+ text=Cannot share files: Select at least one completed download to share\. duration=",
                     toasts, flags=re.MULTILINE) is not None


def check_not_offered(android, relaunch):
    android.tap("card_text_filename", NAME)
    android.view("diag_text_file_name", NAME)
    android.view("diag_text_status_badge", "FAILED")
    relaunch["open_diagnostics_only"] = True
    android.tap("diag_btn_close")
    android.tap_view(android.view("card_text_filename", NAME), long=True)

    def select_if_safe():
        root = android.hierarchy()
        if root is None or find_view(root, "ctx_header_title", NAME) is None:
            return None
        return require_no_file_actions(root)

    # Match the exact fixture menu and select it from the same UI snapshot.
    # The generic toolbar Share must not launch for this single failed item.
    android.tap_view(until(select_if_safe, "fixture Downloads context menu"))
    relaunch["no_open_share_menu"] = True
    android.view("downloads_text_selected_count", "1 selected")

    def foreground():
        return downloads_resumed(android.text("dumpsys", "activity", "activities"))

    def refused():
        return share_refused(android.text("dumpsys", "notification"))

    require(foreground(), "Activity check could not identify Downloads before tapping Share")
    require(not refused(), "Unexpected stale Share refusal before tapping Share")
    android.tap("downloads_btn_share_selected")

    def still_here():
        require(foreground(), "Sharing just the failed item did not remain in Downloads")
        return refused()

    until(still_here, "failed-only Share refusal toast", seconds=5)
    relaunch["selection_share_refused"] = True
    # Recreate just the Downloads activity for Retry, without a Back gesture
    # or waiting for controls underneath the transient system toast window.
    android.start(DOWNLOADS, reset_activity=True)
    android.view("btn_overflow_menu")
    status(android, "FAILED")


def browser_events(android):
    # Keep only narrow lifecycle markers on a failure, not logcat or error messages.
    tags = ("am_proc_start", "am_proc_died", "am_kill", "am_crash", "am_anr")
    events = android.text("logcat", "-b", "events", "-d", "-v", "brief", "-s", *tags)
    result = []
    for line in events.splitlines():
        match = re.match(r"\w/(am_\w+)\s*\([^)]*\): \[(.*)\]$", line)
        if not match:
            continue
        tag, data = match.groups()
        fields = data.split(",", 6)
        index = 3 if tag == "am_proc_start" else 2
        if len(fields) > index and fields[index] == PACKAGE and fields[1].isdigit():
            event = [tag, int(fields[1])]
            if tag == "am_crash" and len(fields) > 4 and re.fullmatch(r"[\w.$ ]{1,80}", fields[4]):
                event.append(fields[4])
            result.append(event)
    return result[-8:]


def phase(report, step):
    report["phase"] = step
    print(f"Mid-copy check ({report['storage']}): {step}", flush=True)


def case(android, work, server, directory, report, cut):
    shared = report["storage"] == "shared-downloads"
    pattern = SHARED_DIR if shared else PRIVATE_DIR
    neighbor = f"{directory}/{NEIGHBOR}"
    marker_file = android.file(neighbor)
    require(size(marker_file) == len(NEIGHBOR_BYTES) and marker_file[1] == sha256(NEIGHBOR_BYTES).hexdigest(),
            "Wrong unrelated fixture file before transfer")
    require(android.app("ls", "-1A", directory).decode().splitlines() == [NEIGHBOR], "Destination fixture is not clean")
    line, hits, chunk = cut
    url = f"http://127.0.0.1:{server.server_port}/jobs/{NAME}"

    phase(report, "select the destination in the real download prompt")
    confirm = ready_to_enqueue(android, None, url)
    shown = android.view("prompt_text_folder_path").get("text", "")
    if shared and shown != directory and not re.fullmatch(pattern, shown):
        require(shown.startswith("/"), "The prompt did not expose its selected filesystem path")
        report.update(result="blocked", reason="Browser chose a fallback instead of the public Downloads folder")
        android.shell("input", "keyevent", "KEYCODE_BACK")  # don't enqueue at a fallback
        return None
    bound_path(android, directory, f"{shown}/{NAME}", re.escape(NAME), pattern)

    phase(report, "hold the publisher after a chunk write")
    old_pid = android.pid()
    require(old_pid is not None, "Downloads did not start a browser process")
    with debugger(android, work, old_pid, line, hits) as (dbg, transcript):
        wait_marker(dbg, transcript, "READY")
        android.tap_view(confirm)
        try:
            marker = wait_cut(dbg, transcript)
        except CheckFailed:
            # Preserve the last real row status/reason, but do not let a missing
            # breakpoint stand in for a process-death observation.
            with suppress(Exception):
                observed, _ = snapshot(android, work)
                report["observed_before_cut"] = {"status": observed["status"], "reason": (observed["error_message"] or "")[:140]}
            raise
        before, segments = snapshot(android, work)
        report["at_cut"] = {"marker": marker, "status": before["status"], "checkpoint_bytes": before["downloaded_bytes"],
                            "segments": segments[:2], "before_commit": before["published_identity"] is None}
        verify_unpublished(before)
        require(before["status"] == "RUNNING" and before["attempt_token"] and before["file_name"] == NAME
                and before["url"] == url and before["total_bytes"] == before["downloaded_bytes"] == LENGTH
                and segments == [(0, LENGTH - 1, LENGTH, "COMPLETED")] and before["supports_range"] == 1
                and before["etag"] == ETAG and before["verified_range_url"] == url and not before["full_download_only"],
                "No verified full transfer checkpoint under the held attempt")
        dest = bound_destination(android, directory, before, pattern, shared)
        stage = bound_path(android, directory, before["temp_path"], r"download-[0-9]+\.part", pattern)
        output, checkpoint = android.file(dest), android.file(stage)
        report["at_cut"].update(output_bytes=size(output), output_sha256=output[1], stage_sha256=checkpoint[1])
        report["at_cut"]["path"] = verify_cut(marker, output, checkpoint, hits * chunk)
        require(server.requests == INITIAL_REQUESTS and android.file(neighbor) == marker_file,
                "Unexpected requests or unrelated file change before the cut")

        phase(report, "force-stop without resuming the held copy")
        require(dbg.poll() is None and android.pid() == old_pid, "Held process disappeared before force-stop")
        android.shell("am", "force-stop", PACKAGE)
        until(lambda: android.pid() is None, "browser process force-stopped", seconds=10)
        wait_marker(dbg, transcript, "DISCONNECTED_AFTER_HOLD", seconds=10)
        require(dbg.wait(timeout=5) == 0, "Debugger did not confirm disconnect while held")
    require(snapshot(android, work) == (before, segments), "Stopped SQLite row changed before relaunch")
    require(android.file(dest) == output and android.file(stage) == checkpoint and android.file(neighbor) == marker_file,
            "Partial output, checkpoint, or unrelated file changed across force-stop")
    report["force_stop_held"] = True

    phase(report, "relaunch; leave the partial destination alone and reject file actions")
    android.start(ACTIVITY)
    pid = android.pid()
    require(pid and pid != old_pid, "The browser did not start in a new process")
    android.start(DOWNLOADS)
    status(android, "FAILED")
    recovered, saved = snapshot(android, work)
    verify_occupied(recovered)
    unchanged = ("id", "dest_path", "destination_key", "temp_path", "downloaded_bytes", "total_bytes", "supports_range",
                 "verified_range_url", "etag", "full_download_only")
    require(all(recovered[key] == before[key] for key in unchanged) and saved == segments,
            "Recovery changed the reservation or its verified checkpoint")
    require(android.file(dest) == output and android.file(stage) == checkpoint and android.file(neighbor) == marker_file
            and server.requests == INITIAL_REQUESTS, "Relaunch modified a fixture file or re-fetched bytes")
    report["relaunch"] = {"ui_and_db": "FAILED", "reason": "occupied destination", "files_preserved": True}
    # Measure the process displaying the recovered card, rather than assuming
    # it is the process originally started for the browser's launcher Activity.
    viewing_pid = android.pid()
    require(viewing_pid is not None, "Recovered Downloads was not running")
    check_not_offered(android, report["relaunch"])
    reopen_pid = android.pid()
    if reopen_pid != viewing_pid:
        report["relaunch"]["changed_pid"] = [pid, viewing_pid, reopen_pid]
    require(reopen_pid == viewing_pid, "Browser process changed during file-action checks")
    after_actions, after_segments = snapshot(android, work)
    changed = [key for key in recovered if after_actions[key] != recovered[key]]
    require(not changed and after_segments == saved, f"File actions changed SQLite fields: {','.join(changed) or 'segments'}")
    require(android.file(dest) == output and android.file(stage) == checkpoint and android.file(neighbor) == marker_file
            and server.requests == INITIAL_REQUESTS, "File actions changed the interrupted download")

    phase(report, "move the short output aside and use Retry, not Start over")
    parked = str(Path(dest).with_name(PARKED))
    bound_path(android, directory, parked, re.escape(PARKED), pattern)
    android.app("test", "!", "-e", parked)
    android.app("test", "!", "-L", parked)
    android.app("mv", "-n", dest, parked)
    android.app("test", "!", "-e", dest)
    require(android.file(parked) == output and android.file(stage) == checkpoint, "Could not park the occupied file intact")
    android.tap("card_btn_pause_resume")
    android.tap("button1", "Retry")
    try:
        status(android, "COMPLETED")
    except CheckFailed:
        with suppress(Exception):
            observed, _ = snapshot(android, work)
            report["observed_after_retry"] = {"status": observed["status"], "reason": (observed["error_message"] or "")[:140]}
        raise
    finished, _ = snapshot(android, work)
    final = android.file(dest)
    require(finished["id"] == before["id"] and finished["dest_path"] == dest and finished["destination_key"] == before["destination_key"]
            and finished["status"] == "COMPLETED" and finished["attempt_token"] and finished["attempt_token"] != before["attempt_token"]
            and finished["finished_time"] > 0 and finished["temp_path"] is None and finished["error_message"] is None
            and finished["total_bytes"] == finished["downloaded_bytes"] == LENGTH and not finished["full_download_only"]
            and size(final) == LENGTH and final[1] == DIGEST and finished["published_identity"] == final[0],
            "Retry did not commit the expected bytes at the same reservation")
    entries = set(android.app("ls", "-1A", directory).decode().splitlines())
    stage_name = Path(stage).name
    require(entries - {stage_name} == {NAME, PARKED, NEIGHBOR}
            and (stage_name not in entries or android.file(stage) == checkpoint)
            and android.file(parked) == output and android.file(neighbor) == marker_file and server.requests == INITIAL_REQUESTS,
            "Retry re-fetched data, changed unrelated files, or created extra output")
    report["retry"] = {"status": "COMPLETED", "bytes": LENGTH, "sha256": final[1], "new_requests": 0,
                       "parked_and_unrelated_preserved": True, "staging_cleaned_up": stage_name not in entries}
    report.update(phase="complete", result="pass")
    return dest, final


def run_case(android, work, directory, report, cut):
    with fixture() as server:
        reverse = f"tcp:{server.server_port}"
        android.command("reverse", reverse, reverse)
        try:
            return case(android, work, server, directory, report, cut)
        finally:
            report["http"] = server.requests[:8]
            android.command("reverse", "--remove", reverse)


def storage_blocked(result):
    if not result.returncode:
        return False
    detail = (result.stderr or result.stdout).decode(errors="replace").strip()[-220:]
    # Only a filesystem rejection qualifies as a skip. A transport/runner or
    # command error must still fail closed instead of pretending storage is unsupported.
    require(re.search(r"Permission denied|Operation not permitted|Read-only file system|No such file or directory", detail),
            f"Unexpected shared-storage probe error: {detail}")
    return True


def probe_shared(android, report):
    phase(report, "probe ordinary app-UID writes and rename inside public Downloads")
    android.app("test", "!", "-e", SHARED_PATH)
    android.app("test", "!", "-L", SHARED_PATH)
    result = android.shell("run-as", PACKAGE, "mkdir", SHARED_PATH, check=False)
    if storage_blocked(result):
        report.update(result="blocked", reason="Public Downloads fixture directory could not be created by the app UID")
        return False
    probe = f"{SHARED_PATH}/storage-probe.part"
    neighbor = f"{SHARED_PATH}/{NEIGHBOR}"
    script = f"set -C; cat > {shlex.quote(probe)} && mv -n {shlex.quote(probe)} {shlex.quote(neighbor)}"
    result = android.shell("run-as", PACKAGE, "sh", "-c", script, check=False, data=NEIGHBOR_BYTES)
    if storage_blocked(result):
        report.update(result="blocked", reason="Public Downloads rejected app-UID file creation or rename")
        return False
    require(android.app("cat", neighbor) == NEIGHBOR_BYTES, "Could not read back the shared-storage probe")
    report["app_uid_write_rename"] = True
    return True


def use_shared(android, work, final):
    # Clear only the completed synthetic row via the regular UI. Do not reset
    # package data or remove files, including the earlier fixture output.
    status(android, "COMPLETED")
    android.tap("btn_overflow_menu")
    android.tap("menu_option_clear_completed")
    until(lambda: snapshot(android, work, allow_empty=True) is None, "completed fixture history cleared")
    require(android.file(final[0]) == final[1], "Clearing fixture history removed or changed the first output")
    android.shell("am", "force-stop", PACKAGE)
    until(lambda: android.pid() is None, "browser stopped for optional shared-storage configuration")
    android.app("test", "!", "-e", PREFERENCES + ".bak")
    android.write_new(PREFERENCES + ".mid-copy", (f'<?xml version="1.0" encoding="utf-8"?><map>'
                      f'<string name="default_download_dir">{SHARED_PATH}</string>'
                      '<boolean name="category_subfolders" value="false" /></map>').encode())
    android.app("mv", PREFERENCES + ".mid-copy", PREFERENCES)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--work", required=True, type=Path)
    parser.add_argument("--apk-sha256", required=True)
    args = parser.parse_args()
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("RUNNER_ENVIRONMENT") != "github-hosted":
        parser.error("This check requires a disposable GitHub-hosted AVD")
    commit = os.environ.get("GITHUB_SHA", "")
    require(re.fullmatch(r"[a-f0-9]{40}", commit) and re.fullmatch(r"[a-f0-9]{64}", args.apk_sha256),
            "Missing exact checkout or APK digest")
    private = {"storage": "app-private", "result": "not-passed"}
    shared = {"storage": "shared-downloads", "result": "not-run"}
    report = {"commit": commit, "apk_sha256": args.apk_sha256, "result": "not-passed", "cases": [private, shared]}
    android = Emulator(args.adb)
    try:
        android.verify_target()
        report["android"] = {"api": 34, "abi": android.text("getprop", "ro.product.cpu.abi")}
        cut = copy_cut((Path(__file__).resolve().parents[1] / PUBLISHER).read_text())
        report["copy_cut"] = {"source_line": cut[0], "chunk_writes": cut[1], "chunk_capacity": cut[2]}
        directory = prepare(android)
        android.write_new(f"{directory}/{NEIGHBOR}", NEIGHBOR_BYTES)
        final = run_case(android, args.work, directory, private, cut)
        require(final is not None, "App-private publication case did not run")
        if probe_shared(android, shared):
            shared["result"] = "not-passed"
            use_shared(android, args.work, final)
            run_case(android, args.work, SHARED_PATH, shared, cut)
            require(android.file(final[0]) == final[1], "Optional storage case changed the first fixture output")
        passed = [item["at_cut"]["path"] for item in report["cases"] if item["result"] == "pass"]
        require("mid-copy" in passed, "No real mid-copy path observed; hard-link evidence is separate")
        report["hard_link_observed"] = "hard-link-before-commit" in passed
        report["result"] = "pass-with-shared-blocked" if shared["result"] == "blocked" else "pass"
    except Exception as failure:
        report["error"] = str(failure).strip()[-360:]
        if "android" in report:
            with suppress(Exception):
                report["browser_lifecycle"] = browser_events(android)
        raise
    finally:
        data = (json.dumps(report, sort_keys=True, separators=(",", ":")) + "\n").encode()
        require(len(data) < 3400, "Mid-copy result exceeded annotation budget")
        (args.work / "proof/download-mid-copy.json").write_bytes(data)
        encoded = data.decode().rstrip("\n").replace("%", "%25")
        print(f"::notice title=Android mid-copy recovery result::BROWSER_MID_COPY_RESULT {sha256(data).hexdigest()} {encoded}", flush=True)


if __name__ == "__main__":
    main()

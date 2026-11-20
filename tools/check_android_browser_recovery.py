#!/usr/bin/env python3
"""One real-emulator batch for session recovery, renderer loss and privacy clearing."""
import argparse
import base64
from hashlib import sha256
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
from tempfile import TemporaryDirectory
import time

from check_android_download_batch import RUNNER, compact, installed, parse_instrumentation
from check_android_download_publication import CheckFailed, Emulator, PACKAGE, SERIAL, require, until

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tests" / "fixtures"))
import browser_recovery as fixtures

PREFIX = "com.privacy.browser."
RECOVERY = PREFIX + "BrowserRecoveryCiTest#"
PRIVACY = PREFIX + "BrowserPrivacyCiTest#"
ACTIVITY = "com.privacy.browser.MainActivity"
STATE = "files/saved_tabs/"
ONE = f"http://127.0.0.1:{fixtures.PORT}/session/one"
PENDING = "a GET /session/held"
READY = "pending navigation held; ready for host force-stop"
EVIDENCE = "INSTRUMENTATION_STATUS: browser_recovery_evidence="
ID = r"[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"

METHODS = {
    "session-native-same-url": PREFIX + "TabSessionDeviceTest#nativeSameAddressHistoryCanBeDetectedCapturedAndRestored",
    "session-background": RECOVERY + "backgroundCommitDoesNotOverwriteForegroundEdit",
    "renderer-restore": RECOVERY + "restoreRendererAndRejectOldNativeConfirmation",
    "home-bookmarks": PREFIX + "BrowserViewLifecycleTest#bundledHomeCanReadRecentBookmarksThroughOnlyTheNewEndpoint",
    "private-entry": PRIVACY + "nativePrivateEntriesDoNotOpenRegularTabs",
    "clear-cancel": PRIVACY + "cancelConfirmationKeepsAllSeededData",
    "clear-http-cache": PRIVACY + "clearOnlyUrlCacheAndObserveRealRefetch",
    "clear-legacy-storage": PRIVACY + "legacyStorageReportsRequestAndObservesProviderOutcome",
}
RESTARTS = {
    "clear-cookies": ("clearOnlyCookiesOnBothOrigins", "cookieRemovalPersistsAcrossRestart"),
    "clear-history-tabs": ("clearOnlyHistoryAndTabs", "historyAndTabRemovalPersistsAcrossRestart"),
}


def args(selector, **extras):
    result = ["am", "instrument", "-w", "-r", "-e", "browser_recovery", "ci-avd", "-e", "class", selector]
    for key, value in extras.items():
        result.extend(("-e", key, str(value)))
    return (*result, RUNNER)


def result_for(output, selector):
    result = parse_instrumentation(output, selector)
    observed = [compact(line.removeprefix(EVIDENCE)) for line in output.splitlines() if line.startswith(EVIDENCE)]
    if observed:
        result["evidence"] = observed[:4]
    return result


def run(android, selector, **extras):
    result = android.shell(*args(selector, **extras), timeout=65, check=False)
    return result_for((result.stdout + result.stderr).decode(errors="replace"), selector)


def clear_disposable_profile(android, fixture):
    android.verify_target()
    require(android.text("pm", "clear", "--user", "0", PACKAGE) == "Success", "Could not clear disposable browser profile")
    require(android.pid() is None, "Previous browser process still alive")
    fixture.reset()
    android.shell("pm", "grant", "--user", "0", PACKAGE, "android.permission.POST_NOTIFICATIONS")


def stop(android):
    android.shell("am", "force-stop", PACKAGE)
    until(lambda: android.pid() is None, "force-stop ended the browser process", seconds=8)


def checkpoint(android):
    raw = android.app("cat", STATE + "index.json")
    require(0 < len(raw) <= 4096, "Unexpected fixture session index size")
    index = json.loads(raw)
    require(index.get("version") == 1 and len(index.get("tabs", [])) == 1, "Unexpected saved tab inventory")
    row, = index["tabs"]
    ident, token = row.get("id", ""), row.get("state") or ""
    require(re.fullmatch(ID, ident) and re.fullmatch(ID, token) and index.get("selected") == ident
            and row.get("url") == ONE and not row.get("renderer_lost"), "No saved native checkpoint at the last committed URL")
    path = STATE + f"state-{ident}-{token}.bin"
    blob = android.app("cat", path)
    require(32 < len(blob) <= 4 * 1024 * 1024 + 256 and blob.startswith(b"TSB1"), "No valid-sized native state file at the saved reference")
    return raw, ident, sha256(blob).hexdigest()


def stage_and_cut(android, fixture, work):
    method = RECOVERY + "stagePendingNavigation"
    command = [android.adb, "-s", SERIAL, "shell", "-T", shlex.join(args(method))]
    with TemporaryDirectory(prefix="pending-navigation-", dir=work) as tmp:
        log = Path(tmp) / "stage.log"
        with log.open("wb") as out, subprocess.Popen(command, stdout=out, stderr=subprocess.STDOUT) as process:
            try:
                def ready():
                    text = log.read_text(errors="replace")[-16000:]
                    if EVIDENCE + READY in text:
                        return True
                    require(process.poll() is None, f"Pending navigation fixture did not stage: {compact(text[-1400:])}")
                    return False

                until(ready, "live pending navigation held before sending HTTP headers", seconds=43)
                before = checkpoint(android)
                old = android.pid()
                require(old is not None and process.poll() is None, "No live app process at the cut")
                counts = fixture.snapshot()
                require(counts.get(PENDING) == 1 and counts.get("a GET /session/one signed", 0) >= 1
                        and not fixture.held.is_set(), "Held navigation or signed-in control was not observed")
                stop(android)
                require(checkpoint(android) == before, "Force-stop changed the published session index or native state")
            finally:
                fixture.held.set()
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=4)
                    except subprocess.TimeoutExpired:
                        process.kill()
    result = run(android, RECOVERY + "verifyPendingNavigationRecovery", cut_selected=before[1], cut_pid=old)
    result["evidence"] = ["real am force-stop with pending HTTP headers held; published index and native blob identical across cut"] + result.get("evidence", [])
    if result["status"] == "passed":
        require(fixture.snapshot().get(PENDING) == 1, "Pending navigation fetched again after real relaunch")
    return result


def privacy_restart(android, fixture, check, verify):
    first = run(android, PRIVACY + check)
    if first["status"] != "passed":
        return first
    before = fixture.snapshot()
    stop(android)
    fixture.reset()  # wire counts only; browser data is deliberately untouched here
    later = run(android, PRIVACY + verify)
    later["before_restart_requests"] = before
    later["evidence"] = first.get("evidence", []) + later.get("evidence", [])
    if later["status"] != "passed":
        later["detail"] = "After restart: " + later.get("detail", later["status"])
    return later


def provider(android):
    observed = re.search(r"Current WebView package \(name, version\): \(([a-zA-Z0-9_.]+), ([0-9.]+)\)",
                         android.text("dumpsys", "webviewupdate"))
    if not observed:
        return None
    return {"package": observed[1], "version": observed[2]}


def emit(work, record):
    payload = (json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n").encode()
    (work / "proof" / "browser-recovery.json").write_bytes(payload)
    chunks = [payload[i:i + 1800] for i in range(0, len(payload), 1800)]
    checksum = sha256(payload).hexdigest()
    for index, chunk in enumerate(chunks, 1):
        print(f"::notice title=Browser recovery proof::BROWSER_RECOVERY_PROOF {index}/{len(chunks)} {checksum} "
              + base64.b64encode(chunk).decode(), flush=True)


def execute(android, work, fixture, record):
    def case(name, call):
        start = time.monotonic()
        try:
            clear_disposable_profile(android, fixture)
        except (CheckFailed, OSError, subprocess.SubprocessError) as error:
            result = {"status": "not-run", "detail": compact(error)}
        else:
            try:
                result = call()
            except (CheckFailed, OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
                result = {"status": "failed", "detail": compact(error)}
        result["seconds"] = round(time.monotonic() - start, 1)
        result["fixture_requests"] = fixture.snapshot()
        record["cases"][name] = result
        print(f"{name}: {result['status']} ({result['seconds']}s) {result.get('detail', '')}", flush=True)

    case("session-pending-force-stop", lambda: stage_and_cut(android, fixture, work))
    for name, test in METHODS.items():
        case(name, lambda: run(android, test))
    for name, (check, verify) in RESTARTS.items():
        case(name, lambda: privacy_restart(android, fixture, check, verify))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--work", required=True, type=Path)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--test-apk", required=True, type=Path)
    options = parser.parse_args()
    require(os.environ.get("GITHUB_ACTIONS") == "true" and os.environ.get("RUNNER_ENVIRONMENT") == "github-hosted"
            and os.environ.get("GITHUB_REF_NAME") == "arena/bb0ffed3-browser", "Only the requested branch's disposable hosted AVD is supported")
    source = os.environ.get("GITHUB_SHA", "")
    require(re.fullmatch(r"[a-f0-9]{40}", source), "Expected archive-sourced build SHA")
    android = Emulator(options.adb)
    android.verify_target()
    record = {"schema": 1, "source_commit": source, "api": 34, "abi": android.text("getprop", "ro.product.cpu.abi"),
              "webview": provider(android), "app_sha256": installed(android, options.apk, PACKAGE),
              "test_sha256": installed(android, options.test_apk, "com.browser.test"),
              "cases": {name: {"status": "not-run"} for name in ("session-pending-force-stop", *METHODS, *RESTARTS)}}
    try:
        with fixtures.serve() as (fixture, port):
            android.command("reverse", "--no-rebind", f"tcp:{port}", f"tcp:{port}")
            try:
                execute(android, options.work, fixture, record)
            finally:
                android.command("reverse", "--remove", f"tcp:{port}")
    except (CheckFailed, OSError, subprocess.SubprocessError) as error:
        record["setup_or_cleanup_error"] = compact(error)
    finally:
        emit(options.work, record)
    results = [result["status"] for result in record["cases"].values()]
    print("Browser recovery totals: " + ", ".join(f"{name}={results.count(name)}" for name in ("passed", "failed", "blocked", "not-run")))
    return int(any(name in results for name in ("failed", "not-run")) or "setup_or_cleanup_error" in record)


if __name__ == "__main__":
    sys.exit(main())

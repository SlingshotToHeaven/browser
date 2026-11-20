#!/usr/bin/env python3
"""Run the autonomous Android follow-up, without the native mux or performance labs."""
import argparse
import base64
from collections import Counter
from contextlib import ExitStack, contextmanager
import gzip
from hashlib import sha256
from http.server import ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import subprocess
from threading import Thread
import time
from urllib.parse import urlsplit

from check_android_browser_recovery import (
    PACKAGE, PREFIX, CheckFailed, Emulator, args, clear_disposable_profile, compact,
    fixtures as privacy, installed, provider, require, result_for,
)
import blob_frame_followup as frame
import startup_restore as startup
import tab_trust

ROOT = Path(__file__).resolve().parents[1]
TEST_ROOT = ROOT / "app/src/androidTest/java/com/privacy/browser"
FOLLOWUP = "BrowserFollowupCiTest"
EXISTING = {
    "tab-residency": "TabResourcesDeviceTest",
    "interception": "FilterInterceptionDeviceTest",
    "script-policy": "adblock.BrowserScriptPolicyTest",
    "assistant-review": "ai.AssistantReviewDeviceTest",
    "assistant-lifecycle": "ai.AssistantSessionDeviceTest",
    "assistant-reminder-data": "ai.AssistantPersistenceDeviceTest",
    "assistant-storage": "ai.AssistantLegacyDataDeviceTest",
    "bookmark-db": "bookmarks.BookmarkDbHelperDeviceTest",
    "thumbnail-codec": "images.LocalThumbnailStoreDeviceTest",
}
NEW = {
    "private-cold": "coldPrivateIntentRefused",
    "private-warm": "warmPrivateIntentPreservesRegularPage",
    "private-picker": "tabPickerPrivateActionRefused",
    "private-home": "bundledHomePrivateActionRefused",
    "foreign-frame": "foreignFrameBlobIsNotAdmittedButSamePageCanOfferOneAtTopLevel",
    "startup-select": "coldLaunchOnlyLoadsSelectedThenExplicitlySelectedTab",
    "startup-state-missing": "missingNativeStateFallsBackToSelectedUrlWithoutLoadingOthers",
    "startup-link": "incomingLinkWinsBeforeSavedTabCanNavigate",
}
STARTUP_FETCHES = {
    "startup-select": {"GET /tab/30": 1, "GET /tab/7": 1},
    "startup-state-missing": {"GET /tab/30": 1},
    "startup-link": {"GET /incoming": 1},
}


def methods(cls):
    text = (TEST_ROOT / (cls.replace(".", "/") + ".java")).read_text(encoding="utf-8")
    items = re.findall(r"@Test\s+public\s+void\s+(\w+)\s*\(", text)
    require(items and len(items) == len(set(items)), f"No unambiguous authored methods in {cls}")
    return items


def plan():
    require(set(methods(FOLLOWUP)) == set(NEW.values()), "Follow-up's test and runner inventories differ")
    for group, method in NEW.items():
        yield group, f"{FOLLOWUP}#{method}"
    yield "tab-session", "TabSessionDeviceTest#platformBundleWithoutNativeHistoryIsNotARestoredPage"
    for group, cls in EXISTING.items():
        for method in methods(cls):
            yield group, f"{cls}#{method}"


class SilentTabTrust(tab_trust.Handler):
    def log_message(self, *_):
        pass


class CountedStartup(startup.Handler):
    def do_GET(self):
        path = urlsplit(self.path).path
        if self.headers.get("Host") == startup.LOOPBACK and (startup.TAB_PATH.fullmatch(path) or path == "/incoming"):
            state = self.server.fixture
            with state.lock:
                state.counts["GET " + path] += 1
            label = "incoming link" if path == "/incoming" else "tab " + path.rsplit("/", 1)[1]
            self.reply("text/html; charset=utf-8", startup.page(label, "unused", False))
        else:
            self.reply("text/plain", "Not a startup fixture route")


def serve(stack, port, handler, state=None):
    server = stack.enter_context(ThreadingHTTPServer(("127.0.0.1", port), handler))
    server.fixture = state
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    stack.callback(thread.join)
    stack.callback(server.shutdown)
    return server.server_port


@contextmanager
def fixtures(android):
    with ExitStack() as stack:
        privacy_state, privacy_port = stack.enter_context(privacy.serve())
        frame_state, frame_ports = stack.enter_context(frame.serve())
        startup_state = privacy.State()
        ports = [privacy_port, *frame_ports,
                 serve(stack, 8014, CountedStartup, startup_state),
                 serve(stack, 8011, SilentTabTrust), serve(stack, 8012, SilentTabTrust)]
        for port in ports:
            endpoint = f"tcp:{port}"
            android.command("reverse", "--no-rebind", endpoint, endpoint)
            stack.callback(android.command, "reverse", "--remove", endpoint)
        yield privacy_state, frame_state, startup_state


def test_result(output, selector, exit_code):
    try:
        result = result_for(output, selector)
    except (CheckFailed, ValueError) as exc:
        lines = output.splitlines()
        statuses = [line.split(": ", 1)[1] for line in lines if line.startswith("INSTRUMENTATION_STATUS_CODE: ")]
        end = [line for line in lines if line.startswith(("INSTRUMENTATION_RESULT: shortMsg=", "INSTRUMENTATION_FAILED:", "INSTRUMENTATION_CODE:"))]
        result = {"status": "failed", "kind": "runner", "detail": compact(exc),
                  "terminal_hint": compact("; ".join(end[-3:]) or "none"), "status_codes": statuses[-10:]}
    if result["status"] == "passed" and exit_code != 0:
        result.update(status="failed", kind="runner", detail=f"ADB instrumentation exit was {exit_code}")
    return result


def observe_routes(group, result, privacy_state, frame_state, startup_state):
    expected = None
    if group in STARTUP_FETCHES:
        seen = startup_state.snapshot()
        expected = STARTUP_FETCHES[group]
    elif group == "tab-residency":
        seen = startup_state.snapshot()
    elif group.startswith("private-"):
        seen = privacy_state.snapshot()
    elif group == "foreign-frame":
        seen = frame_state.snapshot()
        expected = {"child GET /offer": 2, "parent GET /parent": 1,
                    "ready-top": 1, "down-top": 1, "tap-top": 1, "ready-frame": 1, "down-frame": 1, "tap-frame": 1}
    else:
        return
    result["fixture_requests"] = seen
    if result["status"] == "passed" and expected is not None and seen != expected:
        result.update(status="failed", kind="fixture-observation", detail="Unexpected synthetic fetch or missing independent wire control")


def execute(android, states, results):
    privacy_state, frame_state, startup_state = states
    for test, result in results.items():
        group = result["group"]
        start = time.monotonic()
        try:
            clear_disposable_profile(android, privacy_state)
            frame_state.reset()
            startup_state.reset()
        except (CheckFailed, OSError, subprocess.SubprocessError) as error:
            result.update(status="not-run", detail=compact(error))
        else:
            selector = PREFIX + test
            try:
                invocation = android.shell(*args(selector), timeout=150 if group == "tab-residency" else 75, check=False)
                result.update(test_result((invocation.stdout + invocation.stderr).decode(errors="replace"), selector, invocation.returncode))
            except (CheckFailed, OSError, subprocess.SubprocessError) as error:
                result.update(status="failed", kind="runner", detail=compact(error))
            observe_routes(group, result, privacy_state, frame_state, startup_state)
        result["seconds"] = round(time.monotonic() - start, 1)
        print(f"{group} {test.rsplit('#', 1)[-1]}: {result['status']} ({result['seconds']}s) {result.get('detail', '')}", flush=True)


def emit(work, record):
    raw = (json.dumps(record, separators=(",", ":"), sort_keys=True) + "\n").encode()
    (work / "proof" / "android-functional-followup.json").write_bytes(raw)
    zipped = gzip.compress(raw, mtime=0)
    chunks = [zipped[i:i + 1500] for i in range(0, len(zipped), 1500)]
    for i, chunk in enumerate(chunks, 1):
        print(f"::notice title=Android functional proof::ANDROID_FUNCTIONAL_PROOF {i}/{len(chunks)} {sha256(raw).hexdigest()} "
              + base64.b64encode(chunk).decode(), flush=True)


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--adb", required=True)
    cli.add_argument("--work", required=True, type=Path)
    cli.add_argument("--apk", required=True, type=Path)
    cli.add_argument("--test-apk", required=True, type=Path)
    opt = cli.parse_args()
    require(os.environ.get("GITHUB_ACTIONS") == "true" and os.environ.get("RUNNER_ENVIRONMENT") == "github-hosted"
            and os.environ.get("GITHUB_REF_NAME") == "arena/bb0ffed3-browser", "Expected this branch's disposable hosted AVD")
    source = os.environ.get("GITHUB_SHA", "")
    require(re.fullmatch(r"[a-f0-9]{40}", source), "Expected archive-sourced build SHA")
    android = Emulator(opt.adb)
    android.verify_target()
    results = {selector: {"group": group, "status": "not-run"} for group, selector in plan()}
    record = {"schema": 1, "source_commit": source, "api": 34, "abi": android.text("getprop", "ro.product.cpu.abi"),
              "page_bytes": int(android.text("getconf", "PAGE_SIZE")), "webview": provider(android),
              "app_sha256": installed(android, opt.apk, PACKAGE), "test_sha256": installed(android, opt.test_apk, "com.browser.test"),
              "cases": results}
    try:
        with fixtures(android) as states:
            execute(android, states, results)
    except (CheckFailed, OSError, subprocess.SubprocessError) as error:
        record["setup_or_cleanup_error"] = compact(error)
    finally:
        emit(opt.work, record)
    counts = Counter(r["status"] for r in results.values())
    print("Functional totals: " + ", ".join(f"{status}={counts[status]}" for status in ("passed", "failed", "blocked", "not-run")))
    return int(bool(counts["failed"] or counts["not-run"] or "setup_or_cleanup_error" in record))


if __name__ == "__main__":
    raise SystemExit(main())

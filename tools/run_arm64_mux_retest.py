#!/usr/bin/env python3
"""Prepare the original mux fixtures or run just four positives on a disposable ARM64 phone."""
import argparse
from collections import Counter
from contextlib import contextmanager, suppress
from hashlib import sha256
from http.server import ThreadingHTTPServer
import json
from pathlib import Path
import re
import shlex
import subprocess
from threading import Thread
import time
from urllib.parse import urlsplit

from check_android_download_batch import (
    CASE_TIME, PACKAGE, RUNNER, TEST_PACKAGE, CheckFailed, fixture, installed, parse_instrumentation, require,
)

CLASS = "com.privacy.browser.downloads.Arm64MuxRetest"
CASES = {
    "stream-separate": ("separateTracks", "separate.mp4", {"/video.mp4": (200,), "/audio.mp4": (200,)}),
    "stream-ts": ("mpegTs", "transport.mp4", {"/mux-ts.m3u8": (200,), "/ts-media.m3u8": (200,), "/av.ts": (200,)}),
    "stream-fmp4": ("hlsByteRanges", "fragments.mp4", {"/mux-fragments.m3u8": (200,), "/video.m3u8": (200,),
                                                      "/audio.m3u8": (200,), "/video.mp4": (206, 206), "/audio.mp4": (206, 206)}),
    "stream-aes128": ("encryptedHls", "encrypted.mp4", {"/encrypted.m3u8": (200,), "/fixture.key": (200,), "/encrypted.ts": (200,)}),
}
ROUTES = frozenset(path for _, _, routes in CASES.values() for path in routes)
ENDPOINT = f"tcp:{fixture.PRIMARY}"
EVIDENCE = "INSTRUMENTATION_STATUS: download_batch_evidence="
MUX_STATE = re.compile(r"last row=(?:[A-Z_]+|missing); mux phase=(?:true|false); worker in nativeFFmpegExecute=(?:true|false)")


class AdbTimeout(CheckFailed):
    def __init__(self, command, seconds, stdout):
        super().__init__(f"ADB {command} timed out after {seconds}s")
        self.stdout = stdout


class Device:
    def __init__(self, adb, serial):
        require(re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_.-]{0,95}", serial) and not serial.startswith("emulator-"),
                "Specify the USB serial of the disposable physical device")
        self.adb, self.serial = adb, serial

    def command(self, *args, check=True, timeout=15):
        try:
            result = subprocess.run([self.adb, "-s", self.serial, *args], capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired as error:
            # Avoid putting a device serial (argv) in the report.
            raise AdbTimeout(args[0], timeout, error.stdout or b"") from None
        require(not check or result.returncode == 0, f"ADB {args[0]} failed (exit {result.returncode})")
        return result

    def shell(self, *words, **kwargs):
        return self.command("shell", "-T", shlex.join(words), **kwargs)

    def text(self, *words):
        return self.shell(*words).stdout.decode().strip()


def physical_identity(android):
    require(android.command("get-state").stdout.strip() == b"device", "ADB device is not ready")
    require(android.text("am", "get-current-user") == "0", "Use the disposable device's owner profile (user 0)")
    require(all(android.text("getprop", key) in {"", "0"} for key in ("ro.kernel.qemu", "ro.boot.qemu")), "Emulators are excluded")
    abi = android.text("getprop", "ro.product.cpu.abi")
    api = int(android.text("getprop", "ro.build.version.sdk"))
    require(abi == "arm64-v8a" and api >= 34, "Requires primary arm64-v8a ABI and Android 14 or newer")
    page = int(android.text("getconf", "PAGE_SIZE"))
    require(page > 0 and (page & (page - 1)) == 0, "Could not identify device page size")
    return {"model": android.text("getprop", "ro.product.model"), "android": android.text("getprop", "ro.build.version.release"),
            "api": api, "abi": abi, "page_bytes": page}


def verify_device(android):
    identity = physical_identity(android)
    # -u includes packages uninstalled for this user but still known to the device.
    known = set(android.text("pm", "list", "packages", "-u", "--user", "0", PACKAGE).splitlines())
    require(known.isdisjoint({f"package:{PACKAGE}", f"package:{TEST_PACKAGE}"}),
            "An existing com.browser app or test package was found. Do not reset it; use a clean lab device/profile.")
    return identity


def apk_badging(aapt2, apk, package):
    raw = subprocess.run([aapt2, "dump", "badging", str(apk)], capture_output=True, check=True, timeout=30).stdout.decode()
    require(re.search(rf"^package: name='{re.escape(package)}' ", raw, re.MULTILINE), f"Expected APK ID {package}")
    if package == PACKAGE:
        lines = {line.strip() for line in raw.splitlines()}
        require({"application-debuggable", "native-code: 'arm64-v8a'"}.issubset(lines),
                "Use the normal debuggable ARM64 app APK from the handoff build")


def install_once(android, apk, package):
    result = android.command("install", "--no-replace", "-t", "--user", "0", str(apk.resolve()), timeout=90)
    require(b"Success" in result.stdout.splitlines(), f"Fresh install of {package} did not succeed")
    return installed(android, apk, package)


class MuxFixture(fixture.Fixture):
    def allowed(self):
        route = urlsplit(self.path)
        return self.headers.get("Host") == f"127.0.0.1:{self.server.server_port}" and (
            route.path in ROUTES and not route.query
            or self.command == "GET" and route.path == "/control/requests" and route.query in ROUTES)

    def do_GET(self):
        if self.allowed():
            super().do_GET()
        else:
            self.reply(404, b"", "text/plain")

    def do_HEAD(self):
        if self.allowed():
            super().do_HEAD()
        else:
            self.reply(404, b"", "text/plain")


@contextmanager
def serve(assets, port=fixture.PRIMARY):
    state = fixture.State(assets)
    with ThreadingHTTPServer(("127.0.0.1", port), MuxFixture) as server:
        server.fixture = state
        server.primary_port = server.server_port
        thread = Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            yield state, server.server_port
        finally:
            server.shutdown()
            thread.join(timeout=3)


def fixture_inputs(ffmpeg):
    version = subprocess.run([ffmpeg, "-version"], capture_output=True, check=True, timeout=15).stdout.decode().splitlines()[0]
    all_assets = fixture.media_assets(ffmpeg)  # also decodes H.264 + AAC from the generated TS on the host
    assets = {path: all_assets[path] for path in sorted(ROUTES)}
    return assets, {"ffmpeg": version, "assets": {path: {"bytes": len(body), "sha256": sha256(body).hexdigest()}
                                                  for path, (_, body) in assets.items()}}


def observation(state, expected, assets):
    calls = [{k: request[k] for k in ("path", "method", "code", "start", "end")} for request in state.requests() if request["path"] in ROUTES]
    got = Counter((call["path"], call["code"]) for call in calls if call["method"] == "GET")
    want = Counter((path, code) for path, codes in expected.items() for code in codes)
    ok = got == want
    for path, codes in expected.items():
        if codes == (206, 206):
            ranges = [(call["start"], call["end"]) for call in calls if call["path"] == path and call["method"] == "GET"]
            init = re.search(rb'BYTERANGE="([0-9]+)@0"', assets[path.replace(".mp4", ".m3u8")][1])
            if init is None:
                return False, calls[:24]
            split = int(init[1])
            ok = ok and ranges == [(0, split - 1), (split, len(assets[path][1]) - 1)]
    return ok, calls[:24]


def mux_state(output):
    # If the result is incomplete, keep only the standardized pre-cancel diagnosis.
    markers = (line.removeprefix(EVIDENCE) for line in output.splitlines() if line.startswith(EVIDENCE))
    return [marker for marker in markers if MUX_STATE.fullmatch(marker)][:1]


def run_case(android, method, result, state, expected, assets):
    state.reset()
    selector = f"{CLASS}#{method}"
    start = time.monotonic()
    try:
        execution = android.shell("am", "instrument", "--user", "0", "-w", "-r", "-e", "download_batch", "arm64-lab",
                                  "-e", "class", selector, RUNNER, timeout=CASE_TIME, check=False)
        output = (execution.stdout + execution.stderr).decode(errors="replace")
        try:
            result.update(parse_instrumentation(output, selector))
        except (CheckFailed, ValueError):
            result.update(status="runner-error", detail="No trustworthy named instrumentation result", evidence=mux_state(output))
        if execution.returncode != 0 and result["status"] == "passed":
            result.update(status="runner-error", detail=f"ADB instrumentation exit {execution.returncode}")
    except AdbTimeout as error:
        result.update(status="runner-error", detail=str(error), evidence=mux_state(error.stdout.decode(errors="replace")))
    except (CheckFailed, OSError) as error:
        result.update(status="runner-error", detail=str(error))
    ok, result["fixture_requests"] = observation(state, expected, assets)
    if result["status"] == "passed":
        if not ok:
            result.update(status="failed", detail="Wire observations did not match the expected fixture route/range sequence")
        else:
            matches = [m for item in result.get("evidence", []) if (m := re.fullmatch(
                r"ARM64 Android JNI MP4; decoded frame, AAC packets and MediaPlayer completion; bytes=(\d+); sha256=([0-9a-f]{64})", item))]
            if len(matches) == 1:
                result.update(bytes=int(matches[0][1]), sha256=matches[0][2], visible_audible_playback="pending")
            else:
                result.update(status="runner-error", detail="Missing verified MP4 identity in the test result")
    result["seconds"] = round(time.monotonic() - start, 1)


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--ffmpeg", default="ffmpeg", help="host binary for synthetic generation, not the app muxer")
    cli.add_argument("--check-fixtures", action="store_true", help="generate media and print hashes without contacting Android")
    cli.add_argument("--disposable", action="store_true", help="confirm an authorized spare device with no existing test install")
    cli.add_argument("--serial", help="explicit USB ADB serial (not recorded in the report)")
    cli.add_argument("--adb", default="adb")
    cli.add_argument("--aapt2", default="aapt2")
    cli.add_argument("--apk", type=Path)
    cli.add_argument("--test-apk", type=Path)
    cli.add_argument("--source", help="full Git commit used to build both APKs")
    cli.add_argument("--report", type=Path, help="new JSON file; existing reports are not overwritten")
    args = cli.parse_args()
    if not args.check_fixtures and not (args.disposable and args.serial and args.apk and args.test_apk and args.report
                                        and re.fullmatch(r"[0-9a-f]{40}", args.source or "")):
        cli.error("Device mode needs --disposable, --serial, --apk, --test-apk, --source and --report")
    try:
        assets, input_report = fixture_inputs(args.ffmpeg)
        if args.check_fixtures:
            print(json.dumps(input_report, indent=2))
            return 0
        android = Device(args.adb, args.serial)
        apk_badging(args.aapt2, args.apk, PACKAGE)
        apk_badging(args.aapt2, args.test_apk, TEST_PACKAGE)
        report = {"schema": 1, "source_commit": args.source, "device": verify_device(android), "fixture": input_report,
                  "cases": {case: {"file": name, "status": "not-run", "visible_audible_playback": "not-run"}
                            for case, (_, name, _) in CASES.items()}}
        with args.report.open("x", encoding="utf-8") as sink:
            try:
                with serve(assets) as (state, _):
                    android.command("reverse", "--no-rebind", ENDPOINT, ENDPOINT)
                    try:
                        report["app_sha256"] = install_once(android, args.apk, PACKAGE)
                        report["test_sha256"] = install_once(android, args.test_apk, TEST_PACKAGE)
                        for case, (method, _, expected) in CASES.items():
                            result = report["cases"][case]
                            run_case(android, method, result, state, expected, assets)
                            print(f"{case}: {result['status']} ({result['seconds']}s) {result.get('detail', '')}", flush=True)
                            if result["status"] in {"runner-error", "not-run"}:
                                # Do not relaunch a possibly unfinished attempt in a different test.
                                break
                    finally:
                        with suppress(CheckFailed, OSError):
                            report["reverse_removed"] = android.command("reverse", "--remove", ENDPOINT, check=False).returncode == 0
            finally:
                json.dump(report, sink, indent=2, sort_keys=True)
                sink.write("\n")
        print(f"Automated results: {args.report}. A passed case still requires visible video and audible tone on the device.")
        return int(any(row["status"] != "passed" for row in report["cases"].values()))
    except (CheckFailed, OSError, ValueError, subprocess.SubprocessError) as error:
        cli.exit(1, f"Blocked before a complete automated run: {error}\n")


if __name__ == "__main__":
    raise SystemExit(main())

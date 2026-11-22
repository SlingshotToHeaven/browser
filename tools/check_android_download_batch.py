#!/usr/bin/env python3
"""Run the entire isolated download matrix on one disposable CI AVD."""
import argparse
import base64
from hashlib import file_digest, sha256
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import sqlite3
import subprocess
import sys
from tempfile import TemporaryDirectory
import time

from check_android_download_publication import (
    CheckFailed, DOWNLOADS, Emulator, PACKAGE, SERIAL, bound_path, require, snapshot_bytes, until,
)
from check_android_download_upgrade import db_copy, installed_apk_path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tests" / "fixtures"))
import download_batch as fixture
import media_discovery

TEST_PACKAGE = "com.browser.test"
RUNNER = TEST_PACKAGE + "/androidx.test.runner.AndroidJUnitRunner"
JAVA = "com.privacy.browser."
STAGE = JAVA + "downloads.DownloadControlBatchTest#stageForHostForceStop"
PAUSED, ACTIVE = "relaunch-paused.bin", "relaunch-active.bin"
PRIVATE = r"/data/(?:user/0|data)/com\.browser/files/download-batch-[0-9]+"
CASE_TIME = 73
FRAME_DENSITIES = (320, 560)
FRAME_SIZE = "1080x2340"
FRAME_RECHECK = ("blob-source-switch", *(f"blob-foreign-frame-{dpi}dpi" for dpi in FRAME_DENSITIES))
GENERATED_MEDIA_CASES = {"stream-separate", "stream-ts", "stream-fmp4", "stream-aes128", "stream-query-only", "stream-missing-audio", "stream-short"}

# Each method starts in a cleared *disposable* target profile, even after a failed case.
CASES = {
    "downloads.DownloadControlBatchTest": {
        "queue-wifi": "wifiWaitUsesTheSavedRangeAndKeepsManualPause",
        "queue-power-schedule": "chargingAndScheduleReconcileAnAlreadyRunningJob",
        "queue-live-cap": "twoTransfersCanChangeCapPauseAndCancelWithoutStarvation",
        "service-recreate": "actualForegroundServiceCanBeRecreatedWithoutReplacingItsWorker",
    },
    "downloads.DownloadStorageBatchTest": {
        "storage-saf-refusal": "filesystemWriterRejectsDocumentAndTreeUrisBeforeQueueing",
        "storage-link": "hardLinkPreflightAndProductionPublisher",
        "storage-app-external": "appSpecificExternalStoragePublishesExactBytes",
        "storage-public": "publicDownloadsByTheOrdinaryAppUid",
    },
    "DownloadWebViewBatchTest": {
        "http-webview-scope": "realLongPressKeepsScopeOnlyUntilAForeignHop",
        "http-external": "externalShareLinkIsAnonymous",
        "http-private": "privateIntentNeverNavigatesOrDownloads",
        "blob-queued": "dismissBeforeFetchAndQueuedAdmission",
        "blob-cancel": "cancellationDrainsTheBlobAndTheNextOfferIsIndependent",
        "blob-reload": "sameAddressReloadCannotCompleteOldJob",
        "blob-close-tab": "closingTheSourceTabRevokesTheRunningRead",
        "blob-short": "shortBlobRejectsAndCleansUp",
        "blob-idle-timeout": "realIdleTimeoutCleansUpStalledBlob",
        "blob-collision": "occupiedBlobReservationIsNotOverwrittenOrFetched",
        "blob-foreign-frame": "crossOriginFrameCannotUseTheMainPageBlobIntake",
        "blob-source-switch": "crossingOriginDuringBlobReadDrainsBeforeNewOffer",
    },
    "downloads.DownloadMediaBatchTest": {
        "stream-separate": "separateTracks",
        "stream-ts": "mpegTs",
        "stream-fmp4": "hlsByteRanges",
        "stream-aes128": "encryptedHls",
        "stream-query-only": "queryOnlyHlsKeepsTheEncodedReferencesOnTheSamePath",
        "stream-missing-audio": "refusesMissingAudio",
        "stream-invalid": "refusesNonMedia",
        "stream-short": "refusesShortMedia",
        "stream-mux-error": "refusesMuxError",
        "stream-dash": "refusesDashBeforeFetch",
    },
    "ComicDownloadBatchTest": {
        "comic-ordered-repeat": "orderedArchiveAndRepeatDoNotReplaceExistingArchive",
        "comic-missing": "missingPageStopsArchive",
        "comic-short": "truncatedPageStopsArchive",
        "comic-timeout": "stalledPageHitsNormalReadTimeout",
        "comic-invalid-plan": "malformedAndOversizedPlansCannotOfferAnArchive",
        "comic-close": "closeDuringSlowPage",
        "comic-navigate": "navigateDuringSlowPage",
        "comic-background": "backgroundDuringSlowPage",
        "comic-verify": "verificationRetriesOnlyBlockedPage",
        "comic-verifier-back": "backDismissesVerifierAndDisposesItsView",
    },
    "MediaDiscoveryDeviceTest": {
        "media-options": "directAndPlaylistOptionsAreExplicitAnonymousAndDoNotRepeatOnRefresh",
        "media-spa": "spaCommitDuringMetadataRetiresWorkBeforeAnotherProbeOrDialog",
        "media-switches": "enabledSwitchesGatePerDocumentWorkAndOrderedReaderPlans",
    },
    "downloads.DownloadHistoryDeviceTest": {
        "history-cursor": "realSqlPagesAcrossTiesAndChangedRowsStayFiltered",
        "history-v7-v8": "v7UpgradeBackfillsUnicodeWithoutTouchingPublicationFields",
        "history-list": "scrollAppendsThenDeletionChangesOnlyTheMatchingResult",
    },
    "downloads.CompletedDownloadGrantTest": {
        "grant-single": "viewAndSingleShareUseTheSameReadOnlyCompletedFile",
        "grant-multiple": "multiShareListsEachExactFileAndNeverIncludesPartials",
        "grant-replacement": "issuedUriRejectsReplacementLinksAndNonCanonicalUri",
        "grant-shell": "independentShellUidCanReadOnlyTheGrantedFileUntilRevoked",
    },
}


def digest(path):
    with path.open("rb") as source:
        return file_digest(source, "sha256").hexdigest()


def compact(text, length=290):
    text = re.sub(r"https?://[^\s]+", "<fixture-url>", str(text))
    text = re.sub(r"/data/(?:user/0|data)/com\.browser/[^\s\]\)]+", "<fixture-file>", text)
    return " ".join(text.replace("\x00", "").split())[:length]


def parse_instrumentation(output, selector):
    """Shell exit 0 or a trailing 'OK' is not a named test result."""
    wanted_class, wanted_method = selector.split("#")
    require(len(output) < 180_000, "Oversized runner result")
    status, finished, started, evidence = {}, [], [], []
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, _, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
            status[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.rsplit(" ", 1)[1])
            identity = (status.get("class"), status.get("test"))
            if code == 1:
                started.append(identity)
            elif code in (0, -1, -2, -3, -4):
                finished.append((identity, code, status.get("stack", status.get("stream", ""))))
            elif code == 3 and "download_batch_evidence" in status:
                evidence.append(compact(status["download_batch_evidence"]))
            status = {}
    if not started and not finished:
        hint = next((line.partition("=")[2] or line for line in output.splitlines()
                     if line.startswith(("INSTRUMENTATION_FAILED:", "INSTRUMENTATION_RESULT: shortMsg=",
                                         "INSTRUMENTATION_STATUS: Error="))), "test did not start")
        return {"status": "not-run", "detail": compact(hint)}
    require(started == [(wanted_class, wanted_method)] and len(finished) == 1,
            "Instrumentation started but did not return exactly one terminal result")
    identity, code, detail = finished[0]
    require(identity == started[0] and output.rstrip().endswith("INSTRUMENTATION_CODE: -1"),
            "Instrumentation did not finish the selected method")
    result = {"status": {0: "passed", -1: "failed", -2: "failed", -3: "not-run", -4: "blocked"}[code]}
    if detail and code != 0:
        result["detail"] = compact(detail)
    if evidence:
        result["evidence"] = evidence[:3]
    return result


def instrumentation_args(selector):
    return ("am", "instrument", "-w", "-r", "-e", "download_batch", "ci-avd", "-e", "class", selector, RUNNER)


def installed(android, apk, package):
    expected = digest(apk)
    path = installed_apk_path(android.text("pm", "path", "--user", "0", package), package)
    observed = android.text("run-as", package, "sha256sum", path).split()
    require(observed and observed[0] == expected, f"Installed {package} APK did not match the build")
    return expected


def reset_profile(android, state):
    android.verify_target()
    require(android.text("pm", "clear", "--user", "0", PACKAGE) == "Success", "Could not clear disposable app profile")
    require(android.pid() is None, "Old browser process is still alive")
    state.reset()
    android.shell("dumpsys", "battery", "reset")
    android.shell("svc", "wifi", "enable")
    android.shell("pm", "grant", "--user", "0", PACKAGE, "android.permission.POST_NOTIFICATIONS")


def rows(android, work):
    with db_copy(snapshot_bytes(android), work) as (db, _):
        db.row_factory = sqlite3.Row
        result = {row["file_name"]: dict(row) for row in db.execute("SELECT * FROM downloads")}
        require(set(result) == {ACTIVE, PAUSED}, "Expected exactly the two real fixture downloads")
        for name, row in result.items():
            require(row["url"] == f"http://127.0.0.1:{fixture.PRIMARY}/range/{name}", "Unexpected URL in fixture profile")
            row["segments"] = [tuple(seg) for seg in db.execute(
                "SELECT start_byte,end_byte,current_byte,status FROM download_segments WHERE task_id=? ORDER BY segment_index", (row["id"],))]
        return result


def file_for(android, row, stage=False):
    directory = str(Path(row["dest_path"]).parent)
    key = "temp_path" if stage else "dest_path"
    suffix = r"download-[0-9]+\.part" if stage else re.escape(row["file_name"])
    return bound_path(android, directory, row[key], suffix, PRIVATE)


def request_intervals(state, name):
    return [(c["start"], c["end"], c["conditional"], c["code"], c["cookie"] or c["referer"] or c["auth"])
            for c in state.requests("/range/" + name)]


def force_stop(android, state, work, *, serial=SERIAL, instrument=instrumentation_args):
    # Deliberately ending the instrumentation process is part of this host-driven case.
    initial = [(0, 0, False, 206, False), (0, fixture.LENGTH - 1, True, 206, False)]
    suffix = (fixture.PREFIX, fixture.LENGTH - 1, True, 206, False)
    args = [android.adb, "-s", serial, "shell", "-T", shlex.join(instrument(STAGE))]
    with TemporaryDirectory(prefix="batch-cut-", dir=work) as tmp:
        spool = Path(tmp) / "raw"
        with spool.open("wb") as out, subprocess.Popen(args, stdout=out, stderr=subprocess.STDOUT) as process:
            try:
                def staged():
                    text = spool.read_text(errors="replace")[-5000:]
                    if "download_batch_evidence=ready for host force-stop" in text:
                        return True
                    require(process.poll() is None, f"Could not stage real running worker: {compact(text[-850:])}")
                    return False

                until(staged, "live checkpoint ready for force-stop", seconds=29)
                before = rows(android, work)
                cuts = {}
                for name, expected in ((ACTIVE, "RUNNING"), (PAUSED, "PAUSED")):
                    row = before[name]
                    require(row["status"] == expected and row["downloaded_bytes"] == fixture.PREFIX
                            and row["etag"] == fixture.ETAG and row["published_identity"] is None
                            and row["finished_time"] == 0 and len(row["segments"]) == 1
                            and row["segments"][0][:3] == (0, fixture.LENGTH - 1, fixture.PREFIX)
                            and row["attempt_token"] and request_intervals(state, name) == initial,
                            f"{name}: exact live checkpoint not established")
                    android.app("test", "!", "-e", file_for(android, row))
                    stage = file_for(android, row, stage=True)
                    cuts[name] = android.file(stage)
                    require(cuts[name][0].endswith(f":{fixture.PREFIX}") and cuts[name][1] == sha256(fixture.BODY[:fixture.PREFIX]).hexdigest(),
                            f"{name}: held checkpoint bytes differ")
                old = android.pid()
                require(old and process.poll() is None, "No live process to cut")
                android.shell("am", "force-stop", PACKAGE)
                until(lambda: android.pid() is None, "forced stop ended process", seconds=8)
                require(rows(android, work) == before, "Rows, attempts or ranges changed before relaunch")
                for name in (ACTIVE, PAUSED):
                    require(android.file(file_for(android, before[name], stage=True)) == cuts[name]
                            and request_intervals(state, name) == initial, f"Force-stop changed checkpoint or fetched {name}")
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=4)
                    except subprocess.TimeoutExpired:
                        process.kill()
    require(state.gate(ACTIVE, "release"), "Could not release active HTTP fixture")
    android.start(DOWNLOADS, reset_activity=True)
    until(lambda: request_intervals(state, ACTIVE) == initial + [suffix], "exact suffix resumed automatically", seconds=17)

    def published():
        profile = rows(android, work)
        require(profile[ACTIVE]["status"] != "FAILED", f"Recovered worker failed: {compact(profile[ACTIVE]['error_message'])}")
        return profile if profile[ACTIVE]["status"] == "COMPLETED" else None

    after = until(published, "exact relaunch publication", seconds=18)
    new = android.pid()
    job, old_job = after[ACTIVE], before[ACTIVE]
    require(new and new != old and job["id"] == old_job["id"] and job["attempt_token"] != old_job["attempt_token"]
            and job["attempt_token"] and job["dest_path"] == old_job["dest_path"] and job["downloaded_bytes"] == fixture.LENGTH
            and job["finished_time"] > 0 and job["temp_path"] is None,
            "Recovered download did not commit the same row and reservation with a new attempt")
    require(android.file(file_for(android, job)) == (job["published_identity"], fixture.DIGEST), "Recovered output identity or bytes differ")
    require(after[PAUSED] == before[PAUSED] and request_intervals(state, PAUSED) == initial
            and android.file(file_for(android, after[PAUSED], stage=True)) == cuts[PAUSED], "Manually paused job moved after relaunch")
    require(request_intervals(state, ACTIVE) == initial + [suffix], "Unexpected extra HTTP request")
    return {"status": "passed", "evidence": ["real process cut while running; fresh Downloads launch; saved paused row unchanged"],
            "resumed_from": fixture.PREFIX, "bytes": fixture.LENGTH, "sha256": fixture.DIGEST}


def device_matrix(android, work, state, report, media_error, *, frame_recheck=False):
    def run_case(name, action):
        started = time.monotonic()
        try:
            reset_profile(android, state)
        except (CheckFailed, OSError, subprocess.TimeoutExpired) as error:
            result = {"status": "not-run", "detail": compact(error)}
        else:
            try:
                result = action()
            except (CheckFailed, OSError, ValueError, subprocess.TimeoutExpired) as error:
                result = {"status": "failed", "detail": compact(error)}
        result["seconds"] = round(time.monotonic() - started, 1)
        # Static synthetic routes only, no header values or browsing data in evidence.
        request_counts = {}
        for call in state.requests():
            path = call["path"]
            if path != "unmatched" and len(request_counts) < 34:
                request_counts[path] = request_counts.get(path, 0) + 1
        if request_counts:
            result["fixture_requests"] = request_counts
        report["device"][name] = result
        with (work / "proof" / "download-batch.json").open("w") as out:
            json.dump(report, out, sort_keys=True, indent=2)
            out.write("\n")
        print(f"{name}: {result['status']} ({result['seconds']}s) {result.get('detail', '')}", flush=True)

    def run_named(name, selector):
        def run_method():
            result = android.shell(*instrumentation_args(selector), timeout=CASE_TIME, check=False)
            parsed = parse_instrumentation((result.stdout + result.stderr).decode(errors="replace"), selector)
            require(not result.returncode or parsed["status"] != "passed", "ADB exited unsuccessfully despite a test pass")
            return parsed
        run_case(name, run_method)

    if frame_recheck:
        android.verify_target()
        size, density = android.text("wm", "size"), android.text("wm", "density")
        require(re.fullmatch(r"Physical size: \d+x\d+", size) and re.fullmatch(r"Physical density: \d+", density),
                "Frame recheck needs the unmodified disposable emulator display")
        web_tests = CASES["DownloadWebViewBatchTest"]
        prefix = JAVA + "DownloadWebViewBatchTest#"
        run_named("blob-source-switch", prefix + web_tests["blob-source-switch"])
        report["frame_display"] = {"size": FRAME_SIZE, "verified_densities": []}
        try:
            android.shell("wm", "size", FRAME_SIZE)
            require(f"Override size: {FRAME_SIZE}" in android.text("wm", "size").splitlines() or size == f"Physical size: {FRAME_SIZE}",
                    "Could not set the disposable frame test viewport")
            for dpi in FRAME_DENSITIES:
                android.shell("wm", "density", str(dpi))
                require(f"Override density: {dpi}" in android.text("wm", "density").splitlines() or density == f"Physical density: {dpi}",
                        "Could not set disposable frame test density")
                report["frame_display"]["verified_densities"].append(dpi)
                run_named(f"blob-foreign-frame-{dpi}dpi", prefix + web_tests["blob-foreign-frame"])
        finally:
            try:
                android.shell("wm", "density", "reset")
            finally:
                android.shell("wm", "size", "reset")
        report["frame_display"]["restored"] = android.text("wm", "size") == size and android.text("wm", "density") == density
        require(report["frame_display"]["restored"], "Emulator display overrides did not clear")
        return

    for cls, methods in CASES.items():
        for name, method in methods.items():
            if name in GENERATED_MEDIA_CASES and media_error:
                report["device"][name] = {"status": "blocked", "detail": media_error}
                continue
            run_named(name, JAVA + cls + "#" + method)
    run_case("service-force-stop", lambda: force_stop(android, state, work))


def emit_proof(report, work):
    # Small chunks also remain verifiable through the Checks API if log/artifact downloads fail.
    encoded = (json.dumps(report, sort_keys=True, separators=(",", ":")) + "\n").encode()
    (work / "proof" / "download-batch.json").write_bytes(encoded)
    chunks = [encoded[i:i + 1900] for i in range(0, len(encoded), 1900)]
    proof_hash = sha256(encoded).hexdigest()
    for index, chunk in enumerate(chunks, 1):
        print(f"::notice title=Android download batch proof::DOWNLOAD_BATCH_PROOF {index}/{len(chunks)} {proof_hash} "
              + base64.b64encode(chunk).decode(), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--work", required=True, type=Path)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--test-apk", required=True, type=Path)
    parser.add_argument("--frame-recheck", action="store_true", help="only the frame at two AVD densities and the independent blob source switch")
    args = parser.parse_args()
    require(os.environ.get("GITHUB_ACTIONS") == "true" and os.environ.get("RUNNER_ENVIRONMENT") == "github-hosted"
            and os.environ.get("GITHUB_REF_NAME") == "arena/bb0ffed3-browser", "Only run on the disposable branch CI emulator")
    commit = os.environ.get("GITHUB_SHA", "")
    require(re.fullmatch(r"[0-9a-f]{40}", commit), "Expected a pinned source commit")
    android = Emulator(args.adb)
    android.verify_target()
    inventory = FRAME_RECHECK if args.frame_recheck else (
        *(name for group in CASES.values() for name in group), "service-force-stop")
    report = {"schema": 1, "source_commit": commit, "api": 34,
              "abi": android.text("getprop", "ro.product.cpu.abi"), "page_bytes": android.text("getconf", "PAGE_SIZE"),
              "app_sha256": installed(android, args.apk, PACKAGE), "test_sha256": installed(android, args.test_apk, TEST_PACKAGE),
              "device": {name: {"status": "not-run"} for name in inventory}}
    assets, media_error = None, None
    if not args.frame_recheck:
        try:
            ffmpeg = os.environ.get("FFMPEG_TEST_BIN") or shutil.which("ffmpeg")
            require(ffmpeg is not None, "Host FFmpeg not installed to generate the synthetic media")
            assets = fixture.media_assets(ffmpeg)
        except (CheckFailed, OSError, ValueError, subprocess.SubprocessError) as error:
            media_error = compact(error)
    try:
        from contextlib import ExitStack
        from http.server import ThreadingHTTPServer
        from threading import Thread
        with ExitStack() as stack:
            state, _ = stack.enter_context(fixture.servers(assets))
            ports = [fixture.PRIMARY, fixture.FOREIGN]
            if not args.frame_recheck:
                media = stack.enter_context(ThreadingHTTPServer(("127.0.0.1", media_discovery.PORT), media_discovery.Fixture))
                Thread(target=media.serve_forever, daemon=True).start()
                stack.callback(media.shutdown)
                ports.append(media_discovery.PORT)
            android.shell("wm", "dismiss-keyguard")
            for port in ports:
                android.command("reverse", "--no-rebind", f"tcp:{port}", f"tcp:{port}")
                stack.callback(android.command, "reverse", "--remove", f"tcp:{port}", check=False)
            device_matrix(android, args.work, state, report, media_error, frame_recheck=args.frame_recheck)
    except (CheckFailed, OSError, subprocess.SubprocessError) as error:
        report["setup_or_cleanup_error"] = compact(error)
    finally:
        emit_proof(report, args.work)
    statuses = [r["status"] for r in report["device"].values()]
    print("Device totals: " + ", ".join(f"{state}={statuses.count(state)}" for state in ("passed", "failed", "blocked", "not-run")))
    return int(bool(media_error) or "failed" in statuses or "not-run" in statuses
               or (args.frame_recheck and "blocked" in statuses) or "setup_or_cleanup_error" in report)


if __name__ == "__main__":
    sys.exit(main())

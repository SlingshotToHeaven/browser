#!/usr/bin/env python3
"""Retry the previously blocked public Downloads mid-copy check, not the download matrix."""
import argparse
import base64
from contextlib import suppress
from hashlib import sha256
import json
import os
from pathlib import Path
import re

from check_android_browser_recovery import PACKAGE, CheckFailed, Emulator, args, compact, installed, require, result_for, stop
from check_android_download_mid_copy import (
    NAME, NEIGHBOR, NEIGHBOR_BYTES, PARKED, PUBLISHER, SHARED_DIR, SHARED_PATH,
    browser_events, copy_cut, exit_reasons, run_case, snapshot,
)

SETUP = "com.privacy.browser.PublicCopyFixtureCiTest#preparePublicDownloadsFixture"
STAT = r"\d+:\d+:\d+:\d+"
FILE = rf"(?:{NAME.replace('.', '[.]')}|{NEIGHBOR.replace('.', '[.]')}|{PARKED.replace('.', '[.]')}|download-\d+[.]part)"
PUBLIC_PATH = re.compile(SHARED_DIR + rf"(?:/{FILE})?")


class PublicObserver(Emulator):
    """Observe only the owned public files as the ordinary shell; DB still via run-as."""
    def app(self, *words, data=None):
        if not any(word.startswith("/") and "/arena-mid-copy-fixture" in word for word in words):
            return super().app(*words, data=data)
        absolute = [word for word in words if word.startswith("/")]
        require(data is None and all(PUBLIC_PATH.fullmatch(word) for word in absolute), "Outside the public test folder")
        if words[0] == "mv":
            require(len(words) == 4 and words[1] == "-n" and Path(words[2]).name == NAME
                    and Path(words[3]).name == PARKED and Path(words[2]).parent == Path(words[3]).parent,
                    "Only the known short fixture copy may be parked, never replaced")
        else:
            require(words[0] in {"cat", "ls", "stat", "test"}, "Public preflight/read is not permission escalation")
        return self.shell(*words).stdout


def check(android, work, report):
    stage = android.shell(*args(SETUP), timeout=55, check=False)
    prepared = result_for((stage.stdout + stage.stderr).decode(errors="replace"), SETUP)
    report["ordinary_app_preflight"] = prepared["status"]
    if prepared["status"] == "blocked":
        report.update(result="blocked", detail=prepared.get("detail", "Ordinary app writes not supported"))
        return
    require(prepared["status"] == "passed" and stage.returncode == 0, prepared.get("detail", "App fixture setup did not finish"))
    stats = [item for item in prepared.get("evidence", []) if "stat=" in item]
    require(len(stats) == 1 and re.fullmatch(STAT, stats[0].rsplit("stat=", 1)[1]), "No ordinary-UID file identity")
    expected = stats[0].rsplit("stat=", 1)[1]
    stop(android)
    neighbor = SHARED_PATH + "/" + NEIGHBOR
    readable = android.shell("test", "-r", neighbor, check=False)
    if readable.returncode == 1 and not readable.stdout and not readable.stderr:
        report.update(result="blocked", detail="Shell cannot independently observe the ordinary app-created public fixture")
        return
    require(readable.returncode == 0, "Unexpected shared-path read preflight response")
    observed = android.file(neighbor)
    require(observed[1] == sha256(NEIGHBOR_BYTES).hexdigest(), "App-created public neighbor hash mismatch")
    if observed[0] != expected:
        report.update(result="blocked", detail="App and shell disagree on filesystem identity; cannot attest the cut independently")
        return
    report["same_public_identity"] = True
    require(snapshot(android, work, allow_empty=True) is None, "Expected no existing jobs on the disposable profile")
    cut = copy_cut((Path(__file__).resolve().parents[1] / PUBLISHER).read_text())
    report["cut"] = {"source_line": cut[0], "chunk_writes": cut[1], "chunk_capacity": cut[2]}
    finished = run_case(android, work, SHARED_PATH, report, cut)
    require(finished is None and report["result"] == "blocked" or finished and report["at_cut"]["path"] == "mid-copy",
            "No independently verified mid-copy cut in public Downloads")


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
    android = PublicObserver(opt.adb)
    android.verify_target()
    report = {"storage": "shared-downloads", "result": "not-passed", "source_commit": source,
              "api": 34, "abi": android.text("getprop", "ro.product.cpu.abi"),
              "app_sha256": installed(android, opt.apk, PACKAGE), "test_sha256": installed(android, opt.test_apk, "com.browser.test")}
    try:
        check(android, opt.work, report)
    except Exception as failure:
        report.update(result="failed", error=compact(failure))
        with suppress(Exception):
            report["browser_lifecycle"] = browser_events(android)
        with suppress(Exception):
            report["browser_exit_reasons"] = exit_reasons(android)
    finally:
        raw = (json.dumps(report, sort_keys=True, separators=(",", ":")) + "\n").encode()
        (opt.work / "proof" / "android-public-copy.json").write_bytes(raw)
        require(len(raw) <= 3100, "Public copy record exceeded annotation budget")
        print(f"::notice title=Public Downloads proof::ANDROID_PUBLIC_COPY_PROOF {sha256(raw).hexdigest()} "
              + base64.b64encode(raw).decode(), flush=True)
        print(f"Public Downloads: {report['result']}: {report.get('error', report.get('detail', report.get('phase', '')))}", flush=True)
    return int(report["result"] not in {"pass", "blocked"})


if __name__ == "__main__":
    raise SystemExit(main())

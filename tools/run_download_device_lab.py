#!/usr/bin/env python3
"""Run DL-01–DL-06 synthetic checks on a fresh, explicitly selected spare ARM64 device."""
import argparse
from contextlib import contextmanager
from hashlib import sha256
import json
import os
from pathlib import Path
import re
import subprocess
import time
from types import SimpleNamespace
from uuid import uuid4
from xml.etree import ElementTree as ET

import check_android_download_batch as batch
import check_android_download_mid_copy as copy
import check_android_download_publication as publication
import check_android_download_upgrade as upgrade
import check_android_public_copy as public
import run_arm64_mux_retest as mux
from check_android_browser_recovery import provider

PACKAGE, TEST = batch.PACKAGE, batch.TEST_PACKAGE
RECIPIENT = upgrade.RECIPIENT
ROOT = Path(__file__).resolve().parents[1]
MODE = "arm64-download-lab"
MARKER = "files/download-device-lab.token"
ERRORS = (OSError, RuntimeError, ValueError, subprocess.SubprocessError)
SELECTED = {name: batch.JAVA + cls + "#" + method for cls, methods in batch.CASES.items()
            if cls in ("downloads.DownloadControlBatchTest", "downloads.DownloadStorageBatchTest",
                       "DownloadWebViewBatchTest", "downloads.DownloadMediaBatchTest")
            for name, method in methods.items() if name not in mux.CASES}
GRANTS = ("viewAndSingleShareUseTheSameReadOnlyCompletedFile", "multiShareListsEachExactFileAndNeverIncludesPartials",
          "issuedUriRejectsReplacementLinksAndNonCanonicalUri", "independentShellUidCanReadOnlyTheGrantedFileUntilRevoked")


class Phone(public.PublicObserver):
    """Reuse the existing fixture observers, not the CI identification/reset policy."""

    def __init__(self, adb, serial, allowed, candidate):
        super().__init__(adb)
        mux.Device(adb, serial)  # Reuse the explicit physical serial shape check.
        self.serial, self.allowed, self.candidate = serial, allowed, candidate
        self.installed_here, self.reverses = {}, set()
        self.token = uuid4().hex
        self.dump_path = f"/data/local/tmp/arena-download-{self.token}.xml"

    def invoke(self, args, data=None, check=True, timeout=15):
        try:
            result = subprocess.run([self.adb, "-s", self.serial, *args], input=data, capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired:
            raise mux.AdbTimeout(args[0], timeout, b"") from None
        if check:
            publication.require(result.returncode == 0, f"ADB {args[0]} failed (exit {result.returncode})")
        return result

    def known(self, package):
        return f"package:{package}" in self.text("pm", "list", "packages", "-u", "--user", "0", package).splitlines()

    def verify_target(self):
        publication.require(self.command("get-state").stdout.strip() == b"device"
                            and self.text("am", "get-current-user") == "0", "The selected owner device is not ready")

    def verify_installed(self, package, apk):
        publication.require(self.installed_here.get(package) == apk, f"{package} was not installed by this run")
        path = upgrade.installed_apk_path(self.text("pm", "path", "--user", "0", package), package)
        actual = self.text("run-as", package, "sha256sum", path).split()
        publication.require(actual and actual[0] == self.allowed[apk][1], f"Installed {package} hash changed")

    def command(self, *args, data=None, check=True, timeout=15):
        if args[0] == "install":
            apk = Path(args[-1]).resolve()
            publication.require(apk in self.allowed, "Install is limited to the inspected fixture APKs")
            package, expected = self.allowed[apk]
            replace = "-r" in args[1:-1]
            self.verify_target()
            if replace:
                previous = self.installed_here.get(PACKAGE)
                publication.require(package == PACKAGE and apk == self.candidate and previous is not None
                                    and previous != apk, "Only the v7 install made by this run may be replaced")
                self.verify_installed(PACKAGE, previous)
            else:
                publication.require(not self.known(package), f"Existing {package}; refusing to install over it")
            flags = ("-r",) if replace else ("--no-replace",)
            result = self.invoke(("install", *flags, "-t", "--user", "0", str(apk)), timeout=timeout)
            publication.require(b"Success" in result.stdout.splitlines(), f"PackageManager did not install {package}")
            path = upgrade.installed_apk_path(self.text("pm", "path", "--user", "0", package), package)
            actual = self.text("run-as", package, "sha256sum", path).split()
            publication.require(actual and actual[0] == expected, f"Installed {package} bytes did not match")
            self.installed_here[package] = apk
            return result
        if args[0] == "reverse":
            if args[1] == "--remove":
                if args[2] not in self.reverses:
                    raise publication.CheckFailed("Refusing to remove a reverse not created by this run")
                result = self.invoke(args, check=check, timeout=timeout)
                if result.returncode == 0:
                    self.reverses.remove(args[2])
                return result
            ends = args[-2:]
            publication.require(re.fullmatch(r"tcp:[0-9]{1,5}", ends[0]) and ends[0] == ends[1], "Expected loopback TCP ports")
            result = self.invoke(("reverse", "--no-rebind", *ends), timeout=timeout)
            self.reverses.add(ends[0])
            return result
        return self.invoke(args, data=data, check=check, timeout=timeout)

    def reset_candidate(self):
        self.verify_target()
        self.verify_installed(PACKAGE, self.candidate)
        publication.require(self.text("pm", "clear", "--user", "0", PACKAGE) == "Success" and self.pid() is None,
                            "Could not clear this run's own synthetic profile")
        self.shell("pm", "grant", "--user", "0", PACKAGE, "android.permission.POST_NOTIFICATIONS")
        self.app("mkdir", "-p", "files")
        self.write_new(MARKER, self.token.encode("ascii"))

    def hierarchy(self):
        raw = self.shell("uiautomator", "dump", "--compressed", self.dump_path, check=False)
        if raw.returncode or b"dumped to:" not in raw.stdout:
            return None
        try:
            return ET.fromstring(self.shell("cat", self.dump_path).stdout)
        except ET.ParseError:
            return None

    def instrument(self, selector):
        return ("am", "instrument", "--user", "0", "-w", "-r", "-e", "download_batch", MODE,
                "-e", "download_lab_token", self.token, "-e", "class", selector, batch.RUNNER)

    @contextmanager
    def reverse_ports(self, *ports):
        added = []
        try:
            for port in ports:
                end = f"tcp:{port}"
                self.command("reverse", end, end)
                added.append(end)
            yield
        finally:
            for end in reversed(added):
                self.command("reverse", "--remove", end, check=False)


def named(phone, selector):
    result = phone.shell(*phone.instrument(selector), timeout=batch.CASE_TIME, check=False)
    parsed = batch.parse_instrumentation((result.stdout + result.stderr).decode(errors="replace"), selector)
    if parsed["status"] == "not-run" or result.returncode and parsed["status"] == "passed":
        parsed.update(status="runner-error", detail="No trustworthy completed Android test")
    return parsed


def preflight(args):
    aapt2 = args.sdk / "build-tools/34.0.0/aapt2"
    paths = {"candidate": (args.apk, PACKAGE), "test": (args.test_apk, TEST),
             "baseline": (args.baseline, PACKAGE), "recipient": (args.recipient, RECIPIENT)}
    for apk, package in paths.values():
        mux.apk_badging(aapt2, apk, package)
    identities = {key: upgrade.apk_info(args.sdk, apk, package) for key, (apk, package) in paths.items()}
    v7, v8, test, reader = (identities[k] for k in ("baseline", "candidate", "test", "recipient"))
    publication.require(v7["signer"] == v8["signer"] == test["signer"] != reader["signer"]
                        and v7["apk_sha256"] != v8["apk_sha256"] and v8["version_code"] >= v7["version_code"],
                        "Wrong APK signer, build ID or version order")
    permissions = subprocess.check_output([aapt2, "dump", "permissions", args.recipient], text=True, timeout=30)
    publication.require("uses-permission" not in permissions, "Recipient is not an offline, permission-free fixture")
    allowed = {apk.resolve(): (package, identities[key]["apk_sha256"]) for key, (apk, package) in paths.items()}
    phone = Phone(str(args.sdk / "platform-tools/adb"), args.serial, allowed, args.apk.resolve())
    device = mux.verify_device(phone)
    publication.require(not phone.known(RECIPIENT), "Existing fixture recipient; do not touch it")
    device["webview"] = provider(phone)
    device["old_package_presence"] = {package: phone.known(package)
                                      for package in ("com.privacy.browser.injection", "com.privacy.browser.injection.debug")}
    return phone, identities, device


def run(args, phone, report, assets, save):
    results = report["cases"]

    def record(name, action):
        result = results[name]
        started = time.monotonic()
        try:
            action(result)
        except ERRORS as error:
            result.update(status="inconclusive", detail=batch.compact(error))
        finally:
            result["seconds"] = round(time.monotonic() - started, 1)
            print(f"{name}: {result['status']} ({result['seconds']}s)", flush=True)
            save()
        return result["status"]

    def installed_upgrade(result):
        with upgrade.fixture() as server, phone.reverse_ports(server.server_port):
            builds = report["apks"]
            opts = SimpleNamespace(work=args.work, baseline=args.baseline, candidate=args.apk, recipient=args.recipient)
            upgrade.run_case(phone, opts, server, result, builds["baseline"], builds["candidate"], builds["recipient"])
            result.update(status="passed", http=server.events())

    if record("installed-upgrade-and-chooser", installed_upgrade) != "passed":
        return  # Preserve the exact profile for diagnosis; no resetting after an uncertain upgrade.
    mux.install_once(phone, args.test_apk, TEST)

    def cut_work(name):
        work = args.work / name
        work.mkdir()
        (work / "jdi").symlink_to(args.work / "jdi", target_is_directory=True)
        return work

    def precommit(result):
        phone.reset_candidate()
        with publication.fixture() as server:
            publication.run_case(phone, cut_work("precommit"), server, result)
        result["status"] = "passed"

    def private_copy(result):
        phone.reset_candidate()
        result["storage"] = "app-private"
        directory = publication.prepare(phone)
        phone.write_new(f"{directory}/{copy.NEIGHBOR}", copy.NEIGHBOR_BYTES)
        cut = copy.copy_cut((ROOT / copy.PUBLISHER).read_text())
        copy.run_case(phone, cut_work("private-copy"), directory, result, cut)
        result["status"] = "passed" if result["result"] == "pass" else "blocked"

    def public_copy(result):
        phone.reset_candidate()
        result["storage"] = "shared-downloads"
        public.check(phone, cut_work("public-copy"), result, instrument=phone.instrument)
        result["status"] = "passed" if result["result"] == "pass" else "blocked"

    record("storage-precommit", precommit)
    record("storage-private-copy-or-link", private_copy)
    record("storage-public-copy", public_copy)

    with batch.fixture.servers(assets) as (state, _), phone.reverse_ports(batch.fixture.PRIMARY, batch.fixture.FOREIGN):
        def device_method(result, name, selector):
            phone.reset_candidate()
            state.reset()
            if name == "queue-wifi":
                wifi = phone.text("settings", "get", "global", "wifi_on")
                if wifi not in {"0", "1"}:
                    result.update(status="blocked", detail="Could not identify the original Wi-Fi setting")
                    return
                try:
                    result.update(named(phone, selector))
                finally:
                    phone.shell("svc", "wifi", "enable" if wifi == "1" else "disable")
            elif name == "queue-power-schedule":
                if "UPDATES STOPPED" in phone.text("dumpsys", "battery"):
                    result.update(status="blocked", detail="Pre-existing battery simulation; not changed")
                    return
                try:
                    result.update(named(phone, selector))
                finally:
                    phone.shell("dumpsys", "battery", "reset")
            else:
                result.update(named(phone, selector))
            result["requests"] = [{k: r[k] for k in ("path", "method", "code", "start", "end", "cookie", "origin_only", "conditional")}
                                  for r in state.requests() if r["path"] and not r["path"].startswith("/control/")][:32]

        selectors = dict(SELECTED)
        selectors.update({"grant-provider-" + method: batch.JAVA + "downloads.CompletedDownloadGrantTest#" + method for method in GRANTS})
        for name, selector in selectors.items():
            if record(name, lambda result: device_method(result, name, selector)) in {"inconclusive", "runner-error"}:
                return

        def force_stop(result):
            phone.reset_candidate()
            state.reset()
            result.update(batch.force_stop(phone, state, args.work, serial=phone.serial, instrument=phone.instrument))

        if record("service-force-stop", force_stop) != "passed":
            return

    phone.reset_candidate()
    with mux.serve(assets) as (state, _), phone.reverse_ports(batch.fixture.PRIMARY):
        for case, (method, _, expected) in mux.CASES.items():
            result = results[case]
            mux.run_case(phone, method, result, state, expected, assets)
            print(f"{case}: {result['status']}", flush=True)
            save()
            if result["status"] != "passed":
                # Do not resume a possibly stalled native worker in another case.
                break


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--disposable", action="store_true")
    cli.add_argument("--serial", required=True)
    cli.add_argument("--ffmpeg", required=True)
    for name in ("sdk", "apk", "test-apk", "baseline", "recipient", "work"):
        cli.add_argument("--" + name, type=lambda p: Path(p).expanduser().resolve(), required=True)
    cli.add_argument("--source-base", required=True)
    cli.add_argument("--source-tree", required=True)
    cli.add_argument("--zip-sha256", required=True)
    args = cli.parse_args()
    if not (args.disposable and all(re.fullmatch(r"[0-9a-f]{40}", h) for h in (args.source_base, args.source_tree))
            and re.fullmatch(r"[0-9a-f]{64}", args.zip_sha256)):
        cli.error("Confirm a fresh spare device and provide the pinned source base, tree and ZIP SHA-256")
    names = ("installed-upgrade-and-chooser", "storage-precommit", "storage-private-copy-or-link", "storage-public-copy",
             *SELECTED, *("grant-provider-" + m for m in GRANTS), "service-force-stop", *mux.CASES)
    report = {"source_base": args.source_base, "source_tree": args.source_tree, "zip_sha256": args.zip_sha256,
              "cases": {name: {"status": "not-run"} for name in names}}
    for case, (_, name, _) in mux.CASES.items():
        report["cases"][case].update(file=name, visible_audible_playback="not-run")
    phone = None
    try:
        args.work.mkdir()  # Existing paths are never reused.
    except OSError as error:
        cli.exit(1, f"Choose a new results directory: {error}\n")
    path = args.work / "download-device-lab.json"

    def save():
        temp = path.with_suffix(".tmp")
        temp.write_text(json.dumps(report, sort_keys=True, indent=2) + "\n", encoding="utf-8")
        temp.replace(path)

    try:
        publication.require(os.environ.get("JAVA_HOME"), "Set JAVA_HOME to the lab JDK 17")
        java = Path(os.environ["JAVA_HOME"]) / "bin/javac"
        jdi = args.work / "jdi"
        jdi.mkdir()
        subprocess.run([java, "--add-modules", "jdk.jdi", "-d", jdi,
                        ROOT / "tests/fixtures/download_publication/PublicationPause.java"], check=True, timeout=45)
        assets = batch.fixture.media_assets(args.ffmpeg)
        report["fixture"] = {"ffmpeg": subprocess.check_output([args.ffmpeg, "-version"], text=True, timeout=15).splitlines()[0],
                             "assets": {p: {"bytes": len(b), "sha256": sha256(b).hexdigest()} for p, (_, b) in assets.items()}}
        phone, report["apks"], report["device"] = preflight(args)
        save()
        run(args, phone, report, assets, save)
    except ERRORS as error:
        report["incomplete"] = batch.compact(error)
        print(f"Batch incomplete: {report['incomplete']}", flush=True)
    finally:
        if phone is not None:
            for end in list(phone.reverses):
                try:
                    phone.command("reverse", "--remove", end, check=False)
                except ERRORS:
                    pass
            report["reverses_remaining"] = sorted(phone.reverses)
            try:
                phone.shell("rm", "-f", phone.dump_path, check=False)
            except ERRORS:
                report["ui_dump_cleanup"] = "unconfirmed"
        save()
    print(f"Synthetic result record: {path}", flush=True)
    return int(any(result["status"] != "passed" for result in report["cases"].values()))


if __name__ == "__main__":
    raise SystemExit(main())

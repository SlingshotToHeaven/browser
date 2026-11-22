#!/usr/bin/env python3
"""Retry only the five unresolved device observations from the pinned Downloads lab run."""
import argparse
from hashlib import sha256
import json
import os
from pathlib import Path
import re
import subprocess

import run_download_device_lab as lab

PRIOR_SHA256 = "6a6a89ddd0850196b91f641e4cc73898a9aedb2fed75a2925353af1accdd678e"
PRIOR_SOURCE = "4a9087603cc70952bc5eeeb9f18423017aa7597e"
LEGACY = ("com.privacy.browser.injection", "com.privacy.browser.injection.debug")
BLOB_CASES = {name: lab.SELECTED[name] for name in ("blob-foreign-frame", "blob-source-switch")}
CUT_CASES = ("storage-precommit", "storage-private-copy-or-link", "storage-public-copy")
CASES = (*CUT_CASES, *BLOB_CASES)
require = lab.publication.require


def prior_report(path):
    data = path.read_bytes()
    require(sha256(data).hexdigest() == PRIOR_SHA256, "Prior lab JSON does not match the returned evidence")
    return json.loads(data)


class RetainedPhone(lab.Phone):
    """Continue only the exact disposable install attested by the prior report."""

    def __init__(self, adb, serial, prior, test_apk, test_info):
        super().__init__(adb, serial, {}, None)
        self.prior, self.test_apk, self.test_info = prior, test_apk, test_info

    def debugger_forwards(self):
        rows = (line.split() for line in self.command("forward", "--list").stdout.decode().splitlines())
        return {(local, remote) for serial, local, remote in (row for row in rows if len(row) == 3)
                if serial == self.serial and remote.startswith("jdwp:")}

    def bind_retained(self):
        require(not any(key in os.environ for key in ("ADB_SERVER_SOCKET", "ANDROID_ADB_SERVER_ADDRESS")),
                "Use the default local ADB server for the retained lab")
        device = lab.mux.physical_identity(self)
        device.update(webview=lab.provider(self), old_package_presence={p: self.known(p) for p in LEGACY})
        require(device == self.prior["device"], "Device, WebView or legacy-package presence differs from the lab receipt")
        for role, package in (("candidate", lab.PACKAGE), ("recipient", lab.RECIPIENT)):
            require(self.installed_hash(package) == self.prior["apks"][role]["apk_sha256"],
                    f"Installed {package} does not match the prior lab receipt")
        require(self.installed_hash(lab.TEST) in self.allowed_test_hashes(), "Existing instrumentation is not from this lab")
        self.forwards_before = self.debugger_forwards()
        pid = self.pid()
        if pid is not None:
            require(not any(remote == "jdwp:" + pid for _, remote in self.forwards_before),
                    "A debugger forward already targets this lab app; leave it untouched")
        self.profile_token = self.read_profile_token()
        return device

    def allowed_test_hashes(self):
        return {self.prior["apks"]["test"]["apk_sha256"], self.test_info["apk_sha256"]}

    def verify_candidate(self):
        self.verify_target()
        require(self.profile_token is not None, "Bind the exact retained synthetic profile first")
        digest = self.prior["apks"]["candidate"]["apk_sha256"]
        require(self.installed_hash(lab.PACKAGE) == digest, "Retained browser APK changed")
        require(self.read_profile_token() == self.profile_token, "Retained synthetic profile marker changed")
        return digest

    def command(self, *args, **kwargs):
        require(args[0] not in {"install", "uninstall"}, "This follow-up never installs or removes the browser")
        return super().command(*args, **kwargs)

    def replace_lab_instrumentation(self):
        self.verify_candidate()
        require(self.test_info["signer"] == self.prior["apks"]["candidate"]["signer"],
                "Build the test APK with the prior lab debug signer")
        installed = self.installed_hash(lab.TEST)
        require(installed in self.allowed_test_hashes(), "Refusing to replace unrelated instrumentation")
        if installed != self.test_info["apk_sha256"]:
            require(lab.batch.digest(self.test_apk) == self.test_info["apk_sha256"], "Test APK changed after inspection")
            result = self.invoke(("install", "-r", "-t", "--user", "0", str(self.test_apk)), timeout=90)
            require(b"Success" in result.stdout.splitlines(), "Could not replace the lab's instrumentation APK")
        self.verify_instrumentation()
        return installed != self.test_info["apk_sha256"]

    def verify_instrumentation(self):
        require(self.installed_hash(lab.TEST) == self.test_info["apk_sha256"], "Wrong installed follow-up instrumentation")
        actual = self.text("pm", "list", "instrumentation", lab.PACKAGE).splitlines()
        require(f"instrumentation:{lab.batch.RUNNER} (target={lab.PACKAGE})" in actual,
                "Instrumentation does not target the retained browser")

    def instrument(self, selector, *, operator_tap=False):
        self.verify_candidate()
        self.verify_instrumentation()
        require(selector in {*BLOB_CASES.values(), lab.public.SETUP}, "Case is outside the focused follow-up")
        return super().instrument(selector, operator_tap=operator_tap)


def run_cases(args, phone, report, save):
    results = report["cases"]
    cuts = lab.publication_cases(args, phone)
    for name in CUT_CASES:
        if lab.record_case(results, save, name, cuts[name]) == "inconclusive":
            return  # Keep the last fixture intact; do not clear a potentially held app.

    with lab.batch.fixture.servers() as (state, _), phone.reverse_ports(lab.batch.fixture.PRIMARY, lab.batch.fixture.FOREIGN):
        for name, selector in BLOB_CASES.items():
            def attempt(result):
                phone.reset_candidate()
                state.reset()
                operator_tap = name == "blob-foreign-frame"
                if operator_tap:
                    print("On the phone, wait for Request frame blob, then tap that button once. Do not use an ADB tap.", flush=True)
                result.update(lab.named(phone, selector, operator_tap=operator_tap))
                result["requests"] = [{k: r[k] for k in ("path", "method", "code")}
                                      for r in state.requests() if r["path"] in {"/frames", "/foreign-frame", "/blob_download.html"}][:12]
            if lab.record_case(results, save, name, attempt) != "passed":
                return


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--disposable", action="store_true", help="confirm this is the spare lab device")
    mode = cli.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check-only", action="store_true", help="inspect identities without device writes")
    mode.add_argument("--reset-confirmed-lab", action="store_true", help="owner authorizes resets of the verified synthetic lab only")
    cli.add_argument("--serial", required=True)
    for name in ("sdk", "prior-lab-json", "test-apk", "work"):
        cli.add_argument("--" + name, type=lambda p: Path(p).expanduser().resolve(), required=True)
    cli.add_argument("--source-commit", required=True)
    cli.add_argument("--source-tree", required=True)
    cli.add_argument("--zip-sha256", required=True)
    args = cli.parse_args()
    if not (args.disposable and all(re.fullmatch(r"[0-9a-f]{40}", h) for h in (args.source_commit, args.source_tree))
            and re.fullmatch(r"[0-9a-f]{64}", args.zip_sha256)):
        cli.error("Confirm the spare device and provide the exact follow-up source identity")
    args.work.mkdir()
    report = {"source_commit": args.source_commit, "source_tree": args.source_tree, "zip_sha256": args.zip_sha256,
              "app_source_commit": PRIOR_SOURCE, "prior_report_sha256": PRIOR_SHA256,
              "mode": "check-only" if args.check_only else "focused", "cases": {name: {"status": "not-run"} for name in CASES}}
    path = args.work / "download-device-followup.json"

    def save():
        temporary = path.with_suffix(".tmp")
        temporary.write_text(json.dumps(report, sort_keys=True, indent=2) + "\n", encoding="utf-8")
        temporary.replace(path)

    phone = None
    started = False
    try:
        prior = prior_report(args.prior_lab_json)
        test_info = lab.upgrade.apk_info(args.sdk, args.test_apk, lab.TEST)
        require(test_info["signer"] == prior["apks"]["candidate"]["signer"]
                and test_info["apk_sha256"] != prior["apks"]["test"]["apk_sha256"],
                "Need newly built follow-up instrumentation with the previous debug signer")
        if not args.check_only:
            require(os.environ.get("JAVA_HOME"), "Set JAVA_HOME to the lab JDK 17")
            jdi = args.work / "jdi"
            jdi.mkdir()
            subprocess.run([Path(os.environ["JAVA_HOME"]) / "bin/javac", "--add-modules", "jdk.jdi", "-d", jdi,
                            lab.ROOT / "tests/fixtures/download_publication/PublicationPause.java"], check=True, timeout=45)
        phone = RetainedPhone(str(args.sdk / "platform-tools/adb"), args.serial, prior, args.test_apk, test_info)
        report.update(device=phone.bind_retained(), app=prior["apks"]["candidate"], followup_test=test_info, preflight="passed")
        save()
        if not args.check_only:
            started = True
            report["replaced_lab_instrumentation"] = phone.replace_lab_instrumentation()
            run_cases(args, phone, report, save)
            phone.verify_candidate()
            phone.verify_instrumentation()
            require(phone.installed_hash(lab.RECIPIENT) == prior["apks"]["recipient"]["apk_sha256"], "Fixture recipient changed")
            require({p: phone.known(p) for p in LEGACY} == report["device"]["old_package_presence"], "Old app presence changed")
            report["app_and_legacy_identity_preserved"] = True
    except lab.ERRORS as error:
        report["incomplete"] = lab.batch.compact(error)
        print(f"Follow-up incomplete: {report['incomplete']}", flush=True)
    finally:
        if started:
            for end in list(phone.reverses):
                try:
                    phone.command("reverse", "--remove", end, check=False)
                except lab.ERRORS:
                    pass
            report["reverses_remaining"] = sorted(phone.reverses)
            try:
                report["new_debugger_forwards_remaining"] = len(phone.debugger_forwards() - phone.forwards_before)
            except lab.ERRORS:
                report["new_debugger_forwards_remaining"] = "unconfirmed"
            try:
                removed = phone.shell("rm", "-f", phone.dump_path, check=False)
                if removed.returncode:
                    report["ui_dump_cleanup"] = "unconfirmed"
            except lab.ERRORS:
                report["ui_dump_cleanup"] = "unconfirmed"
        save()
    print(f"Synthetic result record: {path}", flush=True)
    if args.check_only:
        return int(report.get("preflight") != "passed")
    return int("incomplete" in report or not report.get("app_and_legacy_identity_preserved")
               or bool(report.get("reverses_remaining")) or report.get("new_debugger_forwards_remaining") != 0
               or "ui_dump_cleanup" in report or any(c["status"] != "passed" for c in report["cases"].values()))


if __name__ == "__main__":
    raise SystemExit(main())

"""Run the real JDI launch guard against fake ADB responses; never attach to a device."""
from pathlib import Path
import os
import shutil
import socket
import subprocess
from tempfile import TemporaryDirectory
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = str(Path(os.environ["JAVA_HOME"]) / "bin" / "java") if os.environ.get("JAVA_HOME") else shutil.which("java")
JAVAC = str(Path(os.environ["JAVA_HOME"]) / "bin" / "javac") if os.environ.get("JAVA_HOME") else shutil.which("javac")
ADB_FIXTURE = r'''#!/usr/bin/env python3
import os, sys
assert sys.argv[1:3] == ["-s", "spare-serial"]
base = "/data/app/~~ABC/com.browser-DEF/base.apk"
mode, port = os.environ["LAB_STUB_MODE"], os.environ["LAB_STUB_PORT"]
pid = "241"
responses = {
    ("get-state",): "device",
    ("shell", "am", "get-current-user"): "0",
    ("shell", "getprop", "ro.product.cpu.abi"): "arm64-v8a",
    ("shell", "getprop", "ro.build.version.sdk"): "36",
    ("shell", "getprop", "ro.kernel.qemu"): "1" if mode == "qemu" else "",
    ("shell", "getprop", "ro.boot.qemu"): "",
    ("shell", "pm", "path", "--user", "0", "com.browser"): "package:" + base,
    ("shell", "run-as", "com.browser", "sha256sum", base): ("f" if mode == "hash" else "a") * 64 + "  " + base,
    ("shell", "run-as", "com.browser", "cat", "files/download-device-lab.token"): ("f" if mode == "marker" else "b") * 32,
    ("shell", "run-as", "com.browser", "id", "-u"): "10414",
    ("shell", "ps", "-p", pid, "-o", "PID,UID,NAME"): "PID UID NAME\n 241 10415 com.browser" if mode == "uid" else "PID UID NAME\n 241 10414 com.browser",
    ("shell", "pidof", "com.browser"): pid,
    ("forward", "--list"): f"spare-serial tcp:{port} jdwp:{'242' if mode == 'forward' else pid}",
}
print(responses[tuple(sys.argv[3:])])
'''


@unittest.skipUnless(JAVA and JAVAC, "Requires the JDK 17 host compiler")
class GuardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.work = Path(cls.enterClassContext(TemporaryDirectory()))
        cls.adb = cls.work / "adb-stub"
        cls.adb.write_text(ADB_FIXTURE)
        cls.adb.chmod(0o700)
        subprocess.run([JAVAC, "--add-modules", "jdk.jdi", "-d", cls.work,
                        ROOT / "tests/fixtures/download_publication/PublicationPause.java"], check=True, timeout=35)

    def invoke(self, mode, port, *, physical=True, token=True):
        env = dict(os.environ, LAB_STUB_MODE=mode, LAB_STUB_PORT=str(port))
        for k in ("GITHUB_ACTIONS", "RUNNER_ENVIRONMENT", "BROWSER_DOWNLOAD_LAB_TOKEN"):
            env.pop(k, None)
        if token:
            env["BROWSER_DOWNLOAD_LAB_TOKEN"] = "b" * 32
        proof = ["--arm64-download-lab", self.adb, "spare-serial", "241", "a" * 64] if physical else []
        return subprocess.run([JAVA, "--add-modules", "jdk.jdi", "-cp", self.work, "PublicationPause", *proof, str(port)],
                              capture_output=True, text=True, env=env, timeout=25)

    def test_original_emulator_guard_is_still_required(self):
        result = self.invoke("valid", 9000, physical=False)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Only attach to the disposable CI emulator", result.stderr)

    def test_physical_branch_fails_before_attach_without_verified_target(self):
        for mode, why in (("qemu", "ARM64 lab device"), ("hash", "APK, lab marker"), ("marker", "APK, lab marker"),
                          ("uid", "APK, lab marker"), ("forward", "No matching local forward")):
            with self.subTest(mode=mode):
                result = self.invoke(mode, 9000)
                self.assertNotEqual(0, result.returncode)
                self.assertIn(why, result.stderr)
                self.assertNotIn("ConnectException", result.stderr)
        self.assertIn("Missing bounded physical-lab identity", self.invoke("valid", 9000, token=False).stderr)

    def test_matching_proof_reaches_only_the_local_socket_attach(self):
        # Reserve an unlistened loopback port, so no process can be attached to.
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as unused:
            unused.bind(("127.0.0.1", 0))
            result = self.invoke("valid", unused.getsockname()[1])
        self.assertNotEqual(0, result.returncode)
        self.assertIn("ConnectException", result.stderr)
        self.assertNotIn("READY", result.stdout)


if __name__ == "__main__":
    unittest.main()

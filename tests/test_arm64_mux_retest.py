"""Host checks for the physical mux handoff; never contact ADB or a device."""
from contextlib import closing
from http.client import HTTPConnection
import json
from pathlib import Path
from types import SimpleNamespace
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import run_arm64_mux_retest as mux
from check_android_download_batch import CASES as BATCH


class SafetyTest(unittest.TestCase):
    def device(self, *, abi="arm64-v8a", qemu="", packages="", user="0"):
        values = {("am", "get-current-user"): user, ("getconf", "PAGE_SIZE"): "4096",
                  ("pm", "list", "packages", "-u", "--user", "0", "com.browser"): packages}
        values.update({("getprop", name): value for name, value in {
            "ro.kernel.qemu": qemu, "ro.boot.qemu": "", "ro.product.cpu.abi": abi, "ro.build.version.sdk": "34",
            "ro.product.model": "Test phone", "ro.build.version.release": "14"}.items()})
        android = Mock()
        android.text.side_effect = lambda *words: values[words]
        android.command.return_value = SimpleNamespace(stdout=b"device\n")
        return android

    def test_only_disposable_owner_profile_on_native_arm64(self):
        for kwargs in ({"qemu": "1"}, {"abi": "x86_64"}, {"user": "10"}, {"packages": "package:com.browser"},
                       {"packages": "package:com.browser.test"}):
            with self.subTest(kwargs=kwargs), self.assertRaises(mux.CheckFailed):
                mux.verify_device(self.device(**kwargs))
        self.assertEqual({"model": "Test phone", "android": "14", "api": 34, "abi": "arm64-v8a", "page_bytes": 4096},
                         mux.verify_device(self.device(packages="package:com.browser.unrelated")))
        for serial in ("emulator-5554", "192.0.2.1:5555", "--all"):
            with self.assertRaises(mux.CheckFailed):
                mux.Device("adb", serial)

    def test_install_refuses_replace_and_hashes_the_installed_apk(self):
        android = Mock()
        android.command.return_value = SimpleNamespace(stdout=b"Success\n")
        path = Path("app-debug.apk")
        with patch.object(mux, "installed", return_value="observed hash") as identity:
            self.assertEqual("observed hash", mux.install_once(android, path, mux.PACKAGE))
        android.command.assert_called_once_with("install", "--no-replace", "-t", "--user", "0", str(path.resolve()), timeout=90)
        identity.assert_called_once_with(android, path, mux.PACKAGE)

    def test_inventory_reuses_only_the_four_original_positive_methods(self):
        expected = BATCH["downloads.DownloadMediaBatchTest"]
        self.assertEqual({"stream-separate", "stream-ts", "stream-fmp4", "stream-aes128"}, mux.CASES.keys())
        for case, (method, _, _) in mux.CASES.items():
            self.assertEqual(expected[case], method)


class FixtureTest(unittest.TestCase):
    def request(self, port, path, headers=None):
        with closing(HTTPConnection("127.0.0.1", port, timeout=3)) as client:
            client.request("GET", path, headers=headers or {})
            with client.getresponse() as response:
                return response.status, response.read()

    def test_fixture_uses_original_exact_ranges_and_omits_other_cases(self):
        assets = {"/video.mp4": ("video/mp4", b"abcdef"), "/invalid.ts": ("video/mp2t", b"unused"),
                  "/video.m3u8": ("text/plain", b'BYTERANGE="2@0"')}
        state, port = self.enterContext(mux.serve(assets, port=0))
        for path in ("/invalid.ts", "/control/arm/hold.bin", "/control/requests?/invalid.ts"):
            self.assertEqual(404, self.request(port, path)[0])
        self.assertEqual(404, self.request(port, "/video.mp4", {"Host": "other.invalid"})[0])
        self.assertEqual((206, b"ab"), self.request(port, "/video.mp4", {"Range": "bytes=0-1"}))
        self.assertEqual((206, b"cdef"), self.request(port, "/video.mp4", {"Range": "bytes=2-5"}))
        self.assertEqual(2, len(json.loads(self.request(port, "/control/requests?/video.mp4")[1])))
        self.assertTrue(mux.observation(state, {"/video.mp4": (206, 206)}, assets)[0])
        state.reset()
        self.request(port, "/video.mp4", {"Range": "bytes=0-0"})
        self.request(port, "/video.mp4", {"Range": "bytes=1-5"})
        self.assertFalse(mux.observation(state, {"/video.mp4": (206, 206)}, assets)[0])


class ResultTest(unittest.TestCase):
    METHOD = "mpegTs"
    IDENTITY = f"INSTRUMENTATION_STATUS: class={mux.CLASS}\nINSTRUMENTATION_STATUS: test={METHOD}\n"
    MARKER = "last row=RUNNING; mux phase=true; worker in nativeFFmpegExecute=true"

    def test_automated_pass_does_not_claim_visible_audible_playback(self):
        mp4 = "ARM64 Android JNI MP4; decoded frame, AAC packets and MediaPlayer completion; bytes=2345; sha256=" + "a" * 64
        output = self.IDENTITY + f"INSTRUMENTATION_STATUS_CODE: 1\n{mux.EVIDENCE}{mp4}\nINSTRUMENTATION_STATUS_CODE: 3\n"
        output += self.IDENTITY + "INSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_CODE: -1\n"
        android = Mock()
        android.shell.return_value = SimpleNamespace(stdout=output.encode(), stderr=b"", returncode=0)
        result = {"status": "not-run", "visible_audible_playback": "not-run"}
        mux.run_case(android, self.METHOD, result, mux.fixture.State(), {}, {})
        self.assertEqual(("passed", "pending", 2345, "a" * 64),
                         (result["status"], result["visible_audible_playback"], result["bytes"], result["sha256"]))
        self.assertIn(f"{mux.CLASS}#{self.METHOD}", android.shell.call_args.args)
        with patch.object(mux, "observation", return_value=(False, [])):
            result = {"status": "not-run", "visible_audible_playback": "not-run"}
            mux.run_case(android, self.METHOD, result, mux.fixture.State(), {}, {})
        self.assertEqual(("failed", "not-run"), (result["status"], result["visible_audible_playback"]))

    def test_timeout_preserves_bounded_diagnosis_not_raw_log(self):
        partial = f"{self.IDENTITY}{mux.EVIDENCE}{self.MARKER}\n{mux.EVIDENCE}raw arbitrary text\n"
        android = Mock()
        android.shell.side_effect = mux.AdbTimeout("shell", 73, partial.encode())
        result = {"status": "not-run"}
        mux.run_case(android, self.METHOD, result, mux.fixture.State(), {}, {})
        self.assertEqual("runner-error", result["status"])
        self.assertEqual([self.MARKER], result["evidence"])


if __name__ == "__main__":
    unittest.main()

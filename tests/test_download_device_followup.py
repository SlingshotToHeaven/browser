"""Fail closed on the wrong retained lab, without contacting ADB."""
from contextlib import nullcontext
from hashlib import sha256
from pathlib import Path
from subprocess import CompletedProcess
import sys
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import run_download_device_followup as followup
from run_download_device_followup import lab


class RetainedLabTest(unittest.TestCase):
    OWNER = {"model": "Spare", "android": "16", "api": 36, "abi": "arm64-v8a", "page_bytes": 4096}
    WEBVIEW = {"package": "fixture.webview", "version": "1"}

    def setUp(self):
        self.path = Path(self.enterContext(TemporaryDirectory())) / "test.apk"
        self.path.write_bytes(b"new test bytes")
        self.test = {"apk_sha256": sha256(self.path.read_bytes()).hexdigest(), "signer": "a" * 64}
        self.apks = {"candidate": {"apk_sha256": "b" * 64, "signer": "a" * 64},
                     "test": {"apk_sha256": "c" * 64}, "recipient": {"apk_sha256": "d" * 64}}
        device = dict(self.OWNER, webview=self.WEBVIEW, old_package_presence=dict.fromkeys(followup.LEGACY, False))
        self.phone = followup.RetainedPhone("adb", "spare", {"device": device, "apks": self.apks}, self.path, self.test)
        self.phone.invoke = Mock(return_value=CompletedProcess([], 0, b"Success\n", b""))
        self.phone.verify_target = Mock()
        self.phone.installed_hash = Mock(side_effect={lab.PACKAGE: "b" * 64, lab.TEST: "c" * 64,
                                                       lab.RECIPIENT: "d" * 64}.__getitem__)
        self.phone.read_profile_token = Mock(return_value="e" * 32)
        self.phone.known = Mock(return_value=False)
        self.phone.pid = Mock(return_value=None)
        self.phone.debugger_forwards = Mock(return_value=set())
        self.identity = self.enterContext(patch.object(lab.mux, "physical_identity", return_value=dict(self.OWNER)))
        self.enterContext(patch.object(lab, "provider", return_value=self.WEBVIEW))

    def test_exact_retained_receipt_binds_with_no_writes(self):
        device = self.phone.bind_retained()
        self.assertEqual(device, self.phone.prior["device"])
        self.assertEqual(self.apks["test"]["apk_sha256"], self.phone.bound_test_sha256)
        self.assertEqual("e" * 32, self.phone.profile_token)
        self.phone.invoke.assert_not_called()

    def test_no_reset_before_binding_or_after_marker_changes(self):
        self.phone.text = Mock()
        with self.assertRaisesRegex(RuntimeError, "Bind the exact"):
            self.phone.reset_candidate()
        self.phone.bind_retained()
        self.phone.read_profile_token.return_value = "f" * 32
        with self.assertRaisesRegex(RuntimeError, "marker changed"):
            self.phone.reset_candidate()
        self.phone.text.assert_not_called()
        self.phone.invoke.assert_not_called()

    def test_different_hardware_or_installed_app_does_not_bind(self):
        self.identity.return_value = dict(self.OWNER, api=35)
        with self.assertRaisesRegex(RuntimeError, "differs from the lab receipt"):
            self.phone.bind_retained()
        self.identity.return_value = dict(self.OWNER)
        self.phone.installed_hash.side_effect = None
        self.phone.installed_hash.return_value = "f" * 64
        with self.assertRaisesRegex(RuntimeError, "does not match the prior"):
            self.phone.bind_retained()
        self.assertIsNone(self.phone.profile_token)
        self.phone.invoke.assert_not_called()

    def test_reject_arbitrary_install_and_only_replace_exact_test(self):
        self.phone.bind_retained()
        for verb in ("install", "uninstall"):
            with self.assertRaises(RuntimeError):
                self.phone.command(verb, "app-debug.apk")
        self.phone.invoke.assert_not_called()
        self.phone.text = Mock(return_value=f"instrumentation:{lab.batch.RUNNER} (target={lab.PACKAGE})")
        self.phone.installed_hash.side_effect = ["b" * 64, "f" * 64]
        with self.assertRaisesRegex(RuntimeError, "unrelated instrumentation"):
            self.phone.replace_lab_instrumentation()
        self.phone.invoke.assert_not_called()
        self.phone.installed_hash.side_effect = ["b" * 64, "c" * 64, self.test["apk_sha256"]]
        self.assertTrue(self.phone.replace_lab_instrumentation())
        self.phone.invoke.assert_called_once_with(("install", "-r", "-t", "--user", "0", str(self.path)), timeout=90)

    def test_publication_needs_a_fresh_marker_and_exact_pid(self):
        self.phone.bind_retained()
        self.phone.pid.return_value = "123"
        with self.assertRaisesRegex(RuntimeError, "current run's fresh profile"):
            self.phone.publication_launcher("123")
        self.phone.profile_token = self.phone.read_profile_token.return_value = self.phone.token
        with self.assertRaisesRegex(RuntimeError, "exact process"):
            self.phone.publication_launcher("124")
        args, env = self.phone.publication_launcher("123")
        self.assertEqual(("--arm64-download-lab", "adb", "spare", "123", "b" * 64), args)
        self.assertEqual(self.phone.token, env["BROWSER_DOWNLOAD_LAB_TOKEN"])
        self.assertNotIn(self.phone.token, args)
        self.phone.invoke.assert_not_called()

    def test_only_the_one_trusted_frame_test_accepts_operator_input(self):
        self.phone.bind_retained()
        self.phone.verify_instrumentation = Mock()
        self.assertIn("download_lab_operator_tap", self.phone.instrument(followup.BLOB_CASES["blob-foreign-frame"], operator_tap=True))
        self.assertNotIn("download_lab_operator_tap", self.phone.instrument(followup.BLOB_CASES["blob-source-switch"]))
        with self.assertRaises(RuntimeError):
            self.phone.instrument(followup.BLOB_CASES["blob-source-switch"], operator_tap=True)
        with self.assertRaises(RuntimeError):
            self.phone.instrument(lab.SELECTED["stream-query-only"])

    def test_only_the_new_run_public_folder_is_in_scope(self):
        self.phone.shell = Mock(return_value=CompletedProcess([], 0, b"fixture", b""))
        directory, _ = self.phone.public_scope()
        self.assertEqual(b"fixture", self.phone.app("cat", directory + "/unrelated.bin"))
        self.phone.shell.reset_mock()
        for bad in (lab.copy.SHARED_PATH, directory + "0", directory + "/..", directory + "/unrelated.bin/.."):
            with self.subTest(bad=bad), self.assertRaises(RuntimeError):
                self.phone.app("ls", bad)
        self.phone.shell.assert_not_called()

    def test_bad_receipt_never_supplies_a_retained_build(self):
        with self.assertRaisesRegex(RuntimeError, "does not match the returned evidence"):
            followup.prior_report(self.path)


class SelectedBlobTest(unittest.TestCase):
    def test_each_blob_case_runs_independently_without_cut_or_operator_wait(self):
        for name, selector in followup.BLOB_CASES.items():
            with self.subTest(name=name), patch.object(lab, "publication_cases") as cuts, \
                 patch.object(lab, "named", return_value={"status": "passed"}) as named:
                state = Mock(requests=Mock(return_value=[]))
                phone = Mock(reverse_ports=Mock(return_value=nullcontext()))
                report = {"cases": {name: {"status": "not-run"}}}
                with patch.object(lab.batch.fixture, "servers", return_value=nullcontext((state, None))):
                    followup.run_cases(SimpleNamespace(case=name), phone, report, Mock())
                cuts.assert_not_called()
                phone.reset_candidate.assert_called_once_with()
                named.assert_called_once_with(phone, selector)
                self.assertEqual({k: v["status"] for k,v in report["cases"].items()}, {name: "passed"})


class DebuggerCleanupTest(unittest.TestCase):
    def setUp(self):
        self.work = Path(self.enterContext(TemporaryDirectory()))
        self.android = Mock(serial="spare")
        self.android.publication_launcher.return_value = ((), None)
        self.forward = CompletedProcess([], 0, b"23865\n", b"")
        self.listed = CompletedProcess([], 0, b"spare tcp:23865 jdwp:123\n", b"")
        self.android.command.side_effect = [self.forward, self.listed, CompletedProcess([], 0)]
        self.debugger = Mock()
        self.debugger.poll.return_value = None
        self.enterContext(patch.object(lab.publication.subprocess, "Popen", return_value=self.debugger))
        self.enterContext(patch.dict("os.environ", JAVA_HOME="/test-jdk"))

    def test_cleanup_stops_verified_process_before_removing_owned_forward(self):
        with lab.publication.debugger(self.android, self.work, "123"):
            self.android.stop_publication_target.assert_not_called()
        self.assertEqual(["publication_launcher", "command", "stop_publication_target", "command", "command"],
                         [call[0] for call in self.android.mock_calls])
        self.android.command.assert_called_with("forward", "--remove", "tcp:23865")

    def test_unknown_process_is_not_detached_or_resumed(self):
        self.android.stop_publication_target.side_effect = lab.publication.CheckFailed("Target changed")
        with self.assertRaisesRegex(RuntimeError, "Target changed"), lab.publication.debugger(self.android, self.work, "123"):
            pass
        self.android.command.assert_called_once_with("forward", "--no-rebind", "tcp:0", "jdwp:123")
        self.debugger.terminate.assert_not_called()

    def test_changed_forward_is_not_removed(self):
        self.listed.stdout = b"spare tcp:23865 jdwp:124\n"
        with self.assertRaisesRegex(RuntimeError, "refusing to remove"), lab.publication.debugger(self.android, self.work, "123"):
            pass
        self.android.command.assert_called_with("forward", "--list")
        self.assertEqual(2, self.android.command.call_count)


if __name__ == "__main__":
    unittest.main()

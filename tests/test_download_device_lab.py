"""Exercise the physical Downloads driver's safety boundaries without ADB."""
from contextlib import closing
from http.client import HTTPConnection
from pathlib import Path
from subprocess import CompletedProcess
import sys
import unittest
from unittest.mock import Mock, call

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import run_download_device_lab as lab


class DeviceBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.base = Path("v7.apk").resolve()
        self.candidate = Path("v8.apk").resolve()
        self.phone = lab.Phone("adb", "spare-serial", {self.base: (lab.PACKAGE, "a" * 64),
                                                     self.candidate: (lab.PACKAGE, "b" * 64)}, self.candidate)
        self.phone.invoke = Mock(return_value=CompletedProcess([], 0, b"Success\n", b""))
        self.phone.verify_target = Mock()
        self.phone.known = Mock(return_value=False)
        self.phone.text = Mock(side_effect=self.android_text)

    def android_text(self, *args):
        if args[:2] == ("pm", "path"):
            return "package:/data/app/~~ABC/com.browser-DEF/base.apk"
        if args[:3] == ("run-as", lab.PACKAGE, "sha256sum"):
            return "a" * 64 if self.phone.invoke.call_count == 1 else "b" * 64
        if args[:2] == ("pm", "clear"):
            return "Success"
        raise AssertionError(args)

    def test_fresh_install_checks_bytes_and_uses_no_replace(self):
        self.phone.command("install", str(self.base))
        self.assertEqual(self.base, self.phone.installed_here[lab.PACKAGE])
        self.phone.invoke.assert_called_once_with(("install", "--no-replace", "-t", "--user", "0", str(self.base)), timeout=15)

    def test_no_install_or_clear_over_an_existing_profile(self):
        self.phone.known.return_value = True
        for action in (lambda: self.phone.command("install", str(self.base)),
                       lambda: self.phone.command("install", "-r", str(self.candidate)), self.phone.reset_candidate):
            with self.assertRaises(RuntimeError):
                action()
        self.phone.invoke.assert_not_called()
        self.assertFalse(any(cmd.args[:2] == ("pm", "clear") for cmd in self.phone.text.call_args_list))

    def test_replace_only_the_verified_baseline_this_run_installed(self):
        self.phone.command("install", str(self.base))
        self.phone.command("install", "-r", str(self.candidate))
        self.assertEqual(self.candidate, self.phone.installed_here[lab.PACKAGE])
        self.assertEqual(call(("install", "-r", "-t", "--user", "0", str(self.candidate)), timeout=15), self.phone.invoke.call_args)
        with self.assertRaises(RuntimeError):
            self.phone.command("install", "-r", str(self.candidate))

    def test_reset_refuses_an_apk_that_no_longer_matches(self):
        self.phone.installed_here[lab.PACKAGE] = self.candidate
        self.phone.text.side_effect = lambda *args: ("package:/data/app/~~ABC/com.browser-DEF/base.apk"
                                                     if args[:2] == ("pm", "path") else "wrong hash")
        with self.assertRaisesRegex(RuntimeError, "hash changed"):
            self.phone.reset_candidate()
        self.assertFalse(any(cmd.args[:2] == ("pm", "clear") for cmd in self.phone.text.call_args_list))
        self.phone.invoke.assert_not_called()

    def test_reverse_is_non_rebinding_and_cleanup_is_ownership_scoped(self):
        with self.assertRaises(RuntimeError):
            self.phone.command("reverse", "--remove", "tcp:8024")
        with self.phone.reverse_ports(8024):
            self.assertEqual({"tcp:8024"}, self.phone.reverses)
        self.assertEqual(set(), self.phone.reverses)
        self.assertEqual([call(("reverse", "--no-rebind", "tcp:8024", "tcp:8024"), timeout=15),
                          call(("reverse", "--remove", "tcp:8024"), check=False, timeout=15)], self.phone.invoke.call_args_list)

    def test_zero_test_runner_is_not_a_pass(self):
        self.phone.shell = Mock(return_value=CompletedProcess([], 0, b"OK (0 tests)\nINSTRUMENTATION_CODE: -1\n", b""))
        test = lab.SELECTED["stream-query-only"]
        self.assertEqual("runner-error", lab.named(self.phone, test)["status"])
        self.assertIn(lab.MODE, self.phone.shell.call_args.args)
        self.assertIn(test, self.phone.shell.call_args.args)

    def test_only_download_cases_are_selected(self):
        self.assertIn("stream-query-only", lab.SELECTED)
        self.assertIn("blob-foreign-frame", lab.SELECTED)
        self.assertIn("http-private", lab.SELECTED)
        self.assertTrue(lab.SELECTED.keys().isdisjoint(lab.mux.CASES))
        self.assertFalse(any(key.startswith(("comic-", "history-", "media-")) for key in lab.SELECTED))


class QueryFixtureTest(unittest.TestCase):
    def test_query_only_fixture_requires_exact_encoded_route(self):
        fixture = lab.batch.fixture
        state, (primary, _) = self.enterContext(fixture.servers(ports=(0, 0), assets={
            fixture.QUERY_HLS: (fixture.HLS, b"master"), fixture.QUERY_MEDIA: (fixture.HLS, b"media"),
            fixture.QUERY_SEGMENT: ("video/mp2t", b"transport"),
        }))

        def get(path):
            with closing(HTTPConnection("127.0.0.1", primary.server_port, timeout=3)) as client:
                client.request("GET", path)
                response = client.getresponse()
                return response.status, response.read()

        self.assertEqual((200, b"master"), get(fixture.QUERY_HLS))
        self.assertEqual((200, b"media"), get(fixture.QUERY_MEDIA))
        self.assertEqual((200, b"transport"), get(fixture.QUERY_SEGMENT))
        self.assertEqual(404, get("/query-only/?part=media%2Flist")[0])
        self.assertEqual(404, get(fixture.QUERY_MEDIA.replace("%2F", "/"))[0])
        self.assertEqual(1, len(state.requests(fixture.QUERY_MEDIA)))


if __name__ == "__main__":
    unittest.main()

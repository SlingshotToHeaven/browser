"""Host tests for runner evidence and fixture controls; do not use adb."""
from contextlib import closing, redirect_stdout
from hashlib import sha256
from http.client import HTTPConnection
import base64
import io
import json
from pathlib import Path
import re
import sys
from tempfile import TemporaryDirectory
from threading import Thread
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import check_android_browser_recovery as batch


class FixtureTest(unittest.TestCase):
    def setUp(self):
        self.state, self.port = self.enterContext(batch.fixtures.serve(0))

    def request(self, path, *, site="127.0.0.1", cookie=None, method="GET"):
        headers = {"Host": f"{site}:{self.port}"}
        if cookie:
            headers["Cookie"] = cookie
        with closing(HTTPConnection("127.0.0.1", self.port, timeout=3)) as connection:
            connection.request(method, path, headers=headers)
            with connection.getresponse() as response:
                return response.status, dict(response.getheaders()), response.read()

    def test_login_origins_and_header_values_are_never_recorded(self):
        token = batch.fixtures.COOKIE
        for origin, label in batch.fixtures.HOSTS.items():
            status, headers, _ = self.request("/privacy/login", site=origin)
            self.assertEqual(303, status)
            self.assertEqual("/privacy/markers", headers["Location"])
            self.assertIn("HttpOnly", headers["Set-Cookie"])
            self.assertNotIn("Domain=", headers["Set-Cookie"])
            self.assertIn(b'data-signed="yes"', self.request("/privacy/markers", site=origin, cookie=token)[2])
            self.assertIn(b'data-signed="no"', self.request("/privacy/markers", site=origin, cookie="separate_secret=value")[2])
            counts = self.state.snapshot()
            self.assertEqual(2, counts[f"{label} GET /privacy/markers"])
            self.assertEqual(1, counts[f"{label} GET /privacy/markers signed"])
            self.assertNotIn("separate_secret", json.dumps(counts))
        self.assertEqual(400, self.request("/privacy/markers", site="unexpected.invalid")[0])

    def test_held_navigation_does_not_send_headers_before_release(self):
        completed = []
        worker = Thread(target=lambda: completed.append(self.request("/session/held")))
        worker.start()
        try:
            batch.until(lambda: self.state.snapshot().get(batch.PENDING) == 1, "observed test request", seconds=2)
            self.assertEqual([], completed)
            self.assertFalse(self.state.held.is_set())
            self.assertEqual(200, self.request("/control/release")[0])
        finally:
            self.state.held.set()
            worker.join(3)
        self.assertEqual(200, completed[0][0])
        self.state.reset()
        self.assertEqual({}, self.state.snapshot())
        self.assertFalse(self.state.held.is_set())

    def test_cache_resource_is_fresh_and_renderer_route_is_metadata_only(self):
        status, headers, body = self.request("/privacy/cacheable")
        self.assertEqual((200, b"cache-fixture"), (status, body))
        self.assertEqual("public, max-age=3600", headers["Cache-Control"])
        self.assertEqual("no-store", self.request("/privacy/markers")[1]["Cache-Control"])
        self.assertEqual(200, self.request("/renderer/clip.mp4", method="HEAD")[0])
        self.assertEqual(404, self.request("/renderer/clip.mp4")[0])


class RunnerTest(unittest.TestCase):
    def test_evidence_does_not_replace_a_named_test_result(self):
        selector = batch.RECOVERY + "backgroundCommitDoesNotOverwriteForegroundEdit"
        cls, method = selector.split("#")
        entry = f"INSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={method}\n"
        proof = batch.EVIDENCE + "synthetic observation\nINSTRUMENTATION_STATUS_CODE: 3\n"
        output = entry + "INSTRUMENTATION_STATUS_CODE: 1\n" + proof + entry + "INSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_CODE: -1\n"
        self.assertEqual({"status": "passed", "evidence": ["synthetic observation"]}, batch.result_for(output, selector))
        self.assertEqual("not-run", batch.result_for(proof + "INSTRUMENTATION_CODE: -1\n", selector)["status"])
        with self.assertRaises(batch.CheckFailed):
            batch.result_for(output.replace(method, "wrongMethod"), selector)

    def test_matrix_covers_every_new_device_test_method(self):
        all_methods = {*batch.METHODS.values(), batch.RECOVERY + "stagePendingNavigation",
                       batch.RECOVERY + "verifyPendingNavigationRecovery", batch.PRIVACY_APPLY, batch.PRIVACY_COLD}
        for cls in (batch.RECOVERY, batch.PRIVACY):
            path = ROOT / "app/src/androidTest/java/com/privacy/browser" / (cls.removeprefix(batch.PREFIX).rstrip("#") + ".java")
            expected = {cls + name for name in re.findall(r"@Test public void (\w+)\(", path.read_text())}
            self.assertEqual(expected, {name for name in all_methods if name.startswith(cls)})

    def test_restart_does_not_clear_app_data_and_persists_partial_result(self):
        android = Mock()
        state = batch.fixtures.State()
        with patch.object(batch, "run", side_effect=[{"status": "passed", "evidence": ["first complete"]}, {"status": "failed"}]) as run, \
                patch.object(batch, "stop") as stop:
            outcome = batch.privacy_restart(android, state, "LEGACY_STORAGE")
        stop.assert_called_once_with(android)
        self.assertEqual([batch.PRIVACY_APPLY, batch.PRIVACY_COLD], [call.args[1] for call in run.call_args_list])
        self.assertEqual([{"privacy_choice": "LEGACY_STORAGE"}] * 2, [call.kwargs for call in run.call_args_list])
        self.assertEqual({"status": "failed", "phase": "after-restart", "before_restart_requests": {}, "evidence": ["first complete"]}, outcome)
        android.shell.assert_not_called()

    def test_privacy_matrix_restarts_each_distinct_choice(self):
        android = Mock()
        state = batch.fixtures.State()
        with patch.object(batch, "clear_disposable_profile") as reset, patch.object(batch, "run", return_value={"status": "passed"}), \
                patch.object(batch, "stage_and_cut", return_value={"status": "passed"}), \
                patch.object(batch, "privacy_restart", side_effect=lambda *args: {"status": "passed"}) as restart:
            record = {"cases": {}}
            with redirect_stdout(io.StringIO()):
                batch.execute(android, Path("unused"), state, record)
        self.assertEqual(["CANCEL", "COOKIES", "HTTP_CACHE", "LEGACY_STORAGE", "HISTORY_TABS"],
                         [call.args[2] for call in restart.call_args_list])
        self.assertEqual(len(record["cases"]), reset.call_count)

    def test_report_checksums_match_the_file(self):
        with TemporaryDirectory() as tmp:
            work = Path(tmp)
            (work / "proof").mkdir()
            notice = io.StringIO()
            with redirect_stdout(notice):
                batch.emit(work, {"cases": {"example": "failed"}, "probe": "x" * 4500})
            result = b""
            for i, line in enumerate(notice.getvalue().splitlines(), 1):
                counter, checksum, encoded = line.split("BROWSER_RECOVERY_PROOF ")[1].split()
                self.assertEqual(f"{i}/3", counter)
                result += base64.b64decode(encoded, validate=True)
            self.assertEqual(checksum, sha256(result).hexdigest())
            self.assertEqual(result, (work / "proof/browser-recovery.json").read_bytes())


if __name__ == "__main__":
    unittest.main()

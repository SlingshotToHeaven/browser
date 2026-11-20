"""Tests for the follow-up's local fixture and its strict result accounting."""
import base64
from collections import Counter
from contextlib import closing, redirect_stdout
import gzip
from hashlib import sha256
from http.client import HTTPConnection
import io
import json
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import check_android_functional_followup as followup


class FrameFixtureTest(unittest.TestCase):
    def request(self, port, path, headers=None):
        with closing(HTTPConnection("127.0.0.1", port, timeout=3)) as client:
            client.request("GET", path, headers=headers or {})
            with client.getresponse() as result:
                return result.status, result.read()

    def test_only_child_origin_accepts_bounded_acknowledgments(self):
        state, (parent, child) = self.enterContext(followup.frame.serve((0, 0)))
        self.assertIn(f'http://127.0.0.1:{child}/offer'.encode(), self.request(parent, "/parent")[1])
        status, body = self.request(child, "/offer")
        self.assertEqual(200, status)
        self.assertIn(b'e.isTrusted', body)
        self.assertIn(b'download="fixture.bin"', body)
        for endpoint, path in ((parent, "/ack/tap-frame"), (child, "/ack/unknown")):
            self.assertEqual(404, self.request(endpoint, path)[0])
        self.assertEqual(400, self.request(child, "/ack/tap-frame", {"Host": "unrelated.invalid"})[0])
        self.assertEqual(204, self.request(child, "/ack/tap-frame")[0])
        self.assertEqual({"parent GET /parent": 1, "child GET /offer": 1, "tap-frame": 1}, state.snapshot())
        self.assertEqual(state.snapshot(), json.loads(self.request(parent, "/counts")[1]))


class RunnerTest(unittest.TestCase):
    def test_plan_runs_each_selected_method_once_and_does_not_include_mux(self):
        selected = list(followup.plan())
        tests = [name for _, name in selected]
        self.assertEqual(len(tests), len(set(tests)))
        self.assertFalse(any("DownloadMediaBatchTest" in name or "#nativeSameAddress" in name for name in tests))
        self.assertEqual(len(followup.NEW), sum(group in followup.NEW for group, _ in selected))

    def test_started_but_interrupted_is_not_a_pass(self):
        cls = "com.privacy.browser.BrowserFollowupCiTest"
        method = "coldPrivateIntentRefused"
        text = f"INSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={method}\nINSTRUMENTATION_STATUS_CODE: 1\n"
        result = followup.test_result(text + "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n",
                                      f"{cls}#{method}", 0)
        self.assertEqual("failed", result["status"])
        self.assertIn("Process crashed", result["terminal_hint"])

    def test_a_test_pass_cannot_hide_a_missing_wire_control(self):
        privacy, frame, startup = (followup.privacy.State() for _ in range(3))
        result = {"status": "passed"}
        followup.observe_routes("foreign-frame", result, privacy, frame, startup)
        self.assertEqual("failed", result["status"])
        startup.counts = Counter(followup.STARTUP_FETCHES["startup-link"])
        result = {"status": "passed"}
        followup.observe_routes("startup-link", result, privacy, frame, startup)
        self.assertEqual("passed", result["status"])
        startup.counts["GET /tab/30"] += 1
        followup.observe_routes("startup-link", result, privacy, frame, startup)
        self.assertEqual("failed", result["status"])

    def test_exact_proof_roundtrip(self):
        with TemporaryDirectory() as tmp:
            work = Path(tmp)
            (work / "proof").mkdir()
            output = io.StringIO()
            with redirect_stdout(output):
                followup.emit(work, {"cases": {"case": "blocked"}})
            raw = (work / "proof/android-functional-followup.json").read_bytes()
            seq, checksum, encoded = output.getvalue().split("ANDROID_FUNCTIONAL_PROOF ", 1)[1].split()
            self.assertEqual("1/1", seq)
            self.assertEqual(sha256(raw).hexdigest(), checksum)
            self.assertEqual(raw, gzip.decompress(base64.b64decode(encoded, validate=True)))


if __name__ == "__main__":
    unittest.main()

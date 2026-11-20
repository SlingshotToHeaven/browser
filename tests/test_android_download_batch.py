"""Host tests of the batch fixtures and evidence parser; no Android target is touched."""
from contextlib import closing
from hashlib import sha256
from http.client import HTTPConnection
import json
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
from threading import Thread
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT / "tools"), str(ROOT / "tests" / "fixtures")]
import check_android_download_batch as batch
import download_batch as fixture
import record_download_batch_host as host


class BatchEvidenceTest(unittest.TestCase):
    cls, method = "com.privacy.browser.downloads.SampleTest", "specificOutcome"
    selector = cls + "#" + method

    def runner(self, code, *, expected=True, detail="", end="INSTRUMENTATION_CODE: -1"):
        identity = f"INSTRUMENTATION_STATUS: class={self.cls}\nINSTRUMENTATION_STATUS: test={self.method}\n"
        return (identity + "INSTRUMENTATION_STATUS_CODE: 1\n"
                + "INSTRUMENTATION_STATUS: download_batch_evidence=exact bytes\nINSTRUMENTATION_STATUS_CODE: 3\n"
                + (identity if expected else identity.replace(self.method, "anotherMethod"))
                + "INSTRUMENTATION_STATUS: stack=" + detail + "\n"
                + f"INSTRUMENTATION_STATUS_CODE: {code}\nINSTRUMENTATION_RESULT: stream=\n{end}\n")

    def test_requires_the_exact_method_and_terminal_code(self):
        self.assertEqual({"status": "passed", "evidence": ["exact bytes"]},
                         batch.parse_instrumentation(self.runner(0), self.selector))
        for output in (self.runner(0, expected=False), self.runner(0, end="INSTRUMENTATION_CODE: 0")):
            with self.assertRaises(batch.CheckFailed):
                batch.parse_instrumentation(output, self.selector)

    def test_pass_fail_skip_and_assumption_are_distinct(self):
        self.assertEqual("failed", batch.parse_instrumentation(self.runner(-2, detail="assertion"), self.selector)["status"])
        self.assertEqual("blocked", batch.parse_instrumentation(self.runner(-4, detail="no link"), self.selector)["status"])
        self.assertEqual("not-run", batch.parse_instrumentation(self.runner(-3), self.selector)["status"])
        self.assertEqual("not-run", batch.parse_instrumentation("OK (0 tests)\nINSTRUMENTATION_CODE: -1\n", self.selector)["status"])
        self.assertEqual("not-run", batch.parse_instrumentation("INSTRUMENTATION_FAILED: runner not installed\n", self.selector)["status"])

    def test_partial_started_case_is_not_a_pass(self):
        with self.assertRaises(batch.CheckFailed):
            batch.parse_instrumentation(self.runner(0).partition("INSTRUMENTATION_STATUS: download")[0], self.selector)

    def test_each_instrumentation_method_is_in_the_matrix(self):
        for cls, entries in batch.CASES.items():
            path = ROOT / "app/src/androidTest/java/com/privacy/browser" / (cls.replace(".", "/") + ".java")
            import re
            test_methods = set(re.findall(r"@Test public void (\w+)\(", path.read_text()))
            if cls == "downloads.DownloadControlBatchTest":
                test_methods.remove(batch.STAGE.split("#")[1])
            self.assertEqual(test_methods, set(entries.values()), cls)

    def test_jvm_summary_counts_skips_separately(self):
        with TemporaryDirectory() as tmp, patch.dict("os.environ", {"GITHUB_SHA": "0" * 40, "FFMPEG_TEST_BIN": ""}):
            root = Path(tmp)
            folder = root / "source/downloader/build/test-results/testDebugUnitTest"
            folder.mkdir(parents=True)
            (folder / "TEST-Streams.xml").write_text('<testsuite name="Fixture" tests="6" failures="1" errors="1" skipped="1"/>')
            summary = host.summarize(root, 1)
            self.assertEqual({"passed": 3, "failed": 2, "skipped": 1}, summary["host_jvm_by_class"]["Fixture"])
            self.assertFalse(summary["ffmpeg_configured"])


class BatchFixtureTest(unittest.TestCase):
    def setUp(self):
        self.state, servers = self.enterContext(fixture.servers(ports=(0, 0)))
        self.primary, self.foreign = [s.server_port for s in servers]

    def call(self, route, port=None, method="GET", headers=None):
        with closing(HTTPConnection("127.0.0.1", port or self.primary, timeout=3)) as conn:
            conn.request(method, route, headers=headers or {})
            with conn.getresponse() as response:
                return response.status, dict(response.getheaders()), response.read()

    def test_probe_exact_suffix_and_wrong_validator(self):
        path = "/range/run.bin"
        code, headers, body = self.call(path, headers={"Range": "bytes=0-0"})
        self.assertEqual((206, f"bytes 0-0/{fixture.LENGTH}", fixture.ETAG, b"d"),
                         (code, headers["Content-Range"], headers["ETag"], body))
        request = {"Range": f"bytes={fixture.PREFIX}-{fixture.LENGTH - 1}", "If-Range": fixture.ETAG}
        self.assertEqual(fixture.BODY[fixture.PREFIX:], self.call(path, headers=request)[2])
        request["If-Range"] = '"stale"'
        self.assertEqual(416, self.call(path, headers=request)[0])
        self.assertEqual([206, 206, 416], [event["code"] for event in self.state.requests(path)])

    def test_releases_only_the_synthetic_held_body(self):
        self.assertEqual(200, self.call("/control/arm/cut.bin")[0])
        with closing(HTTPConnection("127.0.0.1", self.primary, timeout=3)) as conn:
            conn.request("GET", "/range/cut.bin", headers={"Range": f"bytes=0-{fixture.LENGTH - 1}", "If-Range": fixture.ETAG})
            with conn.getresponse() as response:
                self.assertEqual(fixture.BODY[:fixture.PREFIX], response.read(fixture.PREFIX))
                self.assertEqual(200, self.call("/control/release/cut.bin")[0])
                self.assertEqual(fixture.BODY[fixture.PREFIX:], response.read())
        self.state.reset()
        self.assertEqual([], self.state.requests())
        self.assertEqual(409, self.call("/control/release/cut.bin")[0])

    def test_transient_fixture_never_records_supplied_header_values(self):
        cookie, token = "do-not-export-this-cookie", "do-not-export-this-auth"
        self.assertEqual(206, self.call("/session/external.bin", headers={"Range": "bytes=0-0", "Cookie": cookie, "Authorization": token})[0])
        result = self.call("/control/requests?/session/external.bin")[2]
        self.assertNotIn(cookie.encode(), result)
        self.assertNotIn(token.encode(), result)
        event, = json.loads(result)
        self.assertTrue(event["cookie"] and event["auth"])
        self.assertFalse(event["fixture_cookie"] or event["referer"] or event["conditional"])

    def test_foreign_click_uses_a_real_second_port(self):
        code, _, parent = self.call("/frames")
        self.assertEqual(200, code)
        self.assertIn(f'http://127.0.0.1:{self.foreign}/foreign-frame'.encode(), parent)
        self.assertEqual(404, self.call("/foreign-frame")[0])
        frame = self.call("/foreign-frame", self.foreign)[2]
        self.assertIn(b"URL.createObjectURL", frame)
        self.assertIn(b"if(e.isTrusted)", frame)
        self.assertIn(f"'http://127.0.0.1:{self.primary}'".encode(), frame)

    def test_fixture_archive_images_and_verification(self):
        self.assertIn(b'"/images/red.png", "/reader/green", "/images/red.png"', self.call("/comic/ok")[2])
        self.assertEqual(403, self.call("/gated-page")[0])
        code, _, challenge = self.call("/gated-page", headers={"Accept": "text/html,application/xhtml+xml"})
        self.assertEqual(200, code)
        self.assertIn(b'id="verify"', challenge)
        data = self.call("/gated-page", headers={"Cookie": "comic_fixture_verified=1"})[2]
        self.assertEqual(sha256(self.call("/images/blue.png")[2]).digest(), sha256(data).digest())
        self.assertEqual([False, False, True], [event["verified_cookie"] for event in self.state.requests("/gated-page")])


if __name__ == "__main__":
    unittest.main()

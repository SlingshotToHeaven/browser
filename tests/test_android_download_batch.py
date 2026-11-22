"""Host tests of the batch fixtures and evidence parser; no Android target is touched."""
from contextlib import closing
from hashlib import sha256
from http.client import HTTPConnection, IncompleteRead
import json
from pathlib import Path
from subprocess import CompletedProcess
import sys
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import Mock, patch

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

    def test_frame_recheck_is_three_selectors_and_restores_display_even_on_a_test_failure(self):
        original = {"size": "1080x1920", "density": "420"}
        overrides, invocations = {}, []
        def shell(*args, **_):
            if args[0] == "wm":
                kind, value = args[1:]
                if value == "reset":
                    overrides.pop(kind, None)
                else:
                    overrides[kind] = value
                return
            assert args[:2] == ("am", "instrument"), args
            invocations.append((args[args.index("class") + 1], dict(overrides)))
            return CompletedProcess(args, 0, b"result", b"")
        def text(wm, kind):
            assert wm == "wm"
            return f"Physical {kind}: {original[kind]}" + (f"\nOverride {kind}: {overrides[kind]}" if kind in overrides else "")
        with TemporaryDirectory() as tmp, patch.object(batch, "reset_profile") as reset, \
             patch.object(batch, "parse_instrumentation", side_effect=[{"status": x} for x in ("passed", "failed", "passed")]):
            work=Path(tmp); (work / "proof").mkdir()
            report={"device": {}}
            batch.device_matrix(Mock(shell=Mock(side_effect=shell), text=Mock(side_effect=text)), work,
                                Mock(requests=Mock(return_value=[])), report, None, frame_recheck=True)
            self.assertEqual(list(report["device"]), list(batch.FRAME_RECHECK))
            self.assertEqual(reset.call_count, 3)
            self.assertEqual(report["frame_display"], {"size": "1080x2340", "verified_densities": [320, 560], "restored": True})
            self.assertEqual(overrides, {})
            self.assertEqual([name.rsplit("#",1)[1] for name,_ in invocations], [
                "crossingOriginDuringBlobReadDrainsBeforeNewOffer", *(["crossOriginFrameCannotUseTheMainPageBlobIntake"] * 2)])
            self.assertEqual([d.get("density") for _,d in invocations], [None, "320", "560"])

    def test_jvm_summary_counts_skips_separately(self):
        with TemporaryDirectory() as tmp, patch.dict("os.environ", {"GITHUB_SHA": "0" * 40, "FFMPEG_TEST_BIN": ""}):
            root = Path(tmp)
            folder = root / "source/downloader/build/test-results/testDebugUnitTest"
            folder.mkdir(parents=True)
            (folder / "TEST-Streams.xml").write_text('<testsuite name="Fixture" tests="6" failures="1" errors="1" skipped="1"/>')
            summary = host.summarize(root, 1)
            self.assertEqual({"passed": 3, "failed": 2, "skipped": 1}, summary["host_jvm_by_class"]["Fixture"])
            self.assertFalse(summary["ffmpeg_configured"])
            self.assertIsNone(summary["ffmpeg_version"])

    def test_jvm_playback_keeps_independent_method_outcomes(self):
        with TemporaryDirectory() as tmp, patch.dict("os.environ", {"GITHUB_SHA": "0" * 40, "FFMPEG_TEST_BIN": "fixture"}):
            root = Path(tmp)
            folder = root / "source/downloader/build/test-results/testDebugUnitTest"
            folder.mkdir(parents=True)
            (folder / "TEST-playback.xml").write_text(
                f'<testsuite name="{host.PLAYBACK_SUITE}" tests="3" failures="1" skipped="1">'
                '<testcase name="decoded"/><testcase name="failed"><failure>omitted</failure></testcase>'
                '<testcase name="blocked"><skipped/></testcase></testsuite>')
            with patch.object(host, "ffmpeg_version", return_value="n9.0.2-17-g2a571b6068") as version:
                summary = host.summarize(root, 1)
            version.assert_called_once_with("fixture")
            self.assertEqual("n9.0.2-17-g2a571b6068", summary["ffmpeg_version"])
            self.assertEqual({"decoded": "passed", "failed": "failed", "blocked": "skipped"}, summary["playback_by_test"])


class BatchFixtureTest(unittest.TestCase):
    def setUp(self):
        self.state, servers = self.enterContext(fixture.servers(ports=(0, 0)))
        self.primary, self.foreign = [s.server_port for s in servers]

    def call(self, route, port=None, method="GET", headers=None):
        with closing(HTTPConnection("127.0.0.1", port or self.primary, timeout=3)) as conn:
            conn.request(method, route, headers=headers or {})
            with conn.getresponse() as response:
                return response.status, dict(response.getheaders()), response.read()

    def test_frame_serves_a_visible_attachment_and_witness_routes_on_the_child_origin(self):
        code, _, page = self.call("/frames")
        self.assertEqual(code, 200)
        self.assertIn(f'id="foreign-blob" title="Foreign fixture attachment" src="http://127.0.0.1:{self.foreign}/foreign-frame"'.encode(), page)
        self.assertIn(b'e.source === frame.contentWindow', page)
        self.assertLess(page.index(b"addEventListener"), page.index(b"<iframe"))
        code, _, child = self.call("/foreign-frame", self.foreign)
        self.assertEqual(code, 200)
        self.assertIn(b'<a id="offer" download="foreign.bin">', child)
        self.assertIn(b"e.isTrusted && e.pointerType === 'touch'", child)
        self.assertNotIn(b".click()", child)
        for port in (self.primary, self.foreign):
            self.assertEqual(self.call("/favicon.ico", port)[0], 200)
        for path in fixture.FRAME_INPUT:
            self.assertEqual(self.call(path)[0], 404)
            self.assertEqual(self.call(path, self.foreign)[0], 204)
            self.assertEqual(self.call(path + "?spoof=1", self.foreign)[0], 404)
            self.assertEqual([(r["method"], r["code"]) for r in self.state.requests(path)], [("GET", 204)])

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

    def test_static_negative_media_available_without_host_ffmpeg(self):
        for manifest, segment in (("/invalid.m3u8", "/invalid.ts"), ("/broken-mux.m3u8", "/broken-mux.ts")):
            self.assertIn(segment.encode(), self.call(manifest)[2])
            self.assertEqual(200, self.call(segment)[0])

    def test_short_fixture_advertises_more_bytes_than_it_sends(self):
        transport = b'\x47' * 188
        self.state.assets["/short.ts"] = ("video/mp2t", transport)
        with self.assertRaises(IncompleteRead) as truncated:
            self.call("/short.ts")
        self.assertEqual(transport[:63], truncated.exception.partial)
        self.assertEqual(len(transport) - 63, truncated.exception.expected)
        self.assertEqual(200, self.state.requests("/short.ts")[0]["code"])

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

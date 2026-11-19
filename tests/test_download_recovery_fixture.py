"""Host checks for the paced HTTP fixture, not Android download execution."""
from contextlib import ExitStack, contextmanager, redirect_stdout
from hashlib import sha256
from http.client import HTTPConnection
from io import StringIO
from threading import Thread
import unittest

from fixtures import download_recovery as fixture


@contextmanager
def running_fixture(port=0, *, replace_paused=False):
    with fixture.RecoveryServer(port=port, delay=0, replace_paused=replace_paused) as server:
        runner = Thread(target=server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
        runner.start()
        try:
            yield server.server_port
        finally:
            server.shutdown()
            runner.join(5)


class DownloadRecoveryFixtureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        stack = ExitStack()
        cls.addClassCleanup(stack.close)
        cls.port = stack.enter_context(running_fixture())

    def request(self, path, *, method="GET", headers=None, port=None):
        conn = HTTPConnection(fixture.HOST, self.port if port is None else port, timeout=4)
        log = StringIO()
        try:
            with redirect_stdout(log):
                conn.request(method, path, headers=headers or {})
                response = conn.getresponse()
                body = response.read()
            return response.status, response.headers, body, log.getvalue()
        finally:
            conn.close()

    def test_page_sets_path_scoped_dummy_cookie_and_only_serves_two_jobs(self):
        status, headers, body, _ = self.request("/start?dummy=1")
        self.assertEqual(200, status)
        self.assertEqual(fixture.COOKIE + "; Path=/jobs/; SameSite=Lax", headers["Set-Cookie"])
        for name in fixture.CASES:
            self.assertIn(f"/jobs/{name}".encode(), body)
            state, meta, empty, _ = self.request("/jobs/" + name, method="HEAD")
            self.assertEqual((200, b"", str(fixture.LENGTH)), (state, empty, meta["Content-Length"]))
            self.assertEqual(fixture.ETAGS[name], meta["ETag"])
            self.assertEqual("no-store", meta["Cache-Control"])
        self.assertEqual(404, self.request("/README.md")[0])
        self.assertEqual(404, self.request("/jobs/unknown.bin")[0])

    def test_probe_and_nonzero_resume_reassemble_the_right_position_dependent_bytes(self):
        self.assertNotEqual(*fixture.ETAGS.values())
        self.assertNotEqual(*fixture.PAYLOAD.values())
        for name in fixture.CASES:
            with self.subTest(name=name):
                path = "/jobs/" + name
                status, headers, body, log = self.request(path, headers={"Range": "bytes=0-0"})
                self.assertEqual((206, fixture.PAYLOAD[name][:1]), (status, body))
                self.assertEqual(f"bytes 0-0/{fixture.LENGTH}", headers["Content-Range"])
                self.assertIn("start=0 end=0 probe=True conditional=False", log)
                boundary = fixture.CHUNK + 31
                prefix = self.request(path, headers={"Range": f"bytes=0-{boundary - 1}", "If-Range": headers["ETag"]})
                tail = self.request(path, headers={"Range": f"bytes={boundary}-", "If-Range": headers["ETag"]})
                self.assertEqual((206, 206), (prefix[0], tail[0]))
                self.assertIn(f"start={boundary} end={fixture.LENGTH - 1} probe=False conditional=True validator_match=True", tail[3])
                self.assertNotEqual(prefix[2][:64], tail[2][:64])
                self.assertEqual(sha256(fixture.PAYLOAD[name]).digest(), sha256(prefix[2] + tail[2]).digest())

    def test_stale_validator_gets_full_representation_and_unsatisfiable_range_is_rejected(self):
        name = fixture.CASES[0]
        path = "/jobs/" + name
        status, headers, body, log = self.request(path, headers={"Range": "bytes=16-", "If-Range": fixture.ETAGS[fixture.CASES[1]]})
        self.assertEqual((200, fixture.PAYLOAD[name]), (status, body))
        self.assertIsNone(headers.get("Content-Range"))
        self.assertIn("probe=False conditional=True validator_match=False", log)
        status, headers, body, _ = self.request(path, headers={"Range": f"bytes={fixture.LENGTH}-"})
        self.assertEqual((416, b"", f"bytes */{fixture.LENGTH}"), (status, body, headers["Content-Range"]))

    def test_replacement_at_same_url_separates_stale_range_from_full_restart(self):
        name = fixture.ROTATING_JOB
        path = f"/jobs/{name}"
        offset = fixture.CHUNK + 31
        with running_fixture() as port:
            status, headers, prefix, log = self.request(path, port=port, headers={
                "Range": f"bytes=0-{offset - 1}", "If-Range": fixture.ETAGS[name],
            })
            self.assertEqual((206, fixture.PAYLOAD[name][:offset]), (status, prefix))
            self.assertIn("version=1 range_present=True requested_start=0 if_range_version=1", log)
            old_etag = headers["ETag"]
        with running_fixture(port, replace_paused=True):
            status, headers, body, log = self.request(path, port=port, headers={
                "Range": f"bytes={offset}-", "If-Range": old_etag,
            })
            self.assertEqual((200, fixture.REPLACEMENT), (status, body))
            self.assertEqual(fixture.REPLACEMENT_ETAG, headers["ETag"])
            self.assertNotEqual(old_etag, headers["ETag"])
            self.assertEqual(str(fixture.LENGTH), headers["Content-Length"])
            self.assertIsNone(headers.get("Content-Range"))
            self.assertIn(f"status=200 start=0 end={fixture.LENGTH - 1} probe=False conditional=True validator_match=False", log)
            self.assertIn(f"version=2 range_present=True requested_start={offset} if_range_version=1", log)
            self.assertNotIn(old_etag, log)
            self.assertNotEqual(sha256(body).digest(), sha256(prefix + body[offset:]).digest())

            status, headers, full_body, log = self.request(path, port=port)
            self.assertEqual((200, body), (status, full_body))
            self.assertIsNone(headers.get("Content-Range"))
            self.assertIn("probe=False conditional=False", log)
            self.assertIn("version=2 range_present=False requested_start=None if_range_version=none", log)

            status, headers, tail, log = self.request(path, port=port, headers={
                "Range": f"bytes={offset}-", "If-Range": headers["ETag"],
            })
            self.assertEqual((206, body[offset:]), (status, tail))
            self.assertIn("validator_match=True", log)
            self.assertIn(f"version=2 range_present=True requested_start={offset} if_range_version=2", log)

            other = fixture.CASES[1]
            status, headers, body, log = self.request(f"/jobs/{other}", port=port, headers={"Range": "bytes=0-0"})
            self.assertEqual((206, fixture.PAYLOAD[other][:1]), (status, body))
            self.assertEqual(fixture.ETAGS[other], headers["ETag"])
            self.assertIn("version=1 range_present=True requested_start=0 if_range_version=none", log)

    def test_logs_presence_and_origin_only_without_raw_headers(self):
        name = fixture.CASES[1]
        path = f"/jobs/{name}?synthetic-secret"
        origin = f"http://{fixture.HOST}:{self.port}/"
        ranged = {"Range": "bytes=0-0"}
        scoped = self.request(path, headers={**ranged, "Cookie": fixture.COOKIE, "Referer": origin})[3]
        self.assertIn("fixture_cookie=True cookie_present=True referer_present=True origin_only=True", scoped)
        unscoped = self.request(path, headers={**ranged, "Cookie": "other=dummy-header-value", "Referer": origin + "private-path"})[3]
        self.assertIn("fixture_cookie=False cookie_present=True referer_present=True origin_only=False", unscoped)
        anonymous = self.request(path, headers=ranged)[3]
        self.assertIn("fixture_cookie=False cookie_present=False referer_present=False origin_only=False", anonymous)
        odd = self.request(path, headers={"Range": "bytes=dummy-offset", "If-Range": '"dummy-etag"'})[3]
        self.assertIn("range_present=True requested_start=None if_range_version=other", odd)
        for value in (fixture.COOKIE, origin, "synthetic-secret", "dummy-header-value", "private-path", "dummy-offset", "dummy-etag"):
            self.assertNotIn(value, scoped + unscoped + anonymous + odd)


if __name__ == "__main__":
    unittest.main()

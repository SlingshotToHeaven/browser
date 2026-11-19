"""Host checks for the paced restart fixture, not Android checkpoint execution."""
from contextlib import redirect_stdout
from hashlib import sha256
from http.client import HTTPConnection
from io import StringIO
from threading import Thread
import unittest

from fixtures import download_recovery as fixture


class DownloadRecoveryFixtureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = fixture.RecoveryServer(port=0, delay=0)
        cls.addClassCleanup(cls.server.server_close)
        cls.runner = Thread(target=cls.server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
        cls.runner.start()
        cls.addClassCleanup(cls.runner.join, 5)
        cls.addClassCleanup(cls.server.shutdown)

    def request(self, path, *, method="GET", headers=None):
        conn = HTTPConnection(fixture.HOST, self.server.server_port, timeout=4)
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

    def test_logs_presence_and_origin_only_without_raw_cookie_or_referrer(self):
        name = fixture.CASES[1]
        path = f"/jobs/{name}?synthetic-secret"
        origin = f"http://{fixture.HOST}:{self.server.server_port}/"
        ranged = {"Range": "bytes=0-0"}
        scoped = self.request(path, headers={**ranged, "Cookie": fixture.COOKIE, "Referer": origin})[3]
        self.assertIn("fixture_cookie=True cookie_present=True referer_present=True origin_only=True", scoped)
        unscoped = self.request(path, headers={**ranged, "Cookie": "other=dummy-header-value", "Referer": origin + "private-path"})[3]
        self.assertIn("fixture_cookie=False cookie_present=True referer_present=True origin_only=False", unscoped)
        anonymous = self.request(path, headers=ranged)[3]
        self.assertIn("fixture_cookie=False cookie_present=False referer_present=False origin_only=False", anonymous)
        for value in (fixture.COOKIE, origin, "synthetic-secret", "dummy-header-value", "private-path"):
            self.assertNotIn(value, scoped + unscoped + anonymous)


if __name__ == "__main__":
    unittest.main()

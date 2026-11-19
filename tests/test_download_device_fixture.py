"""Exercise the host fixtures, not Android or the app's credential policy."""
from contextlib import redirect_stdout
from http.client import HTTPConnection
from io import StringIO
from threading import Thread
import unittest

from fixtures import scoped_downloads as fixture


class ScopedDownloadFixtureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        servers = fixture.loopback_servers(0, 0)
        cls.primary, cls.other = servers.__enter__()
        cls.addClassCleanup(servers.__exit__, None, None, None)
        for server in (cls.primary, cls.other):
            runner = Thread(target=server.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
            runner.start()
            cls.addClassCleanup(cls.stop, server, runner)

    @staticmethod
    def stop(server, runner):
        server.shutdown()
        runner.join(5)

    @staticmethod
    def request(server, path, *, method="GET", headers=None):
        connection = HTTPConnection(fixture.HOST, server.server_port, timeout=4)
        log = StringIO()
        try:
            with redirect_stdout(log):
                connection.request(method, path, headers=headers or {})
                response = connection.getresponse()
                body = response.read()
            return response.status, response.headers, body, log.getvalue()
        finally:
            connection.close()

    def test_only_the_fixed_pages_and_files_are_served(self):
        status, headers, body, log = self.request(self.primary, "/start?query=fixture-only")
        self.assertEqual(200, status)
        self.assertEqual(fixture.COOKIE + "; Path=/session/; SameSite=Lax", headers["Set-Cookie"])
        self.assertEqual("no-store", headers["Cache-Control"])
        self.assertIn(b"Download Link with Manager", body)
        self.assertNotIn("fixture-only", log)
        status, headers, body, _ = self.request(self.primary, "/blob_download.html")
        self.assertEqual((200, fixture.BLOB_PAGE), (status, body))
        self.assertEqual("text/html; charset=utf-8", headers["Content-Type"])
        self.assertEqual(404, self.request(self.other, "/blob_download.html")[0])
        self.assertEqual(404, self.request(self.primary, "/session/foreign.csv")[0])
        self.assertEqual(404, self.request(self.primary, "/README.md")[0])

    def test_foreign_and_return_routes_use_the_actual_loopback_ports(self):
        for start, hops in (
            ("/session/redirect.csv", [(self.other, "/session/foreign.csv")]),
            ("/session/round-trip.csv", [(self.other, "/session/bounce.csv"), (self.primary, "/session/return.csv")]),
        ):
            with self.subTest(start=start):
                status, headers, body, _ = self.request(self.primary, start)
                for server, path in hops:
                    self.assertEqual(302, status)
                    self.assertEqual(b"", body)
                    self.assertEqual(f"http://{fixture.HOST}:{server.server_port}{path}", headers["Location"])
                    status, headers, body, _ = self.request(server, path)
                self.assertEqual((200, fixture.BODY), (status, body))

    def test_probe_and_verified_range_are_distinguishable(self):
        path = "/session/local.csv"
        size = len(fixture.BODY)
        status, headers, body, _ = self.request(self.primary, path, method="HEAD")
        self.assertEqual((200, b"", str(size)), (status, body, headers["Content-Length"]))
        status, headers, body, log = self.request(self.primary, path, headers={"Range": "bytes=0-0"})
        self.assertEqual((206, fixture.BODY[:1]), (status, body))
        self.assertEqual(f"bytes 0-0/{size}", headers["Content-Range"])
        self.assertIn("probe=True conditional=False", log)
        status, headers, body, log = self.request(self.primary, path, headers={
            "Range": f"bytes=0-{size - 1}", "If-Range": fixture.ETAG,
        })
        self.assertEqual((206, fixture.BODY), (status, body))
        self.assertIn("probe=False conditional=True", log)
        self.assertEqual(fixture.ETAG, headers["ETag"])
        self.assertEqual(200, self.request(self.primary, path, headers={"Range": "bytes=0-0", "If-Range": '"stale"'})[0])
        self.assertEqual(416, self.request(self.primary, path, headers={"Range": f"bytes=0-{size}"})[0])

    def test_log_distinguishes_marker_presence_from_any_cookie_without_echoing_values(self):
        origin = f"http://{fixture.HOST}:{self.primary.server_port}/"
        _, _, _, local = self.request(self.primary, "/session/local.csv", headers={
            "Cookie": f"dummy=other-value;{fixture.COOKIE}", "Referer": origin,
        })
        self.assertIn("fixture_cookie=True cookie_present=True referer_present=True origin_only=True", local)
        _, _, _, foreign = self.request(self.other, "/session/foreign.csv?synthetic-query", headers={
            "Cookie": "dummy=other-value", "Referer": origin + "private-path-bytes", "If-Range": "sentinel-etag",
        })
        self.assertIn("fixture_cookie=False cookie_present=True referer_present=True origin_only=False", foreign)
        _, _, _, anonymous = self.request(self.other, "/session/foreign.csv")
        self.assertIn("fixture_cookie=False cookie_present=False referer_present=False origin_only=False", anonymous)
        _, _, _, unknown = self.request(self.primary, "/private-path-bytes?synthetic-query")
        log = local + foreign + anonymous + unknown
        for value in (fixture.COOKIE, "other-value", "synthetic-query", "private-path-bytes", "sentinel-etag", origin):
            self.assertNotIn(value, log)
        self.assertIn("GET unmatched status=404", log)


if __name__ == "__main__":
    unittest.main()

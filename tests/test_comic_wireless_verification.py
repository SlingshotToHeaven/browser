"""Host contract for the retained-phone comic verification fixture."""
from contextlib import closing
from http.client import HTTPConnection
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent / 'fixtures'))
from comic_wireless_verification import FIRST_IMAGE, RETRY_IMAGE, serve


class ComicVerificationFixtureTest(unittest.TestCase):
    def setUp(self):
        self.port = self.enterContext(serve(0))

    def request(self, path, method='GET', headers=None):
        with closing(HTTPConnection('127.0.0.1', self.port, timeout=3)) as conn:
            conn.request(method, path, headers=headers or {})
            response = conn.getresponse()
            return response.status, dict(response.getheaders()), response.read()

    def counts(self):
        return json.loads(self.request('/control/counts')[2])

    def enter_verifier(self, case):
        self.assertEqual((200, FIRST_IMAGE), self.request(f'/{case}/first.png')[::2])
        self.assertEqual(403, self.request(f'/{case}/gate')[0])
        status, headers, body = self.request(f'/{case}/gate')
        self.assertEqual(200, status)
        self.assertTrue(headers['Content-Type'].startswith('text/html'))
        self.assertIn(f'Synthetic {case} verifier'.encode(), body)
        self.assertIn(f'/witness/{case}'.encode(), body)
        self.assertEqual(204, self.request(f'/witness/{case}', 'POST')[0])

    def test_retry_releases_only_second_position_and_third_is_permanently_missing(self):
        page = self.request('/retry')[2]
        self.assertIn(b'window.chapterImages = ["/retry/first.png", "/retry/gate", "/retry/missing.png"]', page)
        self.assertNotIn(b'<img', page)
        self.assertEqual({'GET /retry': 1}, self.counts()['requests'])
        self.assertEqual(409, self.request('/control/release/retry', 'POST')[0])
        self.enter_verifier('retry')
        self.assertEqual((200, b'released'), self.request('/control/release/retry', 'POST')[::2])
        self.assertEqual(409, self.request('/control/release/retry', 'POST')[0])
        self.assertEqual((200, RETRY_IMAGE), self.request('/retry/gate')[::2])
        self.assertEqual(404, self.request('/retry/missing.png')[0])
        self.assertEqual({'GET /retry': 1, 'GET /retry/first.png': 1, 'GET /retry/gate 403': 1,
                          'GET /retry/gate 200 html': 1, 'GET /retry/gate 200 png': 1,
                          'POST /witness/retry': 1, 'GET /retry/missing.png 404': 1}, self.counts()['requests'])
        self.assertEqual(b'wait', self.request('/control/retire-ready')[2])

    def test_retirement_releases_only_navigation_not_a_photo(self):
        page = self.request('/retire')[2]
        self.assertIn(b"location.replace('/after-retire')", page)
        self.assertEqual(409, self.request('/after-retire')[0])
        self.enter_verifier('retire')
        self.assertEqual(b'wait', self.request('/control/retire-ready')[2])
        self.assertEqual(200, self.request('/control/release/retire', 'POST')[0])
        self.assertEqual(b'go', self.request('/control/retire-ready')[2])
        self.assertIn(b'New same-tab fixture document.', self.request('/after-retire')[2])
        self.assertNotIn('GET /retire/missing.png 404', self.counts()['requests'])
        self.assertTrue(self.request('/retire/gate')[1]['Content-Type'].startswith('text/html'))
        self.assertEqual(404, self.request('/retire/missing.png')[0])
        self.assertEqual(409, self.request('/control/release/retry', 'POST')[0])

    def test_no_host_release_without_fresh_webview_witness(self):
        self.assertEqual(200, self.request('/retry')[0])
        self.assertEqual(200, self.request('/retry/first.png')[0])
        self.assertEqual(409, self.request('/witness/retry', 'POST')[0])
        self.assertEqual(403, self.request('/retry/gate')[0])
        self.assertEqual(200, self.request('/retry/gate')[0])
        self.assertEqual(409, self.request('/control/release/retry', 'POST')[0])
        self.assertEqual(404, self.request('/control/release/retry')[0])
        self.assertEqual(204, self.request('/witness/retry', 'POST')[0])
        self.assertEqual(409, self.request('/witness/retry', 'POST')[0])
        self.assertEqual(200, self.request('/retry/gate')[0])  # An ambiguous duplicate verifier load.
        self.assertEqual(409, self.request('/control/release/retry', 'POST')[0])
        self.assertEqual({}, self.counts()['release_age_ms'])

    def test_late_release_and_unknown_requests_do_not_change_the_plan(self):
        self.assertEqual(200, self.request('/retire')[0])
        with patch('comic_wireless_verification.monotonic', return_value=100):
            self.enter_verifier('retire')
        with patch('comic_wireless_verification.monotonic', return_value=145):
            self.assertEqual(409, self.request('/control/release/retire', 'POST')[0])
        self.assertEqual(404, self.request('/retire?ignored=private')[0])
        self.assertEqual(200, self.request('/retire', headers={'Cookie': 'private=value'})[0])
        self.assertEqual(b'wait', self.request('/control/retire-ready')[2])
        self.assertNotIn('private', json.dumps(self.counts()))

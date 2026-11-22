"""Host checks for the isolated retained-phone document retirement fixture."""
from contextlib import closing
from http.client import HTTPConnection
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent / 'fixtures'))
from browser_wireless_retirement import serve


class RetirementFixtureTest(unittest.TestCase):
    def setUp(self):
        self.port = self.enterContext(serve(0))

    def request(self, path, method='GET', headers=None):
        with closing(HTTPConnection('127.0.0.1', self.port, timeout=3)) as conn:
            conn.request(method, path, headers=headers or {})
            response = conn.getresponse()
            return response.status, dict(response.getheaders()), response.read()

    def counts(self):
        return json.loads(self.request('/control/counts')[2])

    def test_cases_release_once_independently_after_one_old_page_get(self):
        advance = '/control/advance/media'
        self.assertEqual(409, self.request(advance, 'POST')[0])
        self.assertIn(b'location.assign(next)', self.request('/media-old')[2])
        self.assertEqual(404, self.request(advance)[0])
        self.assertEqual(b'wait', self.request('/control/ready/media')[2])
        self.assertEqual((200, b'released'), self.request(advance, 'POST')[::2])
        self.assertEqual(409, self.request(advance, 'POST')[0])
        self.assertEqual(b'go', self.request('/control/ready/media')[2])
        self.assertEqual(b'wait', self.request('/control/ready/picker')[2])
        self.assertIn(b'Select this synthetic element', self.request('/picker-old')[2])
        self.assertEqual(200, self.request('/control/advance/picker', 'POST')[0])
        self.assertEqual(['media', 'picker'], self.counts()['released'])

    def test_reopened_old_page_is_not_a_fresh_control(self):
        for _ in range(2):
            self.assertEqual(200, self.request('/picker-old')[0])
        self.assertEqual(409, self.request('/control/advance/picker', 'POST')[0])
        self.assertEqual(b'wait', self.request('/control/ready/picker')[2])

    def test_head_pairs_are_distinct_and_never_serve_a_body(self):
        for name in ('old', 'new'):
            url = f'/clip/{name}.mp4'
            status, headers, body = self.request(url, 'HEAD')
            self.assertEqual((200, b''), (status, body))
            self.assertEqual('video/mp4', headers['Content-Type'])
            self.assertEqual('12000', headers['Content-Length'])
            self.assertEqual((404, b''), self.request(url)[::2])
        self.assertEqual({f'{method} /clip/{name}.mp4': 1 for method in ('GET', 'HEAD')
                          for name in ('old', 'new')}, self.counts()['requests'])

    def test_new_documents_have_visible_witnesses_and_no_private_request_data(self):
        self.assertIn(b'New media document', self.request('/media-new')[2])
        self.assertIn(b'New document tap accepted', self.request('/picker-new')[2])
        page = self.request('/after-home', headers={'Cookie': 'dummy=secret'})[2]
        self.assertIn(b'typeof window.PrivacyHome', page)
        self.assertEqual(404, self.request('/after-home?unrelated=secret')[0])
        self.assertNotIn('secret', json.dumps(self.counts()))

"""Host fixture checks; these do not drive Android or the provider."""
from contextlib import closing
from http.client import HTTPConnection
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent / 'fixtures'))
from image_search_wireless import PHOTO, PNG, serve


class WirelessImageFixtureTest(unittest.TestCase):
    def setUp(self):
        self.port = self.enterContext(serve(0))

    def request(self, path, method='GET', headers=None):
        with closing(HTTPConnection('127.0.0.1', self.port, timeout=3)) as conn:
            conn.request(method, path, headers=headers or {})
            response = conn.getresponse()
            return response.status, dict(response.getheaders()), response.read()

    def counts(self):
        return json.loads(self.request('/control/counts')[2])

    def test_review_page_loads_only_the_exact_synthetic_photo(self):
        page = self.request('/review')[2]
        self.assertIn(PHOTO.encode(), page)
        self.assertIn(b'<a href="/data">Open inline fixture</a>', page)
        status, headers, body = self.request(PHOTO)
        self.assertEqual((200, 'image/png', PNG), (status, headers['Content-Type'], body))
        self.assertTrue(body.startswith(b'\x89PNG\r\n\x1a\n'))
        head, head_headers, head_body = self.request(PHOTO, 'HEAD')
        self.assertEqual((200, str(len(body)), b''), (head, head_headers['Content-Length'], head_body))
        self.assertEqual(404, self.request(PHOTO.replace('%2F', '/'))[0])
        self.assertEqual(404, self.request(PHOTO.split('?', 1)[0])[0])
        self.assertEqual({'GET /review': 1, f'GET {PHOTO}': 1, f'HEAD {PHOTO}': 1}, self.counts())

    def test_negative_pages_have_distinct_post_only_witnesses(self):
        for kind, source in [('data', b'data:image/png;base64,'), ('blob', b'URL.createObjectURL(new Blob')]:
            page = self.request('/' + kind)[2]
            self.assertIn(source, page)
            self.assertNotIn(PHOTO.encode(), page)
            if kind == 'data':
                self.assertIn(b'<a href="/blob">Open blob fixture</a>', page)
            self.assertIn(b'!event.isTrusted', page)
            self.assertIn(b'__image_search_picker_banner', page)
            target = '/witness/' + kind
            self.assertIn(target.encode(), page)
            self.assertEqual(404, self.request(target)[0])
            self.assertEqual(404, self.request(target, 'HEAD')[0])
            self.assertNotIn('POST ' + target, self.counts())
            self.assertEqual((204, b''), self.request(target, 'POST')[::2])
        self.assertEqual({'GET /data': 1, 'GET /blob': 1, 'POST /witness/data': 1, 'POST /witness/blob': 1}, self.counts())

    def test_counts_ignore_other_targets_and_request_metadata(self):
        self.assertEqual(404, self.request('/review?ignored=private')[0])
        self.assertEqual(404, self.request('/witness/data?ignored=private', 'POST')[0])
        self.assertEqual({}, self.counts())
        self.assertEqual(200, self.request(PHOTO, headers={'Cookie': 'private=value'})[0])
        self.assertEqual({f'GET {PHOTO}': 1}, self.counts())

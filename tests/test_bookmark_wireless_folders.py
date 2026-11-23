"""Check the folder-run's synthetic namespace and exact loopback entry; not Android UI."""
from contextlib import closing
from http.client import HTTPConnection
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent / 'fixtures'))
from bookmark_wireless_folders import TAG, serve


class BookmarkFolderFixtureTest(unittest.TestCase):
    def request(self, port, path, headers=None):
        with closing(HTTPConnection('127.0.0.1', port, timeout=3)) as conn:
            conn.request('GET', path, headers=headers or {})
            response = conn.getresponse()
            return response.status, response.read()

    def test_exact_namespace_and_no_other_state_in_counter(self):
        port, tag = self.enterContext(serve(0, 'ArenaF01234abcde'))
        path = f'/folders/{tag}'
        self.assertEqual(404, self.request(port, '/folders/ArenaFaaaaaaaaaa')[0])
        self.assertEqual(404, self.request(port, path + '?private=value')[0])
        self.assertEqual({'tag': tag, 'entry_gets': 0}, json.loads(self.request(port, '/control/counts')[1]))
        status, body = self.request(port, path, {'Cookie': 'private=value'})
        self.assertEqual(200, status)
        self.assertIn(f'<title>{tag}-Tab</title>'.encode(), body)
        self.assertEqual({'tag': tag, 'entry_gets': 1}, json.loads(self.request(port, '/control/counts')[1]))

    def test_generated_tag_has_only_typeable_synthetic_characters(self):
        with patch('bookmark_wireless_folders.token_hex', return_value='abcde01234') as token:
            _, tag = self.enterContext(serve(0))
        token.assert_called_once_with(5)
        self.assertTrue(TAG.fullmatch(tag))
        self.assertEqual('ArenaFabcde01234', tag)
        for invalid in ('', '../x', 'ArenaF<bad>', 'ArenaF01234abcde\n'):
            with self.assertRaises(ValueError), serve(0, invalid):
                self.fail('Accepted an invalid fixture tag')

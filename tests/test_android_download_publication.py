"""Host tests for the emulator check's evidence readers, not Android results."""
from contextlib import closing
from hashlib import sha256
from pathlib import Path
import sqlite3
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import Mock, call
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from xml.etree import ElementTree as ET

from test_download_queue_sql import create
from tools.check_android_download_publication import (
    CheckFailed, DIGEST, ETAG, INITIAL_REQUESTS, LENGTH, NAME, bound_path,
    find_view, fixture, publication_kind, read_state, type_fixture_url, until,
)


class AndroidDownloadPublicationEvidenceTest(unittest.TestCase):
    def test_fixture_distinguishes_probe_conditional_transfer_and_fresh_get(self):
        with fixture() as server:
            url = f"http://127.0.0.1:{server.server_port}/jobs/{NAME}"
            with urlopen(Request(url, headers={"Range": "bytes=0-0"}), timeout=3) as reply:
                self.assertEqual((206, f"bytes 0-0/{LENGTH}", 1),
                                 (reply.status, reply.headers["Content-Range"], len(reply.read())))
            with urlopen(Request(url, headers={"Range": f"bytes=0-{LENGTH - 1}", "If-Range": ETAG}), timeout=3) as reply:
                self.assertEqual((206, ETAG, DIGEST), (reply.status, reply.headers["ETag"], sha256(reply.read()).hexdigest()))
            with urlopen(url, timeout=3) as reply:
                self.assertEqual((200, None, DIGEST),
                                 (reply.status, reply.headers.get("Content-Range"), sha256(reply.read()).hexdigest()))
            with self.assertRaises(HTTPError) as invalid:
                urlopen(Request(url, headers={"Range": f"bytes=0-{LENGTH - 1}", "If-Range": '"wrong"'}), timeout=3)
            self.assertEqual(400, invalid.exception.code)
            invalid.exception.close()
            self.assertEqual(INITIAL_REQUESTS + ["fresh-200", "unexpected"], server.requests)

    def test_sqlite_snapshot_reads_commits_in_wal_and_rejects_ambiguous_rows(self):
        with TemporaryDirectory() as tmp:
            work = Path(tmp)
            path = work / "device.db"
            with closing(sqlite3.connect(path)) as writer:
                self.assertEqual("wal", writer.execute("PRAGMA journal_mode=wal").fetchone()[0])
                create(writer)
                writer.execute("PRAGMA wal_checkpoint(TRUNCATE)")
                with writer:
                    writer.execute("INSERT INTO downloads (id,url,status) VALUES ('fixture','local','RUNNING')")
                    writer.execute("INSERT INTO download_segments VALUES ('fixture',0,0,9,10,'COMPLETED')")
                main = path.read_bytes()
                wal = path.with_name("device.db-wal")
                with self.assertRaises(CheckFailed):
                    read_state(main, b"", work)
                row, segments = read_state(main, wal.read_bytes(), work)
                self.assertEqual(("fixture", "RUNNING", None), (row["id"], row["status"], row["published_identity"]))
                self.assertEqual([(0, 9, 10, "COMPLETED")], segments)
                with writer:
                    writer.execute("INSERT INTO downloads (id,url,status) VALUES ('extra','local','COMPLETED')")
                with self.assertRaises(CheckFailed):
                    read_state(main, wal.read_bytes(), work)

    def test_final_file_must_match_the_fixture_on_both_publication_paths(self):
        staged = ("12:7:123:4194304", DIGEST)
        self.assertEqual("hard-link", publication_kind(staged, staged))
        self.assertEqual("verified-copy", publication_kind(("12:8:124:4194304", DIGEST), staged))
        with self.assertRaises(CheckFailed):
            publication_kind(("12:8:124:4194304", "wrong"), staged)
        with self.assertRaises(CheckFailed):
            publication_kind(("12:8:124:4194304", "wrong"), ("12:7:123:4194304", "wrong"))

    def test_path_alias_requires_the_exact_created_directory(self):
        directory = "/data/data/com.browser/files/download-publication"
        aliased = "/data/user/0/com.browser/files/download-publication/interrupted.bin"
        android = Mock()
        android.app.side_effect = [b"12:345\n", b"12:345\n"]
        self.assertEqual(aliased, bound_path(android, directory, aliased, r"interrupted\.bin"))
        self.assertEqual(2, android.app.call_count)
        with self.assertRaises(CheckFailed):
            bound_path(android, directory, aliased.replace("/com.browser/", "/other.app/"), r"interrupted\.bin")
        self.assertEqual(2, android.app.call_count)
        android.app.side_effect = [b"12:345\n", b"12:678\n"]
        with self.assertRaises(CheckFailed):
            bound_path(android, directory, aliased, r"interrupted\.bin")

    def test_fixture_input_replaces_dropped_keypresses_without_submitting(self):
        android = Mock()
        android.hierarchy.return_value = ET.fromstring('''<hierarchy><node package="com.browser"
            resource-id="com.browser:id/add_url_edit_text" enabled="true" focused="true" /></hierarchy>''')
        url = "http://127.0.0.1:1234/jobs/interrupted.bin"
        android.view.side_effect = [ET.Element("node", text=url[1:]), ET.Element("node", text=url)]
        type_fixture_url(android, url)
        self.assertEqual(android.tap.call_args_list, [call("add_url_edit_text")] * 2)
        self.assertEqual(android.shell.call_args_list, [call("input", "text", url),
                         call("input", "keycombination", "KEYCODE_CTRL_LEFT", "KEYCODE_A"),
                         call("input", "text", url)])

    def test_ui_probe_ignores_other_apps_and_disabled_controls(self):
        root = ET.fromstring('''<hierarchy>
            <node package="other" resource-id="android:id/button3" enabled="true" text="START OVER" />
            <node package="com.browser" resource-id="android:id/button3" enabled="false" text="START OVER" />
            <node package="com.browser" resource-id="android:id/button3" enabled="true" text="START OVER" />
        </hierarchy>''')
        self.assertIs(root[2], find_view(root, "button3", "Start over"))
        self.assertIs(root[2], until(lambda: find_view(root, "button3", "Start over"), "leaf control", seconds=0.1))
        self.assertIsNone(find_view(root, "button3", "Retry"))
        root.append(ET.fromstring(ET.tostring(root[2])))
        with self.assertRaises(CheckFailed):
            find_view(root, "button3", "Start over")

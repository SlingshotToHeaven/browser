"""Host checks of the mid-copy harness's evidence gates, not Android runtime tests."""
from contextlib import closing
from hashlib import sha256
from pathlib import Path
import sqlite3
from subprocess import CompletedProcess
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import Mock
from xml.etree import ElementTree as ET

from test_download_queue_sql import create
from tools.check_android_download_mid_copy import (
    BODY, DIGEST, LENGTH, MID_COPY, PUBLISHER, PRE_COMMIT, SHARED_DIR, SHARED_PATH, CheckFailed,
    bound_destination, copy_cut, downloads_resumed, require_no_file_actions, share_refused, storage_blocked, verify_cut,
)
from tools.check_android_download_publication import read_state


class AndroidMidCopyEvidenceTest(unittest.TestCase):
    def test_breakpoint_is_after_the_real_chunk_write(self):
        source = (Path(__file__).resolve().parents[1] / PUBLISHER).read_text()
        line, hits, chunk = copy_cut(source)
        self.assertEqual(source.splitlines()[line - 1].strip(), 'copied.update(buffer, 0, count);')
        self.assertEqual(chunk * hits, LENGTH // 2)
        with self.assertRaises(CheckFailed):
            copy_cut(source.replace('while (chunk.hasRemaining()) out.write(chunk);', 'out.write(chunk);'))
        with self.assertRaises(CheckFailed):
            copy_cut(source + '\n  copied.update(buffer, 0, count);\n')

    def test_mid_copy_requires_short_exact_prefix_and_a_separate_full_stage(self):
        middle = LENGTH // 2
        stage = (f'1:23:4:{LENGTH}', DIGEST)
        prefix = (f'1:24:4:{middle}', sha256(BODY[:middle]).hexdigest())
        args = (MID_COPY, prefix, stage, middle)
        self.assertEqual(verify_cut(*args), 'mid-copy')
        for wrong in [(f'1:24:4:0', sha256(b'').hexdigest()), (f'1:24:4:{LENGTH}', DIGEST),
                      (prefix[0], DIGEST), (prefix[0].replace(':24:', ':23:'), prefix[1])]:
            with self.assertRaises(CheckFailed):
                verify_cut(MID_COPY, wrong, stage, middle)
        with self.assertRaises(CheckFailed):
            verify_cut(MID_COPY, prefix, prefix, middle)

    def test_post_copy_hold_does_not_count_unless_the_inodes_prove_a_hard_link(self):
        staged = (f'1:23:4:{LENGTH}', DIGEST)
        self.assertEqual(verify_cut(PRE_COMMIT, staged, staged, LENGTH // 2), 'hard-link-before-commit')
        with self.assertRaisesRegex(CheckFailed, 'breakpoint was missed'):
            verify_cut(PRE_COMMIT, (f'1:24:4:{LENGTH}', DIGEST), staged, LENGTH // 2)

    def test_shared_reservation_key_can_lowercase_download_but_must_bind_the_directory(self):
        dest = SHARED_PATH + '/interrupted.bin'
        row = {'dest_path': dest, 'destination_key': dest.lower()}
        android = Mock()
        android.app.return_value = b'12:201\n'
        self.assertEqual(bound_destination(android, SHARED_PATH, row, SHARED_DIR, True), dest)
        with self.assertRaises(CheckFailed):
            bound_destination(android, SHARED_PATH, dict(row, dest_path=dest.replace('/Download/', '/Documents/')),
                              SHARED_DIR, True)
        android.app.side_effect = [b'12:201\n', b'12:302\n']
        with self.assertRaises(CheckFailed):
            bound_destination(android, SHARED_PATH, row, SHARED_DIR, True)

    def test_shared_filesystem_refusal_is_not_a_harness_error(self):
        self.assertFalse(storage_blocked(CompletedProcess([], 0, b'', b'')))
        self.assertTrue(storage_blocked(CompletedProcess([], 1, b'', b'mkdir: Permission denied')))
        with self.assertRaises(CheckFailed):
            storage_blocked(CompletedProcess([], 1, b'', b'adb: device offline'))

    def test_failed_card_menu_must_be_present_and_lack_completed_actions(self):
        root = ET.fromstring('''<hierarchy>
          <node package="com.browser" enabled="true" resource-id="com.browser:id/ctx_header_title" text="interrupted.bin" />
          <node package="com.browser" enabled="true" resource-id="com.browser:id/action_item_text" text="Select" />
          <node package="other" resource-id="other:id/action_item_text" text="Share File" />
        </hierarchy>''')
        self.assertIs(require_no_file_actions(root), root[1])
        root[2].set('package', 'com.browser')
        root[2].set('enabled', 'false')
        with self.assertRaises(CheckFailed):
            require_no_file_actions(root)
        with self.assertRaises(CheckFailed):
            require_no_file_actions(ET.Element('hierarchy'))

    def test_share_check_needs_an_actual_app_refusal_and_resumed_downloads(self):
        resumed = '    topResumedActivity=ActivityRecord{81b u0 com.browser/com.privacy.downloader.ui.DownloadsActivity t17}'
        self.assertTrue(downloads_resumed(resumed))
        self.assertFalse(downloads_resumed(resumed.replace('topResumedActivity=', 'mLastPausedActivity=')))
        chooser = '    topResumedActivity=ActivityRecord{f0 u0 com.android.intentresolver/.ChooserActivity t17}'
        self.assertFalse(downloads_resumed(resumed + '\n' + chooser))
        self.assertFalse(downloads_resumed(chooser))
        toast = ('    TextToastRecord{135 1729:com.browser/u0a205 isSystemToast=false token=android.os.BinderProxy@2 '
                 'text=Cannot share files: Select at least one completed download to share. duration=0}')
        self.assertTrue(share_refused(toast))
        self.assertFalse(share_refused(toast.replace('com.browser', 'com.other')))
        self.assertFalse(share_refused(toast.replace('Select at least one completed download to share.', 'Unknown app')))

    def test_reuse_of_profile_requires_empty_history_and_no_segments(self):
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            db = root / 'fixture.db'
            with closing(sqlite3.connect(db)) as connection:
                create(connection)
                self.assertIsNone(read_state(db.read_bytes(), b'', root, allow_empty=True))
                with self.assertRaises(CheckFailed):
                    read_state(db.read_bytes(), b'', root)
                with connection:
                    connection.execute("INSERT INTO download_segments VALUES ('orphan',0,0,9,10,'COMPLETED')")
                with self.assertRaisesRegex(CheckFailed, 'orphan checkpoint'):
                    read_state(db.read_bytes(), b'', root, allow_empty=True)


if __name__ == '__main__':
    unittest.main()

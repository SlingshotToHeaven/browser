"""The public observer may only inspect or park files in the known AVD fixture."""
from pathlib import Path
from types import SimpleNamespace
import sys
import unittest
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import check_android_public_copy as copy


class PublicObserverTest(unittest.TestCase):
    def setUp(self):
        self.android = copy.PublicObserver("unused")
        self.android.shell = Mock(return_value=SimpleNamespace(stdout=b"sample"))

    def test_file_reads_use_separate_shell_and_private_db_stays_run_as(self):
        public = copy.SHARED_PATH + "/" + copy.NEIGHBOR
        self.assertEqual(b"sample", self.android.app("cat", public))
        self.android.shell.assert_called_once_with("cat", public)
        self.assertEqual(b"sample", self.android.app("cat", "databases/downloads.db"))
        self.android.shell.assert_called_with("run-as", "com.browser", "cat", "databases/downloads.db", data=None)

    def test_no_root_or_other_path_mutations(self):
        short = copy.SHARED_PATH + "/" + copy.NAME
        parked = copy.SHARED_PATH + "/" + copy.PARKED
        for words in (("rm", short), ("mv", short, parked), ("mv", "-n", short, "/sdcard/Download/another.bin"),
                      ("cat", copy.SHARED_PATH + "/../example")):
            with self.assertRaises(copy.CheckFailed):
                self.android.app(*words)
        self.android.shell.assert_not_called()
        self.assertEqual(b"sample", self.android.app("mv", "-n", short, parked))
        self.android.shell.assert_called_once_with("mv", "-n", short, parked)


if __name__ == "__main__":
    unittest.main()

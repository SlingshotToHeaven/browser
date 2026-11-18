import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


@unittest.skipUnless(shutil.which("git"), "requires Git to apply the nested baseline patches")
class PerfBaselineHandoffTest(unittest.TestCase):
    def test_materializes_independent_build_trees_and_preserves_the_candidate(self):
        with tempfile.TemporaryDirectory() as temp:
            dest = Path(temp) / "baselines"
            command = [sys.executable, "-B", str(ROOT / "tools/prepare_perf_baselines.py"), str(dest)]
            gradle = ROOT / "app/build.gradle"
            expected = gradle.read_bytes()
            result = subprocess.run(command, capture_output=True, text=True, timeout=20)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("Verified", result.stdout)
            for step in ("step17", "step18", "step19"):
                tree = dest / step
                self.assertEqual(expected, (tree / "app/build.gradle").read_bytes())
                self.assertIn("Browser (Test)", (tree / "app/src/debug/res/values/strings.xml").read_text())
                self.assertFalse((tree / "app/src/androidTest").exists())
                self.assertFalse((tree / "local.properties").exists())
                self.assertFalse((tree / ".git").exists())
            self.assertEqual(expected, gradle.read_bytes())
            again = subprocess.run(command, capture_output=True, text=True, timeout=5)
            self.assertNotEqual(0, again.returncode)
            self.assertIn("existing paths are never overwritten", again.stderr)


if __name__ == "__main__":
    unittest.main()

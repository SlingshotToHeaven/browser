import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


BUILD_SCRIPT = Path(__file__).resolve().parents[1] / "build.sh"


@unittest.skipUnless(shutil.which("bash"), "build.sh requires Bash")
class BuildEntrypointTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name).resolve()
        self.project = self.root / "test project"
        self.project.mkdir()
        self.home = self.root / "home"
        self.home.mkdir()
        self.sdk = self.root / "SDK with spaces"
        self.sdk.mkdir()
        self.jdk = self.root / "JDK with spaces"
        (self.jdk / "bin").mkdir(parents=True)
        for tool in ("java", "javac"):
            self.make_executable(self.jdk / "bin" / tool, "#!/bin/sh\nexit 0\n")
        shutil.copy2(BUILD_SCRIPT, self.project / "build.sh")
        self.make_executable(self.project / "gradlew", """#!/bin/sh
printf 'directory=<%s>\n' "$PWD"
printf 'sdk=<%s>\n' "${ANDROID_HOME-unset}"
printf 'legacy=<%s>\n' "${ANDROID_SDK_ROOT-unset}"
printf 'argument=<%s>\n' "$@"
""")
        system_gradle = self.root / "gradle"
        self.make_executable(system_gradle, "#!/bin/sh\necho 'Unexpected system Gradle' >&2\nexit 99\n")
        self.env = {
            "PATH": str(self.root) + os.pathsep + os.environ.get("PATH", os.defpath),
            "HOME": str(self.home),
            "JAVA_HOME": str(self.jdk),
        }

    @staticmethod
    def make_executable(path, script):
        path.write_text(script, encoding="utf-8")
        path.chmod(0o755)

    def run_build(self, *args):
        return subprocess.run(
            ["bash", str(self.project / "build.sh"), *args],
            cwd=self.root, env=self.env, text=True, capture_output=True, timeout=5,
        )

    def assert_wrapper(self, result, sdk):
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(f"directory=<{self.project}>", result.stdout)
        self.assertIn(f"sdk=<{sdk}>", result.stdout)
        self.assertIn("legacy=<unset>", result.stdout)
        self.assertIn("argument=<:app:assembleDebug>", result.stdout)

    def test_explicit_sdk_and_wrapper_arguments(self):
        self.env["ANDROID_HOME"] = str(self.sdk)
        self.env["ANDROID_SDK_ROOT"] = str(self.root / "conflicting legacy path")
        result = self.run_build("--stacktrace", "-Pexample=value with spaces")
        self.assert_wrapper(result, self.sdk)
        self.assertIn("argument=<--stacktrace>", result.stdout)
        self.assertIn("argument=<-Pexample=value with spaces>", result.stdout)

    def test_legacy_sdk_alias_is_forwarded_as_android_home(self):
        self.env["ANDROID_SDK_ROOT"] = str(self.sdk)
        self.assert_wrapper(self.run_build(), self.sdk)

    def test_standard_linux_and_macos_sdk_locations(self):
        for relative in ("Android/Sdk", "Library/Android/sdk"):
            with self.subTest(relative=relative):
                candidate = self.home / relative
                candidate.mkdir(parents=True)
                try:
                    self.assert_wrapper(self.run_build(), candidate)
                finally:
                    candidate.rmdir()

    def test_delegates_sdk_dir_in_local_properties_to_gradle(self):
        (self.project / "local.properties").write_text(f"sdk.dir={self.sdk}\n", encoding="utf-8")
        (self.home / "Android/Sdk").mkdir(parents=True)
        self.assert_wrapper(self.run_build(), "unset")

    def test_missing_or_invalid_sdk_does_not_start_gradle(self):
        result = self.run_build()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Android SDK not found", result.stderr)
        self.assertNotIn("argument=", result.stdout)

        self.env["ANDROID_HOME"] = str(self.root / "missing SDK")
        result = self.run_build()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Android SDK directory does not exist", result.stderr)
        self.assertNotIn("argument=", result.stdout)

    def test_explicit_java_home_requires_a_jdk(self):
        self.env["ANDROID_HOME"] = str(self.sdk)
        (self.jdk / "bin" / "javac").unlink()
        result = self.run_build()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("JAVA_HOME must point to a JDK", result.stderr)
        self.assertNotIn("argument=", result.stdout)

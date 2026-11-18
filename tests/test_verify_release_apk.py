import contextlib
import hashlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

from tools import verify_release_apk as verifier

BADGING = """package: name='com.browser' versionCode='4' versionName='1.0.1'
sdkVersion:'26'
targetSdkVersion:'34'
"""
CERT = "1a" * 32  # synthetic public fingerprint, not signing material
SIGNATURE = f"""Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): false
Number of signers: 1
Signer #1 certificate SHA-256 digest: {CERT}
"""


def elf(align=16384, machine=183, vaddr=0, ptype=1, filesz=120, memsz=120):
    header = verifier.ELF_HEADER.pack(b"\x7fELF\x02\x01\x01", 3, machine, 1, 0, 64, 0, 0, 64, 56, 1, 0, 0, 0)
    program = verifier.PROGRAM_HEADER.pack(ptype, 5, 0, vaddr, 0, filesz, memsz, align)
    return header + program


def fixture(path, *, omit=False, bad_elf=False, compressed=False, other_abi=False, duplicate=False):
    with zipfile.ZipFile(path, "w") as apk:
        apk.writestr("AndroidManifest.xml", b"fixture: not an Android manifest")
        apk.writestr("classes.dex", b"fixture: not executable")
        apk.writestr("assets/example.txt", b"dummy" * 30, compress_type=zipfile.ZIP_DEFLATED)
        for index, library in enumerate(sorted(verifier.LIBRARIES)):
            if omit and index == 0:
                continue
            abi = "x86_64" if other_abi and index == 0 else verifier.ABI
            entry = f"lib/{abi}/{library}"
            data = elf(align=4096 if bad_elf and index == 0 else 16384)
            apk.writestr(entry, data, compress_type=zipfile.ZIP_DEFLATED if compressed and index == 0 else zipfile.ZIP_STORED)
            if duplicate and index == 0:
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    apk.writestr(entry, data)


class ReleaseArtifactTest(unittest.TestCase):
    def test_elf_requires_valid_aarch64_program_headers_and_load_alignment(self):
        self.assertEqual(1, verifier.check_elf(io.BytesIO(elf()), len(elf()), "sample"))
        cases = ((elf(align=4096), "16 KiB"), (elf(align=24576), "16 KiB"), (elf(vaddr=4096), "16 KiB"),
                 (elf(machine=62), "AArch64"), (elf(ptype=2), "no PT_LOAD"),
                 (elf(filesz=4096, memsz=4096), "outside file"), (elf()[:-1], "program header"),
                 (b"", "truncated"))
        for data, message in cases:
            with self.subTest(message=message, size=len(data)), self.assertRaisesRegex(verifier.FailedCheck, message):
                verifier.check_elf(io.BytesIO(data), len(data), "sample")

    def test_archive_requires_inventory_that_is_stored_and_aligned_in_elf(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fixture.apk"
            fixture(path)
            libraries, sizes = verifier.inspect_zip(path)
            self.assertEqual(len(verifier.LIBRARIES), len(libraries))
            self.assertEqual({(1, hashlib.sha256(elf()).hexdigest())}, set(libraries.values()))
            self.assertEqual([len(elf()) * len(libraries)] * 2, sizes["native"])
            self.assertLess(sizes["assets"][0], sizes["assets"][1])
            for change, message in (("omit", "library set"), ("other_abi", "library set"), ("compressed", "uncompressed"),
                                    ("bad_elf", "16 KiB"), ("duplicate", "duplicate")):
                with self.subTest(change=change), self.assertRaisesRegex(verifier.FailedCheck, message):
                    fixture(path, **{change: True})
                    verifier.inspect_zip(path)

    def test_separate_store_target_and_non_debuggable_standalone_checks(self):
        self.assertEqual(("4", "34"), verifier.manifest_summary(BADGING, 26))
        for badging, minimum, message in ((BADGING, 36, "below the requested minimum"),
                                          (BADGING + "application-debuggable\n", 26, "debuggable"),
                                          (BADGING.replace("com.browser", "com.privacy.browser.injection"), 26, "application ID"),
                                          (BADGING.replace("com.browser", "com.browser.debug"), 26, "application ID"),
                                          (BADGING.replace("versionName='1.0.1'", "split='config.arm64_v8a'"), 26, "split APKs")):
            with self.subTest(message=message), self.assertRaisesRegex(verifier.FailedCheck, message):
                verifier.manifest_summary(badging, minimum)

    def test_pins_expected_signer_and_requires_v2_or_later(self):
        self.assertIsNone(verifier.check_signature(SIGNATURE, CERT))
        self.assertEqual(CERT, verifier.fingerprint(":".join(["1A"] * 32)))
        with self.assertRaisesRegex(verifier.FailedCheck, "fingerprint"):
            verifier.check_signature(SIGNATURE, "dd" * 32)
        with self.assertRaisesRegex(verifier.FailedCheck, "single expected"):
            verifier.check_signature(SIGNATURE + f"Signer #2 certificate SHA-256 digest: {CERT}\n", CERT)
        with self.assertRaisesRegex(verifier.FailedCheck, "v2 or v3"):
            verifier.check_signature(SIGNATURE.replace("v2): true", "v2): false"), CERT)

    def test_cli_distinguishes_unsigned_inspection_and_signature_verification(self):
        with tempfile.TemporaryDirectory() as directory:
            tools = Path(directory)
            path = tools / "fixture.apk"
            fixture(path)
            common = ["apk", str(path), "--build-tools", directory]
            calls = []

            def fake_tool(directory, name, *args):
                calls.append((name, *args))
                return {"aapt2": BADGING, "zipalign": "", "apksigner": SIGNATURE}[name]

            with patch.object(verifier, "sdk_tool", fake_tool):
                with contextlib.redirect_stdout(io.StringIO()) as output:
                    self.assertEqual(0, verifier.main([*common, "--inspect-only"]))
                self.assertIn("no signature was verified", output.getvalue())
                self.assertEqual(["aapt2", "zipalign"], [call[0] for call in calls])
                self.assertEqual(("zipalign", "-c", "-P", "16", "4", str(path)), calls[1])
                with contextlib.redirect_stdout(io.StringIO()) as output:
                    self.assertEqual(0, verifier.main([*common, "--cert-sha256", CERT, "--max-bytes", "20000000"]))
                self.assertIn("Signature verified against certificate SHA-256", output.getvalue())
                self.assertEqual(("apksigner", "verify", "--verbose", "--print-certs", "-Werr", str(path)), calls[-1])
                with contextlib.redirect_stderr(io.StringIO()) as errors:
                    self.assertEqual(1, verifier.main([*common, "--inspect-only", "--max-bytes", "1"]))
                self.assertIn("budget", errors.getvalue())

            def unaligned(directory, name, *args):
                if name == "aapt2":
                    return BADGING
                self.assertEqual("zipalign", name)
                raise verifier.FailedCheck("zipalign rejected the artifact")

            with patch.object(verifier, "sdk_tool", unaligned), contextlib.redirect_stderr(io.StringIO()) as errors:
                self.assertEqual(1, verifier.main([*common, "--cert-sha256", CERT]))
            self.assertIn("zipalign rejected", errors.getvalue())

    def test_build_verifier_and_device_traces_agree_on_the_installed_id(self):
        root = Path(__file__).resolve().parents[1]
        app_gradle = (root / "app/build.gradle").read_text()
        self.assertIn('applicationId "com.browser"', app_gradle)
        self.assertNotIn('applicationIdSuffix', app_gradle)
        self.assertEqual("com.browser", verifier.APP_ID)
        for trace in ("omnibox_trace.pbtxt", "download_history_trace.pbtxt"):
            self.assertIn('atrace_apps: "com.browser"', (root / "tests/fixtures" / trace).read_text())

    def test_release_gradle_does_not_embed_or_implicitly_load_release_signing_credentials(self):
        root = Path(__file__).resolve().parents[1]
        app_gradle = (root / "app/build.gradle").read_text()
        for setting in ("storePassword", "keyPassword", "~/keys/", "release.keystore", "signingConfig"):
            self.assertNotIn(setting, app_gradle, "production signing belongs outside the source build")
        self.assertIn("minifyEnabled true", app_gradle)
        self.assertIn("shrinkResources true", app_gradle)


if __name__ == "__main__":
    unittest.main()

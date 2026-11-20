#!/usr/bin/env python3
"""Summarize host-only Gradle/JUnit results without publishing XML or build output."""
from hashlib import sha256
import json
import os
from pathlib import Path
import re
import subprocess
import sys
from xml.etree import ElementTree as ET

PLAYBACK_SUITE = "com.privacy.downloader.engine.StreamDownloadPlaybackTest"


def ffmpeg_version(binary):
    if binary:
        try:
            result = subprocess.run([binary, "-version"], capture_output=True, check=True, text=True, timeout=5)
        except (OSError, ValueError, subprocess.SubprocessError):
            return None
        version = re.match(r"ffmpeg version ([a-zA-Z0-9._+-]{1,96})(?:\s|$)", result.stdout)
        if version:
            return version[1]
    return None


def summarize(root, exit_code):
    groups, playback = {}, {}
    for module in ("app", "downloader"):
        reports = root / "source" / module / "build" / "test-results" / "testDebugUnitTest"
        for path in sorted(reports.glob("TEST-*.xml")):
            suite = ET.parse(path).getroot()
            assert suite.tag == "testsuite"
            tests = int(suite.attrib["tests"])
            failures = int(suite.attrib["failures"]) + int(suite.attrib.get("errors", 0))
            skipped = int(suite.attrib["skipped"])
            groups[suite.attrib["name"]] = {"passed": tests - failures - skipped, "failed": failures, "skipped": skipped}
            if suite.attrib["name"] == PLAYBACK_SUITE:
                for case in suite.findall("testcase"):
                    status = ("failed" if case.find("failure") is not None or case.find("error") is not None else
                              "skipped" if case.find("skipped") is not None else "passed")
                    playback[case.attrib["name"]] = status
    binary = os.environ.get("FFMPEG_TEST_BIN")
    return {"schema": 1, "source_commit": os.environ["GITHUB_SHA"], "gradle_exit_code": exit_code,
            "ffmpeg_configured": bool(binary), "ffmpeg_version": ffmpeg_version(binary),
            "host_jvm_by_class": groups, "playback_by_test": playback}


def main():
    root = Path(sys.argv[1])
    record = summarize(root, int(sys.argv[2]))
    encoded = json.dumps(record, sort_keys=True, separators=(",", ":"))
    payload = (encoded + "\n").encode()
    (root / "proof" / "download-host-tests.json").write_bytes(payload)
    print(f"::notice title=Download host JVM result::DOWNLOAD_HOST_PROOF {encoded}")
    print(f"::notice title=Download host JVM checksum::DOWNLOAD_HOST_SHA256 {sha256(payload).hexdigest()}")
    return int(not record["host_jvm_by_class"])


if __name__ == "__main__":
    sys.exit(main())

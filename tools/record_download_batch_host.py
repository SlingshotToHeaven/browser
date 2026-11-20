#!/usr/bin/env python3
"""Summarize host-only Gradle/JUnit results without publishing XML or build output."""
import json
import os
from pathlib import Path
import sys
from xml.etree import ElementTree as ET


def summarize(root, exit_code):
    groups = {}
    for module in ("app", "downloader"):
        reports = root / "source" / module / "build" / "test-results" / "testDebugUnitTest"
        for path in sorted(reports.glob("TEST-*.xml")):
            suite = ET.parse(path).getroot()
            assert suite.tag == "testsuite"
            tests = int(suite.attrib["tests"])
            failures = int(suite.attrib["failures"]) + int(suite.attrib.get("errors", 0))
            skipped = int(suite.attrib["skipped"])
            groups[suite.attrib["name"]] = {"passed": tests - failures - skipped, "failed": failures, "skipped": skipped}
    return {"schema": 1, "source_commit": os.environ["GITHUB_SHA"], "gradle_exit_code": exit_code,
            "ffmpeg_configured": bool(os.environ.get("FFMPEG_TEST_BIN")), "host_jvm_by_class": groups}


def main():
    root = Path(sys.argv[1])
    record = summarize(root, int(sys.argv[2]))
    encoded = json.dumps(record, sort_keys=True, separators=(",", ":"))
    (root / "proof" / "download-host-tests.json").write_text(encoded + "\n")
    print(f"::notice title=Download host JVM result::DOWNLOAD_HOST_PROOF {encoded}")
    return int(not record["host_jvm_by_class"])


if __name__ == "__main__":
    sys.exit(main())

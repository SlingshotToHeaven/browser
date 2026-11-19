#!/usr/bin/env python3
"""Read-only checks for this project's standalone ARM64 APK or source native libraries."""
import argparse
from collections import defaultdict
import hashlib
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import zipfile

APP_ID = "com.browser"
MIN_SDK = 26
ABI = "arm64-v8a"
LIBRARIES = {
    "libavcodec.so", "libavdevice.so", "libavfilter.so", "libavformat.so", "libavutil.so",
    "libffmpegkit.so", "libswresample.so", "libswscale.so",
}
PAGE = 16 * 1024
ELF_HEADER = struct.Struct("<16sHHIQQQIHHHHHH")
PROGRAM_HEADER = struct.Struct("<IIQQQQQQ")


class FailedCheck(ValueError):
    """A failed artifact assertion, not a qualification of a build or device."""


def require(condition, message):
    if not condition:
        raise FailedCheck(message)


def stream_sha256(source):
    digest = hashlib.sha256()
    for data in iter(lambda: source.read(1024 * 1024), b""):
        digest.update(data)
    return digest.hexdigest()


def sha256(path):
    with path.open("rb") as source:
        return stream_sha256(source)


def read_exact(source, size, name):
    data = source.read(size)
    require(len(data) == size, f"{name}: truncated ELF header")
    return data


def check_elf(source, size, name):
    """Return PT_LOAD count. Only little-endian AArch64 shared objects are supported."""
    fields = ELF_HEADER.unpack(read_exact(source, ELF_HEADER.size, name))
    ident, kind, machine, version = fields[:4]
    require(ident[:7] == b"\x7fELF\x02\x01\x01" and (kind, machine, version) == (3, 183, 1),
            f"{name}: expected a little-endian ELF64 AArch64 shared object")
    phoff, ehsize, phentsize, phnum = fields[5], fields[8], fields[9], fields[10]
    require(ehsize == ELF_HEADER.size and phentsize == PROGRAM_HEADER.size and 0 < phnum <= 1024
            and ehsize <= phoff <= 1024 * 1024 and phoff + phentsize * phnum <= size,
            f"{name}: invalid or unsupported ELF program header table")
    source.seek(phoff)
    loads = 0
    for _ in range(phnum):
        ptype, _, offset, vaddr, _, filesz, memsz, align = PROGRAM_HEADER.unpack(read_exact(source, phentsize, name))
        if ptype != 1:  # PT_LOAD
            continue
        loads += 1
        require(filesz <= memsz and offset + filesz <= size, f"{name}: PT_LOAD outside file or invalid sizes")
        require(align >= PAGE and align & (align - 1) == 0 and (vaddr - offset) % align == 0,
                f"{name}: PT_LOAD is not compatible with 16 KiB pages (p_align=0x{align:x})")
    require(loads > 0, f"{name}: no PT_LOAD segments")
    return loads


def inspect_native_dir(directory):
    libraries = {p.name: p for p in directory.glob("*.so") if p.is_file()}
    require(set(libraries) == LIBRARIES, f"expected exactly the inventoried {ABI} library set")
    print(f"Checkout ELF checks ({ABI}; no APK or device tested):")
    for name, path in sorted(libraries.items()):
        size = path.stat().st_size
        with path.open("rb") as source:
            loads = check_elf(source, size, name)
        print(f"  {name:<18} {size:>9} bytes  {loads} PT_LOAD  SHA-256 {sha256(path)}")
    print(f"Native files: {sum(p.stat().st_size for p in libraries.values()):,} raw bytes")


def inspect_zip(path):
    """Check inventory and packaged ELF headers. zipalign separately verifies the ZIP data offsets."""
    sizes = defaultdict(lambda: [0, 0])  # on-disk ZIP member bytes, uncompressed bytes
    libraries = {}
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        names = {entry.filename for entry in entries}
        require(len(names) == len(entries), "APK contains duplicate ZIP entries")
        require({"AndroidManifest.xml", "classes.dex"}.issubset(names), "expected a standalone APK with manifest and code")
        native = {name for name in names if name.startswith("lib/") and name.endswith(".so")}
        require(native == {f"lib/{ABI}/{name}" for name in LIBRARIES},
                f"APK does not contain exactly the inventoried {ABI} library set")
        for entry in entries:
            name = entry.filename
            if name in native:
                require(entry.compress_type == zipfile.ZIP_STORED, f"{name}: native library must be stored uncompressed")
                with archive.open(entry) as source:
                    count = check_elf(source, entry.file_size, name)
                    source.seek(0)
                    libraries[name] = (count, stream_sha256(source))
                group = "native"
            elif name.startswith("assets/"):
                group = "assets"
            elif re.fullmatch(r"classes[0-9]*\.dex", name):
                group = "dex"
            elif name.startswith("res/") or name == "resources.arsc":
                group = "resources"
            else:
                group = "other"
            sizes[group][0] += entry.compress_size
            sizes[group][1] += entry.file_size
    return libraries, sizes


def sdk_tool(directory, name, *args):
    tool = directory / name
    require(tool.is_file(), f"SDK Build Tools executable not found: {tool}")
    result = subprocess.run([str(tool), *args], capture_output=True, text=True,
                            encoding="utf-8", errors="replace", timeout=120,
                            env={**os.environ, "LC_ALL": "C"})
    # Don't echo raw tool output (e.g. certificate subject, source paths or APK labels).
    require(result.returncode == 0, f"{name} rejected the artifact (exit {result.returncode}); run it locally for details")
    return result.stdout


def manifest_summary(badging, min_target):
    package = re.search(r"(?m)^package: name='([^']+)' versionCode='(\d+)'[^\n]*$", badging)
    require(package is not None and package[1] == APP_ID, f"expected release application ID {APP_ID}")
    require(not re.search(r"\bsplit=", package[0]), "split APKs require a separate package-set verification")
    require(not re.search(r"(?m)^application-debuggable\b", badging), "release APK is debuggable")
    # Accept both SDK tool spellings; if both appear, neither can contradict the other.
    minimums = re.findall(r"(?m)^(?:sdkVersion|minSdkVersion):([^\n]*)$", badging)
    target = re.search(r"(?m)^targetSdkVersion:'(\d+)'$", badging)
    require(set(minimums) == {f"'{MIN_SDK}'"}, f"expected minSdkVersion {MIN_SDK}")
    require(target is not None and int(target[1]) >= MIN_SDK, "missing or invalid targetSdkVersion")
    require(int(target[1]) >= min_target, f"targetSdkVersion {target[1]} is below the requested minimum {min_target}")
    return package[2], target[1]


def fingerprint(value):
    normalized = re.sub(r"[:\s]", "", value).lower()
    require(re.fullmatch(r"[0-9a-f]{64}", normalized) is not None, "expected a SHA-256 certificate fingerprint")
    return normalized


def check_signature(output, expected):
    certs = re.findall(r"(?mi)^Signer #\d+ certificate SHA-256 digest: ([0-9a-f:]+)\s*$", output)
    require(len(certs) == 1 and fingerprint(certs[0]) == expected,
            "certificate fingerprint does not match the single expected release signer")
    require(re.search(r"(?m)^Verified using v[23](?:\.\d+)? scheme [^\n]*: true\s*$", output),
            "an Android v2 or v3 APK signature must verify")


def inspect_apk(args):
    path = args.apk.resolve(strict=True)
    tools = args.build_tools.resolve(strict=True)
    size = path.stat().st_size
    if args.max_bytes is not None:
        require(size <= args.max_bytes, f"APK is {size:,} bytes; budget is {args.max_bytes:,} bytes")
    version, target = manifest_summary(sdk_tool(tools, "aapt2", "dump", "badging", str(path)), args.min_target_sdk)
    libraries, sizes = inspect_zip(path)
    sdk_tool(tools, "zipalign", "-c", "-P", "16", "4", str(path))
    expected = None
    if not args.inspect_only:
        expected = fingerprint(args.cert_sha256)
        signature = sdk_tool(tools, "apksigner", "verify", "--verbose", "--print-certs", "-Werr", str(path))
        check_signature(signature, expected)

    print(f"{APP_ID} | versionCode {version} | minSdk {MIN_SDK} | targetSdk {target} | standalone {ABI}")
    print(f"APK file: {size:,} bytes ({size / 1_000_000:.3f} MB / {size / 2**20:.3f} MiB)")
    print(f"APK SHA-256: {sha256(path)}")
    print(f"Native: {len(libraries)} stored libraries, ZIP alignment checked by zipalign")
    for name, (count, digest) in sorted(libraries.items()):
        print(f"  {name}  {count} compatible PT_LOAD  SHA-256 {digest}")
    print("ZIP members       stored bytes     raw bytes")
    for group, (compressed, raw) in sorted(sizes.items()):
        print(f"  {group:<13} {compressed:>12,}  {raw:>12,}")
    overhead = size - sum(compressed for compressed, _ in sizes.values())
    print(f"ZIP metadata, padding, and signing overhead (combined): {overhead:,} bytes")
    if expected is None:
        print("STRUCTURE ONLY — no signature was verified. Not a release sign-off.")
    else:
        print(f"Signature verified against certificate SHA-256 {expected}")
        print("Artifact checks passed. Device, rollout, and distribution gates are separate.")


def positive_int(value):
    number = int(value)
    if number <= 0:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return number


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    native = sub.add_parser("native", help="inspect the checkout libraries, not an APK")
    native.add_argument("directory", type=Path)
    apk = sub.add_parser("apk", help="inspect the exact standalone APK (SDK Build Tools 35+ on Linux/macOS)")
    apk.add_argument("apk", type=Path)
    apk.add_argument("--build-tools", required=True, type=Path)
    apk.add_argument("--max-bytes", type=positive_int)
    apk.add_argument("--min-target-sdk", type=positive_int, default=MIN_SDK)
    mode = apk.add_mutually_exclusive_group(required=True)
    mode.add_argument("--cert-sha256", help="trusted expected signer fingerprint, not taken from this APK")
    mode.add_argument("--inspect-only", action="store_true", help="run structure checks without verifying a signature")
    args = parser.parse_args(argv)
    try:
        if args.command == "native":
            inspect_native_dir(args.directory)
        else:
            inspect_apk(args)
        return 0
    except (OSError, FailedCheck, zipfile.BadZipFile, subprocess.TimeoutExpired) as error:
        print(f"Artifact check failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

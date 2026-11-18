#!/usr/bin/env python3
"""Materialize matched, build-only Android baselines from a source ZIP or checkout."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "tests/fixtures/perf_baselines"
LAYERS = (("candidate", "step19"), ("step19", "step18"), ("step18", "step17"))
MODULES = {"app", "downloader", "image-search"}
MODULE_FILES = {"build.gradle", "consumer-rules.pro", "proguard-rules.pro"}
ROOT_FILES = {
    "build.sh", "build.gradle", "settings.gradle", "gradle.properties", "gradlew", "gradlew.bat",
    "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties",
}


def is_build_input(name):
    first, _, rest = name.partition("/")
    return name in ROOT_FILES or (first in MODULES and (
        rest in MODULE_FILES or rest.startswith(("src/main/", "src/debug/"))))


def build_input_names():
    if (ROOT / ".git").exists():
        paths = subprocess.check_output(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=ROOT)
        names = {os.fsdecode(item) for item in paths.split(b"\0") if item}
        return sorted(name for name in names if is_build_input(name))

    # A GitHub ZIP has no index. Enumerate only the same build inputs, not the
    # user's other folders, local SDK configuration, or build/test output trees.
    names = set(ROOT_FILES)
    for module in MODULES:
        names.update(f"{module}/{name}" for name in MODULE_FILES)
        for source_set in ("main", "debug"):
            for path in (ROOT / module / "src" / source_set).rglob("*"):
                if path.name == ".DS_Store" or path.name.startswith("._"):
                    continue
                if path.is_symlink() or path.is_file():
                    names.add(path.relative_to(ROOT).as_posix())
    return sorted(names)


def copy_build_inputs(destination):
    for name in build_input_names():
        source = ROOT / name
        if source.resolve() != source:
            raise ValueError(f"Refusing a linked build input: {name}")
        if not source.is_file():
            continue  # deleted paths still present in the candidate's Git index
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
    missing = sorted(name for name in ROOT_FILES if not (destination / name).is_file())
    if missing:
        raise ValueError("Missing candidate build inputs: " + ", ".join(missing))


def prepare(destination):
    if destination.exists() or destination.is_symlink():
        raise ValueError("Choose a new output directory; existing paths are never overwritten")
    if destination.is_relative_to(ROOT):
        raise ValueError("Choose an output directory outside the candidate checkout")
    with tempfile.TemporaryDirectory(prefix="browser-baselines-", dir=destination.parent) as temporary:
        staged = Path(temporary) / "sources"
        staged.mkdir()
        # Do not inherit a Git index from the receiving worktree (or from the output parent).
        env = {k: v for k, v in os.environ.items() if k not in {"GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE"}}
        for newer, older in LAYERS:
            tree = staged / older
            if newer == "candidate":
                copy_build_inputs(tree)
            else:
                shutil.copytree(staged / newer, tree)
            patch = str(FIXTURES / f"{newer}-to-{older}.patch")
            cmd = ["git", "apply", "--whitespace=nowarn"]
            env["GIT_CEILING_DIRECTORIES"] = str(staged)
            subprocess.run([*cmd, "--check", patch], cwd=tree, env=env, check=True)
            subprocess.run([*cmd, patch], cwd=tree, env=env, check=True)

        hashes = (FIXTURES / "restored.sha256").read_text().splitlines()
        for entry in hashes:
            expected, name = entry.split("  ", 1)
            path = staged / name
            if not path.resolve().is_relative_to(staged) or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
                raise ValueError(f"Restored file does not match the saved baseline: {name}")
        if destination.exists() or destination.is_symlink():
            raise ValueError("Output directory appeared while preparing baselines")
        staged.rename(destination)
    print(f"Prepared {destination}/step17, step18, step19. Verified {len(hashes)} restored source hashes.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path, help="new directory outside the patched candidate checkout")
    args = parser.parse_args()
    try:
        path = args.destination.expanduser().absolute()
        prepare(path.parent.resolve() / path.name)
    except (OSError, ValueError, subprocess.CalledProcessError) as failure:
        parser.exit(1, f"Cannot prepare baselines: {failure}\n")


if __name__ == "__main__":
    main()

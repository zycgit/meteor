#!/usr/bin/env python3
"""Build official Go peers with a checksum-verified, project-local toolchain."""

import argparse
import hashlib
import json
import os
import platform
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request
import zipfile


GO_VERSION = "go1.27.2"


def fetch(url, target):
    with urllib.request.urlopen(url, timeout=120) as response, target.open("wb") as output:
        shutil.copyfileobj(response, output)


def prepare_toolchain(build_dir):
    system = {"Linux": "linux", "Darwin": "darwin", "Windows": "windows"}.get(platform.system())
    machine = {"x86_64": "amd64", "AMD64": "amd64", "aarch64": "arm64", "arm64": "arm64"}.get(platform.machine())
    if system is None or machine is None:
        raise RuntimeError("Unsupported Go peer toolchain platform: " + platform.platform())

    tools_dir = build_dir / "tools"
    tools_dir.mkdir(parents=True, exist_ok=True)
    home = tools_dir / GO_VERSION
    executable = home / "go" / "bin" / ("go.exe" if system == "windows" else "go")
    if executable.exists():
        version = subprocess.check_output([str(executable), "version"], text=True)
        if version.startswith("go version " + GO_VERSION + " "):
            return executable
        raise RuntimeError("Unexpected local Go version: " + version.strip())

    # The official release metadata owns the archive digest; no system installation is changed.
    with urllib.request.urlopen("https://go.dev/dl/?mode=json&include=all", timeout=120) as response:
        releases = json.load(response)
    release = next(item for item in releases if item["version"] == GO_VERSION)
    archive_meta = next(item for item in release["files"] if item["os"] == system
                        and item["arch"] == machine and item["kind"] == "archive")
    archive = tools_dir / archive_meta["filename"]
    if not archive.exists():
        temporary = archive.with_suffix(archive.suffix + ".part")
        fetch("https://go.dev/dl/" + archive_meta["filename"], temporary)
        temporary.replace(archive)
    with archive.open("rb") as downloaded:
        digest = hashlib.file_digest(downloaded, "sha256").hexdigest()
    if digest != archive_meta["sha256"]:
        raise RuntimeError("Go archive SHA256 mismatch: " + str(archive))
    (tools_dir / (GO_VERSION + "-metadata.json")).write_text(json.dumps(archive_meta, indent=2) + "\n")

    # extractall's data filter rejects absolute/traversal entries, even for official archives.
    home.mkdir(parents=True, exist_ok=True)
    if archive.name.endswith(".zip"):
        with zipfile.ZipFile(archive) as contents:
            for member in contents.namelist():
                if not (home / member).resolve().is_relative_to(home.resolve()):
                    raise RuntimeError("Unsafe Go archive entry: " + member)
            contents.extractall(home)
    else:
        with tarfile.open(archive) as contents:
            contents.extractall(home, filter="data")
    return executable


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--build-dir", type=Path, required=True)
    parser.add_argument("--toolchain-only", action="store_true")
    args = parser.parse_args()
    build_dir = args.build_dir.resolve()
    executable = prepare_toolchain(build_dir)
    if args.toolchain_only:
        print(executable)
        return

    # Keep the module cache, compiler cache and installed generators inside meteor-test/build.
    env = dict(os.environ, GOTOOLCHAIN="local", GOWORK="off", CGO_ENABLED="0",
               GOPATH=str(build_dir / "go-path"), GOMODCACHE=str(build_dir / "go-mod-cache"),
               GOCACHE=str(build_dir / "go-build-cache"))
    destination = build_dir / "go-peer" / ("order-peer.exe" if os.name == "nt" else "order-peer")
    destination.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(executable), "build", "-mod=readonly", "-trimpath", "-buildvcs=false", "-o", str(destination), "."],
                   cwd=Path(__file__).resolve().parent, env=env, check=True)
    print(destination)


if __name__ == "__main__":
    main()

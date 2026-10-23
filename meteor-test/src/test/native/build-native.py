#!/usr/bin/env python3
"""Compile the independent rpcgen/libtirpc test peers and capture their identity."""

import argparse
import ctypes.util
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess


def fingerprint(path):
    path = path.resolve(strict=True)
    return {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "size": path.stat().st_size}


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def invoke(command, log):
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=120)
    log.write_text("$ " + shlex.join(command) + "\n" + result.stdout +
                   "\nexit_code=" + str(result.returncode) + "\n")
    require(result.returncode == 0, f"Native compilation failed; see {log}")


def libraries(binary):
    result = subprocess.run(["ldd", str(binary)], text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=15)
    require(result.returncode == 0 and "not found" not in result.stdout,
            "Cannot resolve native runtime dependencies: " + result.stdout)
    paths = set()
    for line in result.stdout.splitlines():
        match = re.search(r"(?:=>\s*)?(/\S+)\s+\(0x[0-9a-fA-F]+\)", line)
        if match:
            paths.add(Path(match.group(1)).resolve(strict=True))
    require(any(path.name.startswith("libtirpc.so") for path in paths),
            "The native peer must link the independent libtirpc runtime")
    return sorted(paths)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--include", type=Path)
    parser.add_argument("--cc", default=os.environ.get("CC", "gcc"))
    parser.add_argument("--peer", choices=("all", "interop", "rpcbind"), default="all")
    args = parser.parse_args()

    # Sources and native dependencies are independent of all Java test outputs.
    source = Path(__file__).resolve().parent
    repository = source.parents[3]
    generated = source / "generated"
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    compiler = shutil.which(args.cc)
    require(compiler is not None, "A C compiler is required for the independent libtirpc test peer")
    compiler = str(Path(compiler).resolve(strict=True))
    candidates = [Path("/usr/include/tirpc"), repository / "build/oncrpc-stage2-20261017/tools/sysroot/usr/include/tirpc"]
    if args.include:
        candidates.insert(0, args.include)
    include = next((path.resolve() for path in candidates if (path / "rpc/rpc.h").is_file()), None)
    require(include is not None, "libtirpc headers are required; install libtirpc-dev or supply --include")
    library = ctypes.util.find_library("tirpc")
    require(library is not None and "/" not in library, "The libtirpc shared library is required")

    artifacts = []
    commands = []
    logs = []
    inputs = set(generated.glob("*.h")) | set(include.rglob("*.h"))
    inputs.update({source.parent / "rpc/interop.x", Path(__file__).resolve()})
    peers = ("interop", "rpcbind") if args.peer == "all" else (args.peer,)
    for peer in peers:
        name = "oncrpc_peer" if peer == "interop" else "oncrpc_rpcbind_peer"
        handwritten = source / (name + ".c")
        require(handwritten.is_file(), f"Missing native test harness: {handwritten}")
        vendor = [generated / "interop_xdr.c", generated / "interop_svc.c", generated / "interop_clnt.c"]
        sources = [handwritten, *vendor] if peer == "interop" else [handwritten]
        binary = output / name
        command = [compiler, "-std=c11", "-O2", "-Wall", "-Wextra", "-Wno-cast-function-type",
                   "-Wno-unused-variable", "-D_DEFAULT_SOURCE", "-I", str(include),
                   "-I", str(generated), *map(str, sources), "-Wl,-l:" + library,
                   "-pthread", "-o", str(binary)]
        log = output / (name + "-build.log")
        invoke(command, log)
        commands.append(shlex.join(command))
        logs.append(log)
        inputs.update(sources)
        linked = libraries(binary)
        artifacts.append({**fingerprint(binary), "libraries": [fingerprint(path) for path in linked]})

    # The manifest is derived from actual commands, ELF files and loader resolution.
    toolchain = []
    for command in ([compiler, "--version"], ["rpcgen", "--version"]):
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=15)
        require(result.returncode == 0, "Cannot capture native compiler identity")
        toolchain.append({"command": command, "output": result.stdout.strip()})
    value = {"kind": "native-oncrpc-peer", "status": "passed", "binaries": artifacts,
             "source_inputs": [fingerprint(path) for path in sorted(inputs)],
             "build_commands": commands, "build_logs": [fingerprint(path) for path in logs],
             "toolchain": toolchain}
    manifest = output / "native-peer-runtime.json"
    manifest.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n")
    print(manifest)


if __name__ == "__main__":
    main()

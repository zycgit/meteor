#!/usr/bin/env python3
"""Regenerate official Go bindings directly from the shared, unmodified Order proto."""

import argparse
import os
from pathlib import Path
import shutil
import subprocess


def main():
    source = Path(__file__).resolve().parent
    host = source.parents[2]
    parser = argparse.ArgumentParser()
    parser.add_argument("--build-dir", type=Path, default=host / "build")
    parser.add_argument("--go", default="go")
    parser.add_argument("--protoc-python", type=Path, required=True)
    args = parser.parse_args()
    build = args.build_dir.resolve()
    python = args.protoc_python.resolve()
    if not python.is_file():
        raise RuntimeError("--protoc-python must name a Python executable with grpc_tools.protoc")
    executable = shutil.which(args.go)
    if executable is None:
        raise RuntimeError("Install the development Go toolchain or pass --go /path/to/go")
    generators = build / "go-generators"
    env = dict(os.environ, GOTOOLCHAIN="local", GOWORK="off", CGO_ENABLED="0", GOBIN=str(generators),
               GOPATH=str(build / "go-path"), GOMODCACHE=str(build / "go-mod-cache"),
               GOCACHE=str(build / "go-build-cache"))
    packages = ["google.golang.org/protobuf/cmd/protoc-gen-go@v1.36.12",
                "google.golang.org/grpc/cmd/protoc-gen-go-grpc@v1.6.2",
                "connectrpc.com/connect/cmd/protoc-gen-connect-go@v1.19.1"]
    for package in packages:
        subprocess.run([str(executable), "install", package], env=env, cwd=source, check=True)

    # Both SDK contracts use the same pinned generators and committed Go module graph.
    contracts = [
        (host.parent / "met-example/src/main/resources/proto", "order.proto", "orderpb"),
        (host / "src/test/resources/vectors/types", "types.proto", "typepb"),
    ]

    for directory, filename, package in contracts:
        output = source / "gen" / package
        output.mkdir(parents=True, exist_ok=True)
        command = [str(python), "-m", "grpc_tools.protoc", "-I", str(directory)]
        for name in ("go", "go-grpc", "connect-go"):
            suffix = ".exe" if os.name == "nt" else ""
            command.extend(["--plugin=protoc-gen-" + name + "=" + str(generators / ("protoc-gen-" + name + suffix)),
                            "--" + name + "_out=" + str(output), "--" + name + "_opt=paths=source_relative",
                            "--" + name + "_opt=M" + filename + "=meteor.test/orderpeer/gen/" + package])
        command.append(filename)
        subprocess.run(command, env=env, cwd=source, check=True)

if __name__ == "__main__":
    main()

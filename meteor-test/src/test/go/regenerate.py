#!/usr/bin/env python3
"""Regenerate official Go bindings directly from the shared, unmodified Order proto."""

import argparse
import os
from pathlib import Path
import subprocess
import sys

sys.dont_write_bytecode = True
from prepare import prepare_toolchain


def main():
    source = Path(__file__).resolve().parent
    host = source.parents[2]
    parser = argparse.ArgumentParser()
    parser.add_argument("--build-dir", type=Path, default=host / "build")
    parser.add_argument("--protoc-python", type=Path)
    args = parser.parse_args()
    build = args.build_dir.resolve()
    python = args.protoc_python or build / "python-grpc-venv" / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
    if not python.is_file():
        raise RuntimeError("Prepare the host Python gRPC environment first or pass --protoc-python")
    executable = prepare_toolchain(build)
    generators = build / "go-generators"
    env = dict(os.environ, GOTOOLCHAIN="local", GOWORK="off", CGO_ENABLED="0", GOBIN=str(generators),
               GOPATH=str(build / "go-path"), GOMODCACHE=str(build / "go-mod-cache"),
               GOCACHE=str(build / "go-build-cache"))
    packages = ["google.golang.org/protobuf/cmd/protoc-gen-go@v1.36.12",
                "google.golang.org/grpc/cmd/protoc-gen-go-grpc@v1.6.2",
                "connectrpc.com/connect/cmd/protoc-gen-connect-go@v1.19.1"]
    for package in packages:
        subprocess.run([str(executable), "install", package], env=env, cwd=source, check=True)

    output = source / "gen" / "orderpb"
    output.mkdir(parents=True, exist_ok=True)
    command = [str(python), "-m", "grpc_tools.protoc", "-I", str(host.parent / "met-example/src/main/resources/proto")]
    for name in ("go", "go-grpc", "connect-go"):
        suffix = ".exe" if os.name == "nt" else ""
        command.extend(["--plugin=protoc-gen-" + name + "=" + str(generators / ("protoc-gen-" + name + suffix)),
                        "--" + name + "_out=" + str(output), "--" + name + "_opt=paths=source_relative",
                        "--" + name + "_opt=Morder.proto=meteor.test/orderpeer/gen/orderpb"])
    command.append("order.proto")
    subprocess.run(command, env=env, cwd=source, check=True)


if __name__ == "__main__":
    main()

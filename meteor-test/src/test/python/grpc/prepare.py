#!/usr/bin/env python3
# Copyright 2015-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0. See LICENSE.txt.
"""Prepare an isolated official SDK and generate clients from the shared order proto."""

import argparse
import os
from pathlib import Path
import subprocess
import venv


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--environment", type=Path, required=True)
    parser.add_argument("--generated", type=Path, required=True)
    parser.add_argument("--proto", type=Path, required=True)
    options = parser.parse_args()

    # Install only inside the test build directory, never in the host interpreter.
    executable = options.environment / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
    if not executable.exists():
        # Reuse existing interpreter symlinks when repairing an incomplete environment.
        venv.EnvBuilder(with_pip=True, symlinks=True).create(options.environment)
    subprocess.run([
        str(executable), "-c", "import sys; assert sys.prefix != sys.base_prefix, 'Isolated venv required'",
    ], check=True, timeout=10)
    requirements = Path(__file__).with_name("requirements.txt")
    subprocess.run([
        str(executable), "-m", "pip", "install", "--disable-pip-version-check",
        "--requirement", str(requirements),
    ], check=True, timeout=180)

    # Generate SDK stubs from the exact same service used by every other order demo.
    options.generated.mkdir(parents=True, exist_ok=True)
    subprocess.run([
        str(executable), "-m", "grpc_tools.protoc", "-I", str(options.proto.parent),
        "--python_out=" + str(options.generated), "--grpc_python_out=" + str(options.generated),
        str(options.proto),
    ], check=True, timeout=30)
    (options.generated / "requirements-installed.txt").write_bytes(requirements.read_bytes())


if __name__ == "__main__":
    main()

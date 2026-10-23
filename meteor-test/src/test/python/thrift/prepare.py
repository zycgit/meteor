#!/usr/bin/env python3
# Copyright 2015-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
# See the LICENSE.txt file for the full license.
# https://www.apache.org/licenses/LICENSE-2.0
"""Install the official Thrift SDK inside a test-local virtual environment."""

import argparse
import os
from pathlib import Path
import subprocess
import venv


def main():
    repository = Path(__file__).resolve().parents[5]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--environment", type=Path, default=repository / "build/python-thrift-venv")
    options = parser.parse_args()

    # Never install packages into the user's Python or system interpreter.
    executable = options.environment / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
    if not executable.exists():
        venv.EnvBuilder(with_pip=True, symlinks=os.name != "nt").create(options.environment)
    subprocess.run([
        str(executable), "-c", "import sys; assert sys.prefix != sys.base_prefix, 'Isolated venv required'",
    ], check=True, timeout=10)

    # Reuse an already prepared environment without contacting the package index.
    requirements = Path(__file__).with_name("requirements.txt")
    installed = subprocess.run([
        str(executable), "-c", "from importlib.metadata import version; print(version('thrift'))",
    ], capture_output=True, text=True, timeout=10)
    if installed.returncode == 0 and installed.stdout.strip() == "0.22.0":
        options.environment.joinpath("requirements-installed.txt").write_bytes(requirements.read_bytes())
        return
    subprocess.run([
        str(executable), "-m", "pip", "install", "--disable-pip-version-check",
        "--requirement", str(requirements),
    ], check=True, timeout=180)
    options.environment.joinpath("requirements-installed.txt").write_bytes(requirements.read_bytes())


if __name__ == "__main__":
    main()

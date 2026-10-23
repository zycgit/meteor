#!/usr/bin/env python3
# Copyright 2015-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0. See LICENSE.txt.
"""Prepare isolated, version-locked Zeep/soapfish2 SDKs and the SDK-generated order WSDL."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import venv


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--environment", required=True, type=Path)
    parser.add_argument("--generated", required=True, type=Path)
    options = parser.parse_args()

    # Install only into the test-owned virtual environment, never the user's interpreter.
    executable = options.environment / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
    if not executable.exists():
        venv.EnvBuilder(with_pip=True, symlinks=os.name != "nt").create(options.environment)
    requirements = Path(__file__).with_name("requirements.txt")
    expected = dict(line.split("==", 1) for line in requirements.read_text().splitlines() if line)
    probe = subprocess.run([
        str(executable), "-c",
        "import json,sys; from importlib.metadata import version; "
        "assert sys.prefix != sys.base_prefix; "
        "print(json.dumps({name:version(name) for name in sys.argv[1:]}))",
        *expected,
    ], capture_output=True, text=True, timeout=10)
    if probe.returncode != 0 or json.loads(probe.stdout) != expected:
        subprocess.run([
            str(executable), "-m", "pip", "install", "--disable-pip-version-check",
            "--requirement", str(requirements),
        ], check=True, timeout=180)

    # Contract generation is delegated to the actual provider SDK, with no handwritten SOAP XML.
    options.generated.mkdir(parents=True, exist_ok=True)
    subprocess.run([
        str(executable), str(Path(__file__).with_name("peer.py")), "contract", "--output",
        str(options.generated / "orders.wsdl"),
    ], check=True, timeout=20)
    options.generated.joinpath("requirements-installed.txt").write_bytes(requirements.read_bytes())


if __name__ == "__main__":
    main()

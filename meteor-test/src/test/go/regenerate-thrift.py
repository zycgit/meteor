#!/usr/bin/env python3
"""Generate official Go bindings from the unmodified shared Order Thrift IDL."""

import argparse
from pathlib import Path
import subprocess


def main():
    source = Path(__file__).resolve().parent
    repository = source.parents[3]
    parser = argparse.ArgumentParser()
    parser.add_argument("--compiler", type=Path, required=True)
    args = parser.parse_args()
    compiler = args.compiler.resolve()
    version = subprocess.check_output([str(compiler), "--version"], text=True).strip()
    if version != "Thrift version 0.25.0":
        raise RuntimeError("Expected Apache Thrift Compiler 0.25.0, got " + version)

    # No copied IDL or manual generated-runtime substitute: only the output package is selected.
    output = source / "gen"
    output.mkdir(parents=True, exist_ok=True)
    contract = repository / "meteor-test/src/test/thrift/order.thrift"
    subprocess.run([str(compiler), "--gen", "go:package=thriftorder,skip_remote", "-out", str(output), str(contract)],
                   check=True)


if __name__ == "__main__":
    main()

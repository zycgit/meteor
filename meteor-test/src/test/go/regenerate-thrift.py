#!/usr/bin/env python3
"""Generate official Go bindings from the shared order and data-type Thrift contracts."""

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

    # The order demo and type matrix use the same maintained IDL as their other-language peers.
    output = source / "gen"
    output.mkdir(parents=True, exist_ok=True)
    contracts = [
        ("thriftorder", repository / "meteor-test/src/test/thrift/order.thrift"),
        ("typecontract", repository / "meteor-test/src/test/resources/vectors/types/types.thrift"),
    ]
    for package, contract in contracts:
        generator = "go:package=" + package + ",skip_remote"
        subprocess.run([str(compiler), "--gen", generator, "-out", str(output), str(contract)], check=True)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Regenerate or verify the official rpcgen C artifacts without rewriting them."""

import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    contract = Path(__file__).resolve().parent / "interop.x"
    generated = contract.parent.parent / "native/generated"
    require_rpcgen = shutil.which("rpcgen")
    if require_rpcgen is None:
        raise RuntimeError("rpcgen is required only when regenerating the checked-in native contract")
    with tempfile.TemporaryDirectory(prefix="meteor-oncrpc-rpcgen-") as temporary:
        directory = Path(temporary)
        shutil.copyfile(contract, directory / contract.name)
        for option, name in (("-h", "interop.h"), ("-c", "interop_xdr.c"),
                             ("-l", "interop_clnt.c"), ("-m", "interop_svc.c")):
            result = subprocess.run([require_rpcgen, option, contract.name], cwd=directory,
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
            if result.returncode != 0:
                raise RuntimeError(result.stderr.decode())
            destination = generated / name
            if args.check:
                if destination.read_bytes() != result.stdout:
                    raise RuntimeError("Generated native source has drifted: " + str(destination))
            else:
                generated.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(result.stdout)
    print("Official rpcgen contract verified" if args.check else "Official rpcgen contract regenerated")


if __name__ == "__main__":
    main()

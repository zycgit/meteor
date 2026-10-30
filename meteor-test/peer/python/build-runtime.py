#!/usr/bin/env python3
"""Generate Python SDK contracts once in the shared runtime output volume."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import importlib.util
import shutil


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--output", type=Path, default=Path("/opt/meteor-peer"))
    parser.add_argument("--toolchain", type=Path, default=Path("/opt/meteor-toolchain"))
    args = parser.parse_args()

    # SDK installations belong to the image; the output volume retains the old executable paths.
    source = Path(__file__).resolve().parent
    resources = source.parents[1] / "resource"
    output = args.output
    output.mkdir(parents=True, exist_ok=True)
    ready = output / "runtime-python.ready.json"
    ready.unlink(missing_ok=True)
    for peer in ("grpc", "thrift", "soap", "connect", "hprose", "hessian", "oncrpc", "triple"):
        installed = args.toolchain / f"python-{peer}"
        requirements = source / peer / "requirements.txt"
        if requirements.read_bytes() != (installed / "requirements-installed.txt").read_bytes():
            raise RuntimeError(f"Python requirements changed; rebuild the image: {peer}")
        executable = output / f"python-{peer}"
        executable.unlink(missing_ok=True)
        executable.symlink_to(installed, target_is_directory=True)

    # Generated gRPC modules and SOAP contracts are consumed by both Python and Java scenarios.
    python = str(output / "python-grpc/bin/python")
    for contract, directory in ((resources / "protocol/proto/order.proto", "python-grpc-generated"),
                                (resources / "protocol/proto/types/types.proto", "python-types-grpc-generated")):
        target = output / directory
        target.mkdir(parents=True, exist_ok=True)
        subprocess.run([python, "-m", "grpc_tools.protoc", "-I", str(contract.parent),
                        f"--python_out={target}", f"--grpc_python_out={target}", str(contract)], check=True)
        (target / "requirements-installed.txt").write_bytes((source / "grpc/requirements.txt").read_bytes())

    # The official Connect generator binds the same order.proto to its sync client and WSGI server.
    connect = output / "python-connect-generated"
    connect_package = connect / "order_contract"
    connect_package.mkdir(parents=True, exist_ok=True)
    (connect_package / "__init__.py").touch()
    contract = resources / "protocol/proto/order.proto"
    plugin = output / "python-connect/bin/protoc-gen-connectrpc"
    subprocess.run([python, "-m", "grpc_tools.protoc", "-I", str(contract.parent),
                    f"--python_out={connect_package}", f"--plugin=protoc-gen-connectrpc={plugin}",
                    f"--connectrpc_out={connect_package}", "--connectrpc_opt=protobuf=google", str(contract)], check=True)
    (connect / "requirements-installed.txt").write_bytes((source / "connect/requirements.txt").read_bytes())
    soap = output / "python-soap-generated"
    soap.mkdir(parents=True, exist_ok=True)
    for script, name in (("peer.py", "orders.wsdl"), ("type_peer.py", "types.wsdl")):
        subprocess.run([str(output / "python-soap/bin/python"), str(source / "soap" / script),
                        "contract", "--output", str(soap / name)], check=True)
    (soap / "requirements-installed.txt").write_bytes((source / "soap/requirements.txt").read_bytes())

    # Version preparation belongs to this shared language runtime, not to individual testcases.
    spec = importlib.util.spec_from_file_location("python_sdk_versions", source / "prepare-versions.py")
    versions = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(versions)
    versions.prepare(source, args.toolchain, output)

    # Preserve the actual SDK identities and resolved dependencies before the peer volume is removed.
    archive = source.parents[1] / "build/preparation/sdk-versions/python"
    archive.mkdir(parents=True, exist_ok=True)
    manifest = output / "python-sdk-versions.json"
    shutil.copyfile(manifest, archive / manifest.name)
    for profile in json.loads(manifest.read_text()):
        runtime = Path(profile["runtime"])
        destination = archive / runtime.name
        destination.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(runtime / "requirements-installed.txt", destination / "requirements-installed.txt")
    for family, _, version in versions.VERSIONS:
        runtime = args.toolchain / f"python-{family}-{version}"
        shutil.copyfile(runtime / "requirements-resolved.txt", archive / runtime.name / "requirements-resolved.txt")

    # Health checks only succeed after all compiler and contract-generation commands succeeded.
    digest = hashlib.sha256()
    for path in sorted(source.rglob("*.py")):
        digest.update(path.relative_to(source).as_posix().encode())
        digest.update(path.read_bytes())
    ready.write_text(json.dumps({"runtime": "python", "source_sha256": digest.hexdigest()}) + "\n")
    print(ready, flush=True)
    if not args.prepare_only:
        os.execlp("sleep", "sleep", "infinity")


if __name__ == "__main__":
    main()

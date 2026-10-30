#!/usr/bin/env python3
"""Prepare version-isolated SDKs inside the existing Python language container."""

import argparse
import json
from pathlib import Path
import subprocess
import sys


# Each entry is a compatibility boundary, not an enumeration of every minor release.
VERSIONS = (
    ("connect", "connectrpc", "0.10.1"),
    ("connect", "connectrpc", "0.11.1"),
    ("soap", "zeep", "3.4.0"),
    ("thrift", "thrift", "0.13.0"),
    ("thrift", "thrift", "0.16.0"),
    ("thrift", "thrift", "0.25.0"),
)


def installed_version(executable, distribution, expected):
    code = "from importlib.metadata import version; import sys; print(version(sys.argv[1]))"
    actual = subprocess.check_output([str(executable), "-c", code, distribution], text=True).strip()
    if actual != expected:
        raise RuntimeError(f"SDK version differs: {distribution} expected {expected}, installed {actual}")
    python = subprocess.check_output([str(executable), "--version"], text=True).strip()
    if distribution == "zeep" and expected == "3.4.0" and python != "Python 3.12.16":
        raise RuntimeError(f"Legacy Zeep must use the pinned Python 3.12.16, found {python}")
    print(f"SDK {distribution}@{actual} {python}", flush=True)
    return actual


def install(source, toolchain):
    for family, distribution, version in VERSIONS:
        requirements = source / family / "versions" / f"{version}.txt"
        target = toolchain / f"python-{family}-{version}"
        interpreter = sys.executable
        if family == "soap" and version == "3.4.0":
            # Zeep 3 imports stdlib cgi, removed in Python 3.13; keep its SDK unchanged.
            interpreter = str(toolchain / "python-legacy/bin/python3.12")
        subprocess.run([interpreter, "-m", "venv", str(target)], check=True)
        executable = target / "bin/python"
        subprocess.run([str(executable), "-m", "pip", "install", "-r", str(requirements)], check=True)
        installed_version(executable, distribution, version)
        (target / "requirements-installed.txt").write_bytes(requirements.read_bytes())
        frozen = subprocess.check_output([str(executable), "-m", "pip", "freeze"], text=True)
        (target / "requirements-resolved.txt").write_text(frozen)


def prepare(source, toolchain, output):
    # Reuse the image's immutable venvs; every test starts only its own SDK process.
    installed = []
    for family, distribution, version in VERSIONS:
        requirements = source / family / "versions" / f"{version}.txt"
        target = toolchain / f"python-{family}-{version}"
        if requirements.read_bytes() != (target / "requirements-installed.txt").read_bytes():
            raise RuntimeError(f"Python version requirements changed; rebuild image: {family}@{version}")
        link = output / target.name
        link.unlink(missing_ok=True)
        link.symlink_to(target, target_is_directory=True)
        actual = installed_version(target / "bin/python", distribution, version)
        installed.append({"sdk": distribution, "version": actual, "runtime": str(link)})

    # Baseline environments also have version-qualified paths and verified runtime identities.
    for family, distribution, version in (("grpc", "grpcio", "1.85.0"),
                                           ("connect", "connectrpc", "0.13.0"),
                                           ("soap", "zeep", "4.3.3")):
        target = output / f"python-{family}"
        link = output / f"python-{family}-{version}"
        link.unlink(missing_ok=True)
        link.symlink_to(target, target_is_directory=True)
        actual = installed_version(target / "bin/python", distribution, version)
        installed.append({"sdk": distribution, "version": actual, "runtime": str(link)})
    (output / "python-sdk-versions.json").write_text(json.dumps(installed, indent=2) + "\n")

    # Each Connect generator is paired with its own runtime and message API.
    contract = source.parents[1] / "resource/protocol/proto/order.proto"
    protoc = output / "python-grpc/bin/python"
    profiles = (("0.10.1", "google"), ("0.11.1", "google"),
                ("0.11.1", "native"), ("0.13.0", "google"), ("0.13.0", "native"))
    for version, message_api in profiles:
        generated = output / f"python-connect-{version}-{message_api}-generated"
        package = generated / "order_contract"
        package.mkdir(parents=True, exist_ok=True)
        (package / "__init__.py").touch()
        environment = output / f"python-connect-{version}"
        command = [str(protoc), "-m", "grpc_tools.protoc", "-I", str(contract.parent)]
        plugin = environment / "bin/protoc-gen-connectrpc"
        command.extend([f"--plugin=protoc-gen-connectrpc={plugin}", f"--connectrpc_out={package}"])
        if message_api == "google":
            command.append(f"--python_out={package}")
            if version != "0.10.1":
                command.append("--connectrpc_opt=protobuf=google")
        else:
            message_plugin = environment / "bin/protoc-gen-py"
            command.extend([f"--plugin=protoc-gen-py={message_plugin}", f"--py_out={package}"])
        subprocess.run(command + [str(contract)], check=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--install", action="store_true")
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--toolchain", type=Path, default=Path("/opt/meteor-toolchain"))
    arguments = parser.parse_args()
    if not arguments.install:
        parser.error("Contract preparation is called by build-runtime.py")
    install(arguments.source, arguments.toolchain)

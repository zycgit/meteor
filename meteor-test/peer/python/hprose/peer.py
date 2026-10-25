#!/usr/bin/env python3
"""Official Hprose Python SDK HTTP peer for the basic compatibility matrix."""

import argparse
import sys
import threading
from wsgiref.simple_server import make_server

from hprose import HproseHttpClient, HproseHttpService


def provider():
    # The SDK owns Hprose encoding and dispatch; WSGI supplies the HTTP listener.
    service = HproseHttpService()
    service.addFunction(lambda value: value, "sample_echo")
    with make_server("0.0.0.0", 0, service) as server:
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        print(f"READY {server.server_port}", flush=True)
        try:
            sys.stdin.buffer.read()
        finally:
            server.shutdown()
            worker.join(timeout=5)


def consumer(target):
    # Call the actual Meteor listener through the unmodified official client.
    service = HproseHttpClient(target)
    service.timeout = 10
    expected = "Python Hprose 中文"
    actual = service.sample_echo(expected)
    assert actual == expected, (expected, actual)
    print("OK", flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("role", choices=("provider", "consumer"))
    parser.add_argument("--target")
    args = parser.parse_args()
    if args.role == "provider":
        provider()
    else:
        consumer(args.target)


if __name__ == "__main__":
    main()

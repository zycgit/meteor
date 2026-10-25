#!/usr/bin/env python3
"""python-hessian provides an HTTP consumer, not a Hessian RPC server."""

import argparse

from pyhessian.client import HessianProxy


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", required=True)
    args = parser.parse_args()

    # The SDK owns the Hessian 2 call envelope, HTTP request and reply decoding.
    service = HessianProxy(args.target, version=2, timeout=10)
    expected = "Python Hessian 中文"
    actual = service.echo(expected)
    assert actual == expected, (expected, actual)
    print("OK", flush=True)


if __name__ == "__main__":
    main()

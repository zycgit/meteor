#!/usr/bin/env python3
"""Development sunrpc SDK peer; standard-xdrlib supplies Python's removed module."""

import argparse
import asyncio
import sys

from sunrpc.client import TCPClient, rpc_client_obtain, rpc_client_send
from sunrpc.server import AsyncTCPServer, rpc_server_obtain, rpc_server_return
from sunrpc.types import RpcStr


PROGRAM = 0x31234567
VERSION = 1


class EchoClient(TCPClient):
    @rpc_client_send(1, RpcStr)
    @rpc_client_obtain(RpcStr)
    def echo(self, result):
        return result


class EchoServer(AsyncTCPServer):
    def __init__(self):
        super().__init__("0.0.0.0", 0, PROGRAM, VERSION)
        self.add_method(1, self.echo)

    @rpc_server_obtain(RpcStr)
    @rpc_server_return(RpcStr)
    def echo(self, value):
        return [value]


async def provider():
    # SDK bind starts the asyncio listener; stdin only controls its lifetime.
    server = EchoServer()
    await server.bind()
    print(f"READY {server.port}", flush=True)
    try:
        await asyncio.to_thread(sys.stdin.buffer.read)
    finally:
        server.server.close()
        await server.server.wait_closed()


def consumer(host, port):
    # The SDK owns TCP record framing, RPC headers, XDR strings and response parsing.
    client = EchoClient(host, port, PROGRAM, VERSION)
    client.connect()
    client.sock.settimeout(10)
    try:
        expected = "Python sunrpc to Meteor"
        actual = client.echo(expected)
        assert actual == expected, (expected, actual)
        print("OK", flush=True)
    finally:
        client.close()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("role", choices=("provider", "consumer"))
    parser.add_argument("--host")
    parser.add_argument("--port", type=int)
    args = parser.parse_args()
    if args.role == "provider":
        asyncio.run(provider())
    else:
        consumer(args.host, args.port)


if __name__ == "__main__":
    main()

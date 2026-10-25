#!/usr/bin/env python3
"""Apache Dubbo Python 3.0.0b1 Triple/Protobuf unary compatibility peer."""

import argparse
import socket
import sys

import dubbo
from dubbo.configs import ReferenceConfig, ServiceConfig
from dubbo.proxy.handlers import RpcMethodHandler, RpcServiceHandler
from google.protobuf.wrappers_pb2 import StringValue


SERVICE = "net.hasor.peer.protocol.triple.DubboProtoService"


def provider():
    # This beta treats port zero as its default port and exposes no bound-port query.
    # Select an ephemeral candidate once, then let the actual SDK establish its listener.
    with socket.socket() as candidate:
        candidate.bind(("0.0.0.0", 0))
        port = candidate.getsockname()[1]

    handler = RpcMethodHandler.unary(
        method=lambda request: request,
        method_name="echo",
        request_deserializer=StringValue.FromString,
        response_serializer=StringValue.SerializeToString,
    )
    service = RpcServiceHandler(service_name=SERVICE, method_handlers=[handler])
    server = dubbo.Server(ServiceConfig(service_handler=service, host="0.0.0.0", port=port))
    server.start()
    print(f"READY {port}", flush=True)

    # The beta has no public Server.close; this exclusive process owns the listener.
    # EOF ends the process, so the OS releases its sockets and the SDK's daemon event loop.
    sys.stdin.buffer.read()


def consumer(target):
    # Serializer hooks use the official Protobuf wrapper; Dubbo owns HTTP/2 and Triple framing.
    config = ReferenceConfig.from_url(f"tri://{target}/{SERVICE}")
    client = dubbo.Client(config)
    echo = client.unary(
        method_name="echo",
        request_serializer=StringValue.SerializeToString,
        response_deserializer=StringValue.FromString,
    )
    expected = StringValue(value="Python Triple 中文 🙂")
    actual = echo(expected)
    assert actual == expected, (expected, actual)
    print("OK", flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("role", choices=("provider", "consumer"))
    parser.add_argument("--target")
    arguments = parser.parse_args()
    if arguments.role == "provider":
        provider()
    else:
        consumer(arguments.target)


if __name__ == "__main__":
    main()

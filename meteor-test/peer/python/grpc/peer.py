#!/usr/bin/env python3
# Copyright 2015-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0. See LICENSE.txt.
"""Official gRPC Python provider/consumer for the shared Order save/find contract."""

import argparse
import base64
from concurrent import futures
import json
from importlib.metadata import version
from pathlib import Path
import sys

import grpc


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("role", choices=("provider", "consumer"))
    parser.add_argument("--generated", type=Path, required=True)
    parser.add_argument("--certificates", type=Path, required=True)
    parser.add_argument("--security", choices=("clear", "tls", "mtls"), default="clear")
    parser.add_argument("--target")
    parser.add_argument("--scenario", choices=("ascii", "unicode", "bytes", "int32-min", "int32-max", "failure"))
    parser.add_argument("--sdk-version", default="1.85.0")
    return parser.parse_args()


def provider(options, messages, services):
    class OrderService(services.OrderDemoServiceServicer):
        def __init__(self):
            self.orders = {}

        def save(self, request, context):
            # Copy the generated message so callers cannot mutate stored order state.
            stored = messages.Order()
            stored.CopyFrom(request)
            self.orders[request.id] = stored
            return stored

        def find(self, request, context):
            stored = self.orders.get(request.id)
            if stored is None:
                context.abort(grpc.StatusCode.INTERNAL, "Order not found: " + str(request.id))
            return stored

    # The official SDK owns HTTP/2, framing, Protobuf dispatch and TLS negotiation.
    with futures.ThreadPoolExecutor(max_workers=2) as executor:
        server = grpc.server(executor)
        services.add_OrderDemoServiceServicer_to_server(OrderService(), server)
        if options.security == "clear":
            port = server.add_insecure_port("0.0.0.0:0")
        else:
            certificates = options.certificates
            identity = ((certificates.joinpath("server-key.pem").read_bytes(),
                         certificates.joinpath("server.pem").read_bytes()),)
            credentials = grpc.ssl_server_credentials(
                identity, root_certificates=certificates.joinpath("ca.pem").read_bytes(),
                require_client_auth=options.security == "mtls",
            )
            port = server.add_secure_port("0.0.0.0:0", credentials)
        if port == 0:
            raise RuntimeError("Official Python provider did not bind a listener")
        server.start()
        print("READY " + str(port), flush=True)

        # The parent owns process lifetime; EOF means it has finished its scenario.
        try:
            for _ in sys.stdin:
                pass
        finally:
            server.stop(grace=0).wait(timeout=5)


def order(options, messages):
    value = messages.Order(id=42, description="Order from Python", attachment=b"Meteor")
    if options.scenario == "unicode":
        value.description = "Python 订单 🙂 <&>"
    elif options.scenario == "bytes":
        value.attachment = bytes((0, 1, 127, 128, 255))
    elif options.scenario == "int32-min":
        value.id = -(2 ** 31)
    elif options.scenario == "int32-max":
        value.id = 2 ** 31 - 1
    return value


def consumer(options, messages, services):
    # Certificate verification uses the same CA as the Java trust store; no overrides.
    if options.security == "clear":
        channel = grpc.insecure_channel(options.target)
    else:
        certificates = options.certificates
        private_key = None
        certificate_chain = None
        if options.security == "mtls":
            private_key = certificates.joinpath("client-key.pem").read_bytes()
            certificate_chain = certificates.joinpath("client.pem").read_bytes()
        credentials = grpc.ssl_channel_credentials(
            root_certificates=certificates.joinpath("ca.pem").read_bytes(),
            private_key=private_key, certificate_chain=certificate_chain,
        )
        channel = grpc.secure_channel(options.target, credentials)
    with channel:
        grpc.channel_ready_future(channel).result(timeout=5)
        client = services.OrderDemoServiceStub(channel)

        # A missing order is a business failure, not a timeout or transport failure.
        if options.scenario == "failure":
            try:
                client.find(messages.OrderQuery(id=404), timeout=5)
            except grpc.RpcError as failure:
                assert failure.code() == grpc.StatusCode.INTERNAL, failure
                assert "Order not found: 404" in failure.details(), failure
                result = {"status": failure.code().name, "message": failure.details()}
            else:
                raise AssertionError("A missing order unexpectedly succeeded")
        else:
            expected = order(options, messages)
            saved = client.save(expected, timeout=5)
            found = client.find(messages.OrderQuery(id=expected.id), timeout=5)
            assert saved == expected, (saved, expected)
            assert found == expected, (found, expected)
            result = {
                "id": found.id, "description": found.description,
                "attachment": base64.b64encode(found.attachment).decode("ascii"),
            }
        print("RESULT " + json.dumps(result, ensure_ascii=False), flush=True)


def main():
    options = arguments()
    actual = version("grpcio")
    if actual != options.sdk_version:
        raise RuntimeError(f"Expected grpcio {options.sdk_version}, installed {actual}")
    print(f"SDK grpcio@{actual}", flush=True)
    sys.path.insert(0, str(options.generated))
    import order_pb2
    import order_pb2_grpc

    if options.role == "provider":
        provider(options, order_pb2, order_pb2_grpc)
    else:
        consumer(options, order_pb2, order_pb2_grpc)


if __name__ == "__main__":
    main()

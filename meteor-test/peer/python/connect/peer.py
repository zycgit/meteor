#!/usr/bin/env python3
"""Official Connect Python SDK unary order exchange over Connect or gRPC-Web."""

import argparse
import base64
import copy
from importlib import import_module
from importlib.metadata import version
import json
import sys
import threading
from waitress.server import create_server


def provider(bindings):
    # Generated WSGI endpoints own protocol selection, framing and Protobuf serialization.
    class OrderStore:
        def __init__(self):
            self.orders = {}

        def save(self, request, context):
            self.orders[request.id] = copy.deepcopy(request)
            return copy.deepcopy(self.orders[request.id])

        def find(self, request, context):
            return copy.deepcopy(self.orders[request.id])

    application = bindings.OrderDemoServiceWSGIApplication(OrderStore())
    # The standard threaded WSGI host supplies message-bounded input for both lengths and chunks.
    server = create_server(application, host="0.0.0.0", port=0, threads=2)
    worker = threading.Thread(target=server.run, daemon=True)
    worker.start()
    print(f"READY {server.effective_port}", flush=True)

    # EOF is the scenario owner's shutdown signal; no process or shared container is abandoned.
    try:
        sys.stdin.buffer.read()
    finally:
        server.task_dispatcher.shutdown()
        server.close()
        worker.join(timeout=5)


def consumer(bindings, messages, arguments):
    from connectrpc.protocol import ProtocolType

    # Only the official SDK changes wire protocol; both directions use the same order contract.
    protocol = ProtocolType.CONNECT
    if arguments.protocol == "grpc-web":
        protocol = ProtocolType.GRPC_WEB
    request = messages.Order(id=1001, description="Python Connect 订单 🙂", attachment=b"Meteor")
    with bindings.OrderDemoServiceClientSync(arguments.target, protocol=protocol,
                                           send_compression=None, timeout_ms=10000) as client:
        saved = client.save(request)
        found = client.find(messages.OrderQuery(id=request.id))
        assert saved == request, f"save returned {saved!r} instead of {request!r}"
        assert found == request, f"find returned {found!r} instead of {request!r}"

    # JSON is control-plane evidence; it is never the RPC payload codec.
    result = {"id": found.id, "description": found.description,
              "attachment": base64.b64encode(found.attachment).decode("ascii")}
    print("RESULT " + json.dumps(result, ensure_ascii=False), flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("role", choices=("provider", "consumer"))
    parser.add_argument("--protocol", choices=("connect", "grpc-web"), required=True)
    parser.add_argument("--generated", required=True)
    parser.add_argument("--target")
    parser.add_argument("--sdk-version", default="0.13.0")
    parser.add_argument("--message-api", choices=("google", "native"), default="google")
    arguments = parser.parse_args()
    actual = version("connectrpc")
    if actual != arguments.sdk_version:
        raise RuntimeError(f"Expected connectrpc {arguments.sdk_version}, installed {actual}")
    print(f"SDK connectrpc@{actual} message-api={arguments.message_api}", flush=True)
    sys.path.insert(0, arguments.generated)
    # The 0.10 generator emits absolute sibling imports; later generators use package-relative imports.
    if actual == "0.10.1":
        sys.path.insert(0, arguments.generated + "/order_contract")
    from order_contract import order_connect
    if arguments.message_api == "google":
        module = "order_pb2" if actual == "0.10.1" else "order_contract.order_pb2"
        messages = import_module(module)
    else:
        from order_contract import order_pb as messages

    if arguments.role == "provider":
        provider(order_connect)
    else:
        consumer(order_connect, messages, arguments)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
# Copyright 2015-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0. See LICENSE.txt.
"""Order save/find through Python's XML-RPC SDK; JSON lines only control the process."""

import argparse
import base64
import json
import socket
import sys
import threading
from xmlrpc.client import Binary, ServerProxy
from xmlrpc.server import SimpleXMLRPCRequestHandler, SimpleXMLRPCServer


def emit(value):
    print(json.dumps(value, ensure_ascii=False), flush=True)


def wire_order(order):
    # The shared JSON vector spells bytes as base64; the XML-RPC wire carries base64 values.
    return {
        "id": order["id"],
        "description": order["description"],
        "attachment": Binary(base64.b64decode(order["attachment"])),
    }


def json_order(order):
    attachment = order["attachment"]
    if isinstance(attachment, Binary):
        attachment = attachment.data
    return {
        "id": order["id"],
        "description": order["description"],
        "attachment": base64.b64encode(attachment).decode("ascii"),
    }


def consume():
    # No URL is supplied at startup. Every business target comes from the explicit registry resolver.
    emit({"ready": True, "role": "consumer"})
    for line in sys.stdin:
        command = json.loads(line)
        if command["operation"] == "quit":
            return
        try:
            targets = command["endpoints"]
            if len(targets) != 1:
                raise ValueError("One discovered provider is required")
            with ServerProxy(targets[0], use_builtin_types=True) as orders:
                order = wire_order(command["order"])
                orders.orders.save(order)
                result = orders.orders.find({"id": order["id"]})
            emit({"result": json_order(result)})
        except Exception as failure:
            emit({"error": str(failure)})


def provide(excluded_port):
    # The operating system chooses the listener; a restarted provider excludes the previous port.
    SimpleXMLRPCRequestHandler.rpc_paths = ("/xmlrpc",)
    while True:
        server = SimpleXMLRPCServer(
            ("127.0.0.1", 0), requestHandler=SimpleXMLRPCRequestHandler,
            logRequests=False, allow_none=True, use_builtin_types=True,
        )
        if server.server_address[1] != excluded_port:
            break
        server.server_close()

    # Both implementations expose the same orders.save/orders.find methods and shared data shape.
    stored = {}

    def save(order):
        stored[order["id"]] = dict(order)
        return dict(order)

    def find(query):
        return dict(stored[query["id"]])

    server.register_function(save, "orders.save")
    server.register_function(find, "orders.find")
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    emit({"ready": True, "role": "provider", "port": server.server_address[1]})
    try:
        for line in sys.stdin:
            command = json.loads(line)
            if command["operation"] == "quit":
                return
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("role", choices=("consumer", "provider"))
    parser.add_argument("--exclude-port", type=int, default=0)
    arguments = parser.parse_args()
    socket.setdefaulttimeout(10)
    if arguments.role == "consumer":
        consume()
    else:
        provide(arguments.exclude_port)


if __name__ == "__main__":
    main()

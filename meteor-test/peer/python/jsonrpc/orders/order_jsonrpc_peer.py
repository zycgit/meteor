# Copyright 2015-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.

"""Independent JSON-RPC 2.0 Order peer using Python's HTTP and JSON standard libraries.

This is a wire implementation, not an official JSON-RPC SDK. Business data and
semantics match the test-owned OrderDemoService; Java never relays its RPC traffic.
"""

import argparse
import base64
import json
import sys
import threading
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class OrderStore:
    def __init__(self):
        self.orders = {}
        self.lock = threading.Lock()

    def dispatch(self, method, parameters):
        # Decode only the two operations of the shared OrderDemoService contract.
        if method == "orders.save":
            order = parameters[0]
            if type(order["id"]) is not int or not -(2 ** 31) <= order["id"] < 2 ** 31:
                raise ValueError("Order id is outside int32")
            base64.b64decode(order["attachment"], validate=True)
            with self.lock:
                self.orders[order["id"]] = dict(order)
            return dict(order)
        if method == "orders.find":
            identifier = parameters[0]["id"]
            with self.lock:
                if identifier not in self.orders:
                    raise ValueError("Order not found: " + str(identifier))
                return dict(self.orders[identifier])
        raise LookupError("Method not found")


class OrderHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        # Each HTTP exchange carries one JSON-RPC request and its original ID.
        if self.path != "/jsonrpc":
            self.send_error(404)
            return
        length = int(self.headers["Content-Length"])
        call = json.loads(self.rfile.read(length))
        reply = {"jsonrpc": "2.0", "id": call.get("id")}
        try:
            if call.get("jsonrpc") != "2.0":
                raise ValueError("Invalid JSON-RPC version")
            reply["result"] = self.server.orders.dispatch(call["method"], call["params"])
        except LookupError as failure:
            reply["error"] = {"code": -32601, "message": str(failure)}
        except ValueError as failure:
            reply["error"] = {"code": -32001, "message": str(failure)}

        body = json.dumps(reply, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, message, *arguments):
        print(message % arguments, file=sys.stderr, flush=True)


def call(endpoint, method, argument, identifier):
    # urllib itself owns and sends the business HTTP request from this process.
    payload = {"jsonrpc": "2.0", "id": identifier, "method": method, "params": [argument]}
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(endpoint, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=10) as response:
        result = json.loads(response.read())
    assert result["jsonrpc"] == "2.0", result
    assert result["id"] == identifier, result
    return result


def consume(endpoint, scenario, vectors):
    if scenario == "missing-order":
        # Meteor maps an undeclared business exception to Internal error.
        failure = call(endpoint, "orders.find", {"id": 404}, "missing-order")
        assert failure["error"]["code"] == -32603, failure
        assert failure["error"]["message"] == "Internal error", failure
        print("OK", flush=True)
        return

    # Shared vectors store unsigned octets; JSON DTO bytes use standard Base64.
    vector = json.loads(Path(vectors).read_text(encoding="utf-8"))[scenario]
    order = {"id": vector["id"], "description": vector["description"]}
    attachment = bytes(vector["attachment"])
    order["attachment"] = base64.b64encode(attachment).decode("ascii")
    saved = call(endpoint, "orders.save", order, "save-" + scenario)
    assert "error" not in saved, saved
    assert saved["result"] == order, saved

    if scenario == "order":
        found = call(endpoint, "orders.find", {"id": order["id"]}, "find-" + scenario)
        assert found["result"] == order, found
        actual = base64.b64decode(found["result"]["attachment"], validate=True)
    else:
        actual = base64.b64decode(saved["result"]["attachment"], validate=True)
    assert actual == attachment
    print("OK", flush=True)


def provide():
    # Port zero is resolved by the foreign HTTP listener; stdin EOF ends ownership.
    server = ThreadingHTTPServer(("0.0.0.0", 0), OrderHandler)
    server.daemon_threads = True
    server.orders = OrderStore()
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    print("READY " + str(server.server_port), flush=True)
    try:
        sys.stdin.read()
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("role", choices=("provider", "consumer"))
    parser.add_argument("--endpoint")
    parser.add_argument("--scenario")
    parser.add_argument("--vectors")
    arguments = parser.parse_args()
    if arguments.role == "provider":
        provide()
    else:
        consume(arguments.endpoint, arguments.scenario, arguments.vectors)


if __name__ == "__main__":
    main()

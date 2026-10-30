#!/usr/bin/env python3
# Copyright 2015-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
# See the LICENSE.txt file for the full license.
# https://www.apache.org/licenses/LICENSE-2.0
"""Native Python XML-RPC type vectors; JSON is only the shared input fixture."""

import base64
import copy
import json
import math
from pathlib import Path
import socket
import struct
import sys
import threading
import xmlrpc.client
import xmlrpc.server


class RequestHandler(xmlrpc.server.SimpleXMLRPCRequestHandler):
    rpc_paths = ("/rpc",)

    def setup(self):
        super().setup()
        self.connection.settimeout(10)


def load_value(vector):
    # Restore every business field before RPC; XML-RPC owns all wire encoding.
    resource = Path(__file__).resolve().parents[3] / "resource/data/types/values.json"
    table = json.loads(resource.read_text(encoding="utf-8"))
    entry = table["vectors"][vector]
    value = copy.deepcopy(table["defaults"])
    value[entry["field"]] = copy.deepcopy(entry["value"])
    value["caseId"] = vector
    value["receipt"] = "client:" + vector
    value["int64Value"] = int(value["int64Value"])
    value["unsigned64Value"] = int(value["unsigned64Value"])
    value["float32Value"] = struct.unpack("!f", struct.pack("!f", float(value["float32Value"])))[0]
    value["float64Value"] = float(value["float64Value"])
    value["binaryValue"] = base64.b64decode(value["binaryValue"])
    value["mapValue"] = {key: int(number) for key, number in value["mapValue"].items()}
    return entry, value


def assert_value(vector, actual, receipt_prefix):
    # Check only this vector's business type; receipts prove real provider invocation.
    entry, expected = load_value(vector)
    assert type(actual) is dict, type(actual)
    assert actual["caseId"] == vector, actual
    assert actual["receipt"] == receipt_prefix + vector, actual
    field = entry["field"]
    result = actual[field]
    value = expected[field]
    kind = entry["type"]
    if kind in ("int8", "int16", "int32", "int64", "uint8", "uint16", "uint32", "uint64"):
        assert type(result) is int, type(result)
        assert -(2**31) <= result < 2**31, result
    elif kind == "boolean":
        assert type(result) is bool, type(result)
    elif kind in ("float32", "float64"):
        assert type(result) is float, type(result)
        assert math.isfinite(result), result
        if value == 0.0:
            assert math.copysign(1.0, result) == math.copysign(1.0, value), result
    elif kind in ("text", "enum"):
        assert type(result) is str, type(result)
    elif kind == "binary":
        assert type(result) is bytes, type(result)
    elif kind == "list":
        assert type(result) is list, type(result)
        assert all(type(element) is str for element in result), result
    elif kind == "map":
        assert type(result) is dict, type(result)
        assert all(type(key) is str and type(number) is int for key, number in result.items()), result
    elif kind == "record":
        assert type(result) is dict, type(result)
        assert type(result["index"]) is int, result
        assert type(result["name"]) is str, result
    elif kind == "optional":
        assert result is None or type(result) is str, type(result)
    else:
        raise AssertionError("Unknown vector type: " + kind)
    assert result == value, (vector, value, result)


def run_provider(vector):
    # The provider receives a real XML-RPC struct and applies typed business assertions.
    with xmlrpc.server.SimpleXMLRPCServer(
        ("0.0.0.0", 0),
        requestHandler=RequestHandler,
        allow_none=True,
        use_builtin_types=True,
        logRequests=False,
    ) as server:
        invocations = []

        def echo(request):
            invocations.append(request["caseId"])
            assert_value(vector, request, "client:")
            response = copy.deepcopy(request)
            response["receipt"] = "accepted:" + vector
            return response

        server.register_function(echo, "types.echo")

        # Control queries observe invocation without executing any business request.
        def await_close():
            for command in sys.stdin:
                if command.strip() == "CHECK":
                    print("NOT_INVOKED" if not invocations else "INVOKED", flush=True)
            server.shutdown()

        threading.Thread(target=await_close, daemon=True).start()
        print("READY " + str(server.server_address[1]), flush=True)
        server.serve_forever(poll_interval=0.1)


def run_consumer(vector, target):
    # Construct native types first, then let the standard library send and decode XML.
    _, request = load_value(vector)
    socket.setdefaulttimeout(10)
    with xmlrpc.client.ServerProxy(target, allow_none=True, use_builtin_types=True) as remote:
        response = remote.types.echo(request)
    assert_value(vector, response, "accepted:")
    print("OK", flush=True)


def run_rejected_consumer(vector, target):
    # Real stdlib serialization must reject the integer before sending HTTP bytes.
    _, request = load_value(vector)
    socket.setdefaulttimeout(10)
    with xmlrpc.client.ServerProxy(target, allow_none=True, use_builtin_types=True) as remote:
        try:
            remote.types.echo(request)
        except OverflowError as failure:
            assert str(failure) == "int exceeds XML-RPC limits", str(failure)
        else:
            raise AssertionError("Expected Python XML-RPC signed int32 range rejection")
    print("REJECTED:int32", flush=True)


if __name__ == "__main__":
    if sys.argv[1] == "provider":
        run_provider(sys.argv[2])
    elif sys.argv[1] == "consumer":
        run_consumer(sys.argv[2], sys.argv[3])
    elif sys.argv[1] == "rejected-consumer":
        run_rejected_consumer(sys.argv[2], sys.argv[3])
    else:
        raise ValueError("Unknown peer role: " + sys.argv[1])

"""Independent Python standard-library JSON-RPC type provider and consumer."""
import base64
import copy
import json
import math
import struct
import sys
import threading
import urllib.request
from enum import Enum
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class TypeKind(Enum):
    FIRST = 0
    SECOND = 1


def load_value(file, name):
    # Shared decimal texts are restored to Python int/float before the HTTP call.
    table = json.loads(Path(file).read_text(encoding="utf-8"))
    vector = table["vectors"][name]
    value = copy.deepcopy(table["defaults"])
    value[vector["field"]] = vector["value"]
    value["caseId"] = name
    value["receipt"] = "client:" + name
    value["int64Value"] = int(value["int64Value"])
    value["unsigned64Value"] = int(value["unsigned64Value"])
    value["float32Value"] = single(float(value["float32Value"]))
    value["float64Value"] = float(value["float64Value"])
    value["mapValue"] = {key: int(number) for key, number in value["mapValue"].items()}
    value["binaryValue"] = base64.b64decode(value["binaryValue"], validate=True)
    value["enumValue"] = TypeKind[value["enumValue"]]
    return value, vector


def single(number):
    return struct.unpack("!f", struct.pack("!f", number))[0]


def json_value(value):
    # Bytes and enum are real Python values; JSON-specific mapping happens at the wire boundary.
    if isinstance(value, bytes):
        return base64.b64encode(value).decode("ascii")
    if isinstance(value, TypeKind):
        return value.name
    raise TypeError("Unsupported JSON business value: " + type(value).__name__)


def decode_value(value):
    result = copy.deepcopy(value)
    result["binaryValue"] = base64.b64decode(result["binaryValue"], validate=True)
    result["enumValue"] = TypeKind[result["enumValue"]]
    return result


def assert_vector(file, name, actual, receipt):
    # Per invocation only the selected business field and server receipt are checked.
    expected, vector = load_value(file, name)
    assert actual["caseId"] == name, actual
    assert actual["receipt"] == receipt + name, actual
    field = vector["field"]
    observed = actual[field]
    wanted = expected[field]
    kind = vector["type"]

    # Python bool is an int subclass, so exact types protect the numeric assertions.
    if kind == "boolean":
        assert type(observed) is bool and observed == wanted, actual
    elif kind in ("int8", "int16", "int32", "int64", "uint8", "uint16", "uint32", "uint64"):
        assert type(observed) is int and observed == wanted, actual
    elif kind in ("float32", "float64"):
        assert type(observed) in (int, float), actual
        if wanted == 0.0 and math.copysign(1.0, wanted) < 0.0:
            # JSON-RPC's existing exact-decimal binding normalizes signed zero.
            assert observed == 0.0 and math.copysign(1.0, observed) > 0.0, actual
        elif kind == "float32":
            assert struct.pack("!f", observed) == struct.pack("!f", wanted), actual
        else:
            assert struct.pack("!d", observed) == struct.pack("!d", wanted), actual
    elif kind == "binary":
        assert type(observed) is bytes and observed == wanted, actual
    elif kind == "enum":
        assert type(observed) is TypeKind, actual
        assert observed == wanted, actual
    elif kind == "map":
        assert type(observed) is dict and observed == wanted, actual
        assert all(type(number) is int for number in observed.values()), actual
    else:
        assert type(observed) is type(wanted) and observed == wanted, actual


class TypeHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        # This independent Python HTTP endpoint owns JSON decoding and the echo service.
        length = int(self.headers["Content-Length"])
        request = json.loads(self.rfile.read(length))
        assert request["jsonrpc"] == "2.0" and request["method"] == "types.echo", request
        value = decode_value(request["params"][0])
        name = value["caseId"]
        expected, vector = load_value(self.server.vector_file, name)
        negative_zero = vector["type"] in ("float32", "float64") and name.endswith("negative-zero")
        if not negative_zero:
            assert_vector(self.server.vector_file, name, value, "client:")
        else:
            # The caller's JSON-RPC exact-decimal binding may already normalize -0.
            assert value[vector["field"]] == 0.0, value

        # Generate a receipt in the business implementation rather than echoing the input.
        result = copy.deepcopy(value)
        result["receipt"] = "accepted:" + name
        response = {"jsonrpc": "2.0", "id": request["id"], "result": result}
        body = json.dumps(response, ensure_ascii=False, allow_nan=False, default=json_value).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, message, *arguments):
        print(message % arguments, file=sys.stderr, flush=True)


def consume(file, endpoint, name):
    # Standard urllib/json execute the full business exchange from the Python container.
    expected, vector = load_value(file, name)
    payload = {"jsonrpc": "2.0", "id": name, "method": "types.echo", "params": [expected]}
    body = json.dumps(payload, ensure_ascii=False, allow_nan=False, default=json_value).encode("utf-8")
    request = urllib.request.Request(endpoint, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=10) as response:
        result = json.loads(response.read())

    # Precision and real Python types are verified before reporting this single case complete.
    assert result["jsonrpc"] == "2.0" and result["id"] == name, result
    assert "error" not in result, result
    assert_vector(file, name, decode_value(result["result"]), "accepted:")
    negative_zero = vector["type"] in ("float32", "float64") and name.endswith("negative-zero")
    print("LIMIT negative-zero" if negative_zero else "OK", flush=True)


def provide(file):
    # The owning Java fixture closes stdin; this peer then releases its listener and worker.
    server = ThreadingHTTPServer(("0.0.0.0", 0), TypeHandler)
    server.daemon_threads = True
    server.vector_file = file
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    print("READY " + str(server.server_port), flush=True)
    try:
        sys.stdin.read()
    finally:
        server.shutdown()
        server.server_close()
        worker.join(timeout=5)


if __name__ == "__main__":
    role, vector_file, *arguments = sys.argv[1:]
    if role == "provider":
        provide(vector_file)
    elif role == "consumer":
        consume(vector_file, arguments[0], arguments[1])
    else:
        raise ValueError("Expected provider or consumer role")

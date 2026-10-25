#!/usr/bin/env python3
# Copyright 2015-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0. See LICENSE.txt.
"""Official Python gRPC typed request/response interoperability peer."""

import argparse
from concurrent import futures
import json
from pathlib import Path
import sys

import grpc

from type_values import assert_value, vector


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("role", choices=("provider", "consumer"))
    parser.add_argument("--generated", type=Path, required=True)
    parser.add_argument("--vectors", type=Path, required=True)
    parser.add_argument("--vector", required=True)
    parser.add_argument("--encoding", default="protobuf", choices=("protobuf",))
    parser.add_argument("--host")
    parser.add_argument("--port", type=int)
    return parser.parse_args()


def native(value):
    # Protobuf scalar access yields Python numbers; optional presence is checked independently of empty text.
    return {
        "caseId": value.caseId,
        "booleanValue": value.booleanValue,
        "int8Value": value.int8Value,
        "int16Value": value.int16Value,
        "int32Value": value.int32Value,
        "int64Value": value.int64Value,
        "float32Value": value.float32Value,
        "float64Value": value.float64Value,
        "textValue": value.textValue,
        "binaryValue": value.binaryValue,
        "enumValue": value.enumValue,
        "listValue": list(value.listValue),
        "mapValue": dict(value.mapValue),
        "recordValue": {"index": value.recordValue.index, "name": value.recordValue.name},
        "optionalValue": value.optionalValue if value.HasField("optionalValue") else None,
        "receipt": value.receipt,
        "unsigned64Value": value.unsigned64Value,
    }


def message(messages, value):
    # Pass a real generated message; no serialized business object is tunneled through a string field.
    fields = dict(value)
    if fields["optionalValue"] is None:
        del fields["optionalValue"]
    return messages.TypeValue(**fields)


def provider(options, messages, services, expected, type_name):
    class TypeService(services.TypeEchoServiceServicer):
        def echo(self, request, context):
            assert_value(expected, native(request), "client:" + options.vector, type_name)
            response = messages.TypeValue()
            response.CopyFrom(request)
            response.receipt = "accepted:" + request.caseId
            return response

    # The official SDK owns HTTP/2, Protobuf field decoding and per-call dispatch.
    with futures.ThreadPoolExecutor(max_workers=2) as executor:
        server = grpc.server(executor)
        services.add_TypeEchoServiceServicer_to_server(TypeService(), server)
        port = server.add_insecure_port("0.0.0.0:0")
        if port == 0:
            raise RuntimeError("Python gRPC type provider did not bind")
        server.start()
        print("READY " + str(port), flush=True)
        try:
            for _ in sys.stdin:
                pass
        finally:
            server.stop(grace=0).wait(timeout=5)


def consumer(options, messages, services, expected, type_name):
    # Python int retains signed and unsigned 64-bit values without a float or decimal-string RPC conversion.
    target = options.host + ":" + str(options.port)
    with grpc.insecure_channel(target) as channel:
        grpc.channel_ready_future(channel).result(timeout=10)
        result = services.TypeEchoServiceStub(channel).echo(message(messages, expected), timeout=10)
        assert_value(expected, native(result), "accepted:" + options.vector, type_name)
    print("RESULT " + json.dumps({"ok": True, "vector": options.vector, "type": type_name}), flush=True)


def main():
    options = arguments()
    sys.path.insert(0, str(options.generated))
    import types_pb2
    import types_pb2_grpc

    type_name, expected = vector(options.vectors, options.vector)
    if options.role == "provider":
        provider(options, types_pb2, types_pb2_grpc, expected, type_name)
    else:
        consumer(options, types_pb2, types_pb2_grpc, expected, type_name)


if __name__ == "__main__":
    main()

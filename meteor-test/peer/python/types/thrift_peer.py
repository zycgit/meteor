#!/usr/bin/env python3
# Copyright 2015-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0. See LICENSE.txt.
"""Official Python Thrift typed request/response interoperability peer."""

import argparse
import json
import os
from pathlib import Path
import signal
import socket
import sys
import threading

from thrift.protocol import TBinaryProtocol, TCompactProtocol, TJSONProtocol
from thrift.transport import TSocket, TTransport

from type_values import assert_value, vector


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("role", choices=("provider", "consumer"))
    parser.add_argument("--generated", type=Path, required=True)
    parser.add_argument("--vectors", type=Path, required=True)
    parser.add_argument("--vector", required=True)
    parser.add_argument("--encoding", choices=("binary", "compact", "json"), required=True)
    parser.add_argument("--host")
    parser.add_argument("--port", type=int)
    return parser.parse_args()


def protocol(transport, encoding):
    if encoding == "binary":
        return TBinaryProtocol.TBinaryProtocol(transport)
    if encoding == "compact":
        return TCompactProtocol.TCompactProtocol(transport)
    return TJSONProtocol.TJSONProtocol(transport)


def native(value):
    # Thrift has no unsigned i64; its one explicit decimal-string field is restored to Python int.
    assert type(value.unsigned64Value) is str, type(value.unsigned64Value)
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
        "listValue": value.listValue,
        "mapValue": value.mapValue,
        "recordValue": {"index": value.recordValue.index, "name": value.recordValue.name},
        "optionalValue": value.optionalValue,
        "receipt": value.receipt,
        "unsigned64Value": int(value.unsigned64Value),
    }


def message(messages, value):
    # Only u64 uses the documented string mapping; signed integers remain native SDK i64 values.
    fields = dict(value)
    fields["unsigned64Value"] = str(fields["unsigned64Value"])
    fields["recordValue"] = messages.TypeRecord(**fields["recordValue"])
    return messages.TypeValue(**fields)


def provider(options, messages, services, expected, type_name):
    class TypeService(services.Iface):
        def echo(self, request):
            request_value = native(request)
            assert_value(expected, request_value, "client:" + options.vector, type_name)
            response = message(messages, request_value)
            response.receipt = "accepted:" + request.caseId
            return response

    server = TSocket.TServerSocket("0.0.0.0", 0)
    stopped = threading.Event()
    connections = set()
    lock = threading.Lock()
    workers = []

    # Parent EOF and termination stop this listener; worker sockets belong to this provider only.
    def stop_from_signal(_signal, _frame):
        stopped.set()

    def stop_from_stdin():
        while os.read(sys.stdin.fileno(), 1):
            pass
        stopped.set()

    def process(connection):
        transport = TTransport.TFramedTransport(connection)
        processor = services.Processor(TypeService())
        input_protocol = protocol(transport, options.encoding)
        output_protocol = protocol(transport, options.encoding)
        try:
            while not stopped.is_set():
                processor.process(input_protocol, output_protocol)
        except TTransport.TTransportException as failure:
            if failure.type != TTransport.TTransportException.END_OF_FILE and not stopped.is_set():
                raise
        finally:
            transport.close()
            with lock:
                connections.discard(connection)

    signal.signal(signal.SIGTERM, stop_from_signal)
    signal.signal(signal.SIGINT, stop_from_signal)
    threading.Thread(target=stop_from_stdin, daemon=True).start()
    server.listen()
    server.handle.settimeout(0.25)
    print("READY " + str(server.handle.getsockname()[1]), flush=True)

    # Official generated Processor and transport own all field decoding and response framing.
    try:
        while not stopped.is_set():
            try:
                connection = server.accept()
            except socket.timeout:
                continue
            connection.setTimeout(10000)
            with lock:
                connections.add(connection)
            worker = threading.Thread(target=process, args=(connection,), daemon=True)
            workers.append(worker)
            worker.start()
    finally:
        stopped.set()
        server.close()
        with lock:
            active = list(connections)
        for connection in active:
            if connection.handle is not None:
                try:
                    connection.handle.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
        for worker in workers:
            worker.join(timeout=1)


def consumer(options, messages, services, expected, type_name):
    # Binary, Compact and JSON use the same generated structs, each in its own JUnit instance.
    connection = TSocket.TSocket(options.host, options.port)
    connection.setTimeout(10000)
    transport = TTransport.TFramedTransport(connection)
    try:
        transport.open()
        client = services.Client(protocol(transport, options.encoding))
        result = client.echo(message(messages, expected))
        assert_value(expected, native(result), "accepted:" + options.vector, type_name)
    finally:
        transport.close()
    print("RESULT " + json.dumps({"ok": True, "vector": options.vector, "type": type_name}), flush=True)


def main():
    options = arguments()
    sys.path.insert(0, str(options.generated))
    from typecontract import TypeEchoService
    from typecontract import ttypes

    type_name, expected = vector(options.vectors, options.vector)
    if options.role == "provider":
        provider(options, ttypes, TypeEchoService, expected, type_name)
    else:
        consumer(options, ttypes, TypeEchoService, expected, type_name)


if __name__ == "__main__":
    main()

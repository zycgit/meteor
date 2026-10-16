#!/usr/bin/env python3
# Copyright 2015-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
# See the LICENSE.txt file for the full license.
# https://www.apache.org/licenses/LICENSE-2.0
"""Independent XML-RPC peer using only Python's standard library."""

import datetime
import socket
import ssl
import sys
import threading
import xmlrpc.client
import xmlrpc.server


class RequestHandler(xmlrpc.server.SimpleXMLRPCRequestHandler):
    rpc_paths = ("/rpc",)

    def setup(self):
        super().setup()
        self.connection.settimeout(10)


def fail(message):
    raise xmlrpc.client.Fault(42, message)


def start_server(tls_files):
    # Register the same public method names without implementing a second codec.
    with xmlrpc.server.SimpleXMLRPCServer(
        ("127.0.0.1", 0),
        requestHandler=RequestHandler,
        allow_none=True,
        use_builtin_types=True,
        logRequests=False,
    ) as server:
        if tls_files:
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.set_alpn_protocols(["http/1.1"])
            context.load_cert_chain(tls_files[0], tls_files[1])
            server.socket = context.wrap_socket(server.socket, server_side=True)
        server.register_function(lambda value: value, "demo.echo")
        server.register_function(lambda left, right: left - right, "demo.subtract")
        server.register_function(lambda value: not value, "demo.negate")
        server.register_function(lambda value: value / 2.0, "demo.half")
        server.register_function(lambda value: value, "demo.binary")
        server.register_function(lambda value: value, "demo.date")
        server.register_function(lambda value: value, "demo.people")
        server.register_function(lambda value: value, "demo.struct")
        server.register_function(fail, "demo.fail")
        server.register_function(lambda: "ready", "demo.current")

        # Java owns this process; closing stdin requests an orderly server stop.
        def await_close():
            sys.stdin.buffer.read()
            server.shutdown()

        threading.Thread(target=await_close, daemon=True).start()
        print("READY " + str(server.server_address[1]), flush=True)
        server.serve_forever(poll_interval=0.1)


def run_client(target, scene, trust_file):
    # Real xmlrpc.client performs XML encoding, HTTP I/O and response decoding.
    socket.setdefaulttimeout(10)
    context = None
    if trust_file:
        context = ssl.create_default_context(cafile=trust_file)
        context.set_alpn_protocols(["http/1.1"])
    with xmlrpc.client.ServerProxy(target, allow_none=True, use_builtin_types=True, context=context) as remote:
        if scene == "string":
            value = '中文🙂 <tag a="quoted"> & escaped'
            assert remote.demo.echo(value) == value
        elif scene == "integer":
            assert remote.demo.subtract(21, 5) == 16
        elif scene == "boolean":
            assert remote.demo.negate(True) is False
        elif scene == "double":
            assert remote.demo.half(12.5) == 6.25
        elif scene == "base64":
            value = b"\xff\x00A"
            assert remote.demo.binary(value) == value
        elif scene == "datetime":
            value = datetime.datetime(2026, 10, 16, 12, 34, 56)
            assert remote.demo.date(value) == value
        elif scene == "array":
            value = [{"name": "甲", "age": 31}, {"name": "乙", "age": 28}]
            assert remote.demo.people(value) == value
        elif scene == "struct":
            value = {"count": 7, "enabled": True, "nested": {"name": "<标签>&"}}
            assert remote.demo.struct(value) == value
        elif scene == "fault":
            try:
                remote.demo.fail("独立调用失败 <&>")
            except xmlrpc.client.Fault as failure:
                assert failure.faultCode == 42
                assert failure.faultString == "独立调用失败 <&>"
            else:
                raise AssertionError("Expected an XML-RPC fault")
        elif scene == "noargs":
            assert remote.demo.current() == "ready"
        elif scene == "nil":
            assert remote.demo.echo(None) is None
        else:
            raise ValueError("Unknown client scene: " + scene)
    print("OK", flush=True)


if __name__ == "__main__":
    if sys.argv[1] == "server":
        start_server(sys.argv[2:])
    elif sys.argv[1] == "client":
        trust_file = sys.argv[4] if len(sys.argv) > 4 else None
        run_client(sys.argv[2], sys.argv[3], trust_file)
    else:
        raise ValueError("Unknown peer mode: " + sys.argv[1])

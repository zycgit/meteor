#!/usr/bin/env python3
# Copyright 2015-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0.
# See the LICENSE.txt file for the full license.
# https://www.apache.org/licenses/LICENSE-2.0
"""Official Python Thrift provider and consumer for the shared order IDL."""

import argparse
import logging
import os
from pathlib import Path
import signal
import socket
import ssl
import sys
import threading

from thrift.Thrift import TApplicationException
from thrift.protocol import TBinaryProtocol, TCompactProtocol, TJSONProtocol
from thrift.transport import TSocket, TSSLSocket, TTransport

sys.path.insert(0, str(Path(__file__).with_name("generated")))
from order import OrderThriftService
from order.ttypes import OrderQuery, ThriftOrder


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("provider", "consumer"), required=True)
    parser.add_argument("--protocol", choices=("binary", "compact", "json"), default="binary")
    parser.add_argument("--framed", choices=("true", "false"), default="true")
    parser.add_argument("--scenario", choices=("basic", "unicode", "bytes", "failure"), default="basic")
    parser.add_argument("--host", required=True)
    parser.add_argument("--port", type=int, default=0)
    parser.add_argument("--security", choices=("plain", "tls", "mutual"), default="plain")
    parser.add_argument("--trust", type=Path)
    parser.add_argument("--certificate", type=Path)
    parser.add_argument("--key", type=Path)
    return parser.parse_args()


def protocol(transport, name):
    if name == "binary":
        return TBinaryProtocol.TBinaryProtocol(transport)
    if name == "compact":
        return TCompactProtocol.TCompactProtocol(transport)
    return TJSONProtocol.TJSONProtocol(transport)


def buffered_transport(connection, framed):
    # The official transport owns framing; no hand-written length prefixes.
    if framed == "true":
        return TTransport.TFramedTransport(connection)
    return TTransport.TBufferedTransport(connection)


def client_connection(options):
    if options.security == "plain":
        connection = TSocket.TSocket(options.host, options.port)
    else:
        # Standard TLS verifies both the certificate chain and the requested host.
        context = ssl.create_default_context(cafile=str(options.trust))
        if options.security == "mutual":
            context.load_cert_chain(str(options.certificate), str(options.key))
        connection = TSSLSocket.TSSLSocket(
            options.host, options.port, ssl_context=context, server_hostname=options.host,
        )
    connection.setTimeout(10000)
    return connection


def listener(options):
    if options.security == "plain":
        return TSocket.TServerSocket(options.host, options.port)

    # The TLS layer verifies client identity through the trust chain, not source-IP hostname matching.
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(str(options.certificate), str(options.key))
    if options.security == "mutual":
        context.load_verify_locations(cafile=str(options.trust))
        context.verify_mode = ssl.CERT_REQUIRED
    return TSSLSocket.TSSLServerSocket(
        options.host, options.port, ssl_context=context,
        validate_callback=lambda _certificate, _address: None,
    )


class OrderService(OrderThriftService.Iface):
    def __init__(self):
        self.orders = {}
        self.lock = threading.Lock()

    @staticmethod
    def copy(order):
        attachment = None
        if order.attachment is not None:
            attachment = bytes(bytearray(order.attachment))
        return ThriftOrder(id=order.id, description=order.description, attachment=attachment)

    def save(self, order):
        stored = self.copy(order)
        with self.lock:
            self.orders[stored.id] = stored
        return self.copy(stored)

    def find(self, query):
        with self.lock:
            stored = self.orders.get(query.id)
        if stored is None:
            # The generated Processor converts this business failure into INTERNAL_ERROR.
            raise ValueError("Order not found: " + str(query.id))
        return self.copy(stored)


def process_connection(connection, options, service, stopped, connections, connections_lock):
    connection.setTimeout(10000)
    transport = buffered_transport(connection, options.framed)
    processor = OrderThriftService.Processor(service)
    input_protocol = protocol(transport, options.protocol)
    output_protocol = protocol(transport, options.protocol)

    # Each accepted connection has its own official transport, protocol state and Processor.
    try:
        while not stopped.is_set():
            processor.process(input_protocol, output_protocol)
    except TTransport.TTransportException as failure:
        expected = (TTransport.TTransportException.END_OF_FILE, TTransport.TTransportException.TIMED_OUT)
        if not stopped.is_set() and failure.type not in expected:
            logging.exception("Thrift connection failed")
    finally:
        transport.close()
        with connections_lock:
            connections.discard(connection)


def provider(options):
    server = listener(options)
    service = OrderService()
    stopped = threading.Event()
    connections = set()
    connections_lock = threading.Lock()
    workers = []

    # Parent stdin ownership and OS termination both stop this provider's listener.
    def stop_from_signal(_signal, _frame):
        stopped.set()

    def stop_from_stdin():
        # A raw descriptor read avoids holding Python's buffered-stdin lock during SIGTERM shutdown.
        while os.read(sys.stdin.fileno(), 1):
            pass
        stopped.set()

    signal.signal(signal.SIGTERM, stop_from_signal)
    signal.signal(signal.SIGINT, stop_from_signal)
    threading.Thread(target=stop_from_stdin, daemon=True).start()
    server.listen()
    server.handle.settimeout(0.25)
    print("READY:" + str(server.handle.getsockname()[1]), flush=True)

    # Accept remains responsive to shutdown; workers own their per-connection protocol state.
    try:
        while not stopped.is_set():
            try:
                connection = server.accept()
            except socket.timeout:
                continue
            if connection is None:
                continue
            with connections_lock:
                connections.add(connection)
            worker = threading.Thread(
                target=process_connection,
                args=(connection, options, service, stopped, connections, connections_lock),
                daemon=True,
            )
            workers.append(worker)
            worker.start()
    finally:
        stopped.set()
        server.close()
        with connections_lock:
            active = list(connections)
        for connection in active:
            handle = connection.handle
            if handle is None:
                continue
            try:
                handle.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        for worker in workers:
            worker.join(timeout=1)


def expected_order(scenario):
    if scenario == "unicode":
        return ThriftOrder(id=102, description="Python 订单 🙂 <&>", attachment=b"Meteor")
    if scenario == "bytes":
        return ThriftOrder(id=103, description="Binary order", attachment=bytes((0, 1, 127, 128, 255)))
    return ThriftOrder(id=101, description="Order from Python", attachment=b"Meteor")


def consumer(options):
    connection = client_connection(options)
    transport = buffered_transport(connection, options.framed)
    client = OrderThriftService.Client(protocol(transport, options.protocol))
    transport.open()
    try:
        if options.scenario == "failure":
            # Require an application exception; transport failures and missing responses must fail.
            try:
                client.find(OrderQuery(id=404))
            except TApplicationException as failure:
                assert failure.type == TApplicationException.INTERNAL_ERROR, failure
                assert str(failure), "The application exception has no message"
            else:
                raise AssertionError("A missing order unexpectedly succeeded")
        else:
            # Save and find are one round-trip scenario over the same business state.
            expected = expected_order(options.scenario)
            assert client.save(expected) == expected
            assert client.find(OrderQuery(id=expected.id)) == expected
    finally:
        transport.close()
    print("RESULT:OK", flush=True)


if __name__ == "__main__":
    options = arguments()
    if options.mode == "provider":
        provider(options)
    else:
        consumer(options)

#!/usr/bin/env python3
# Copyright 2015-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0. See LICENSE.txt.
"""使用 Apache Thrift 发布订单服务。"""

from pathlib import Path
import os
import sys

from thrift.protocol import TBinaryProtocol
from thrift.server import TServer
from thrift.transport import TSocket, TTransport

sys.path.insert(0, str(Path(__file__).with_name("generated")))
from order import OrderThriftService


class OrderService(OrderThriftService.Iface):
    def __init__(self):
        self.orders = {}

    def save(self, order):
        self.orders[order.id] = order
        return order

    def find(self, query):
        return self.orders[query.id]


class OrderListener(TSocket.TServerSocket):
    def listen(self):
        super().listen()
        bind_host = os.environ.get("METEOR_ORDER_BIND_HOST", "127.0.0.1")
        publish_host = os.environ.get("METEOR_ORDER_PUBLISH_HOST", os.environ.get("METEOR_TEST_PEER_HOST", bind_host))
        port = self.handle.getsockname()[1]
        print(f"Order provider is ready: thrift://{publish_host}:{port}", flush=True)


def main():
    # 使用 binary 编码和 framed 传输发布服务。
    bind_host = os.environ.get("METEOR_ORDER_BIND_HOST", "127.0.0.1")
    bind_port = int(os.environ.get("METEOR_ORDER_PORT", "8080"))
    listener = OrderListener(bind_host, bind_port)
    server = TServer.TThreadedServer(
        OrderThriftService.Processor(OrderService()),
        listener,
        TTransport.TFramedTransportFactory(),
        TBinaryProtocol.TBinaryProtocolFactory(),
        daemon=True,
    )
    try:
        server.serve()
    except KeyboardInterrupt:
        pass
    finally:
        listener.close()


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
# Copyright 2015-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0. See LICENSE.txt.
"""使用 Python 标准库发布 XML-RPC 订单服务。"""

import os
from pathlib import Path
import sys
from xmlrpc.server import SimpleXMLRPCRequestHandler, SimpleXMLRPCServer

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import nxn_control as nxn


class OrderService:
    def __init__(self):
        self.orders = {}

    def save(self, order):
        self.orders[order["id"]] = order
        return order

    def find(self, query):
        return self.orders[query["id"]]


class OrderRequestHandler(SimpleXMLRPCRequestHandler):
    rpc_paths = ("/xmlrpc",)


def main():
    bind_host = os.environ.get("METEOR_ORDER_BIND_HOST", "127.0.0.1")
    bind_port = int(os.environ.get("METEOR_ORDER_PORT", "8080"))
    publish_host = os.environ.get("METEOR_ORDER_PUBLISH_HOST", os.environ.get("METEOR_TEST_PEER_HOST", bind_host))
    # 标准库负责 struct 与 Base64 的编解码。
    with SimpleXMLRPCServer(
        (bind_host, bind_port),
        requestHandler=OrderRequestHandler,
        use_builtin_types=True,
    ) as server:
        service = OrderService()
        server.register_function(service.save, "orders.save")
        server.register_function(service.find, "orders.find")
        target = f"http://{publish_host}:{server.server_address[1]}/xmlrpc"
        if nxn.enabled():
            nxn.ready(target)
        else:
            print(f"Order provider is ready: {target}", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()

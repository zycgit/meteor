#!/usr/bin/env python3
# Copyright 2015-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0. See LICENSE.txt.
"""使用 grpcio 发布订单服务。"""

from concurrent.futures import ThreadPoolExecutor
import os
import sys

import grpc

sys.path.insert(0, "/opt/meteor-peer/python-grpc-generated")
import order_pb2
import order_pb2_grpc


class OrderService(order_pb2_grpc.OrderDemoServiceServicer):
    def __init__(self):
        self.orders = {}

    def save(self, order, context):
        self.orders[order.id] = order
        return order

    def find(self, query, context):
        return self.orders[query.id]


def main():
    # 在固定 context 下发布两个订单方法。
    service = OrderService()
    methods = {
        "save": grpc.unary_unary_rpc_method_handler(
            service.save,
            request_deserializer=order_pb2.Order.FromString,
            response_serializer=order_pb2.Order.SerializeToString,
        ),
        "find": grpc.unary_unary_rpc_method_handler(
            service.find,
            request_deserializer=order_pb2.OrderQuery.FromString,
            response_serializer=order_pb2.Order.SerializeToString,
        ),
    }
    with ThreadPoolExecutor(max_workers=2) as executor:
        server = grpc.server(executor)
        handler = grpc.method_handlers_generic_handler("grpc/meteor.example.OrderDemoService", methods)
        server.add_generic_rpc_handlers((handler,))
        bind_host = os.environ.get("METEOR_ORDER_BIND_HOST", "127.0.0.1")
        bind_port = int(os.environ.get("METEOR_ORDER_PORT", "8080"))
        port = server.add_insecure_port(f"{bind_host}:{bind_port}")
        server.start()
        publish_host = os.environ.get("METEOR_ORDER_PUBLISH_HOST", os.environ.get("METEOR_TEST_PEER_HOST", bind_host))
        print(f"Order provider is ready: grpc://{publish_host}:{port}/grpc", flush=True)
        try:
            server.wait_for_termination()
        finally:
            server.stop(grace=0).wait()


if __name__ == "__main__":
    main()

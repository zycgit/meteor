#!/usr/bin/env python3
# Copyright 2015-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0. See LICENSE.txt.
"""使用 grpcio 调用 Meteor 订单服务。"""

import base64
import json
import os
from pathlib import Path
import sys
from urllib.parse import urlsplit

import grpc

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import nxn_control as nxn

sys.path.insert(0, "/opt/meteor-peer/python-grpc-generated")
import order_pb2


def main():
    # 创建固定挂载路径下的两个 RPC 方法。
    target = urlsplit(os.environ.get("METEOR_ORDER_TARGET", "grpc://127.0.0.1:8080/grpc"))
    context_path = target.path.rstrip("/")
    if not nxn.enabled() and not context_path:
        context_path = "/grpc"
    with grpc.insecure_channel(target.netloc) as channel:
        save = channel.unary_unary(
            context_path + "/meteor.example.OrderDemoService/save",
            request_serializer=order_pb2.Order.SerializeToString,
            response_deserializer=order_pb2.Order.FromString,
        )
        find = channel.unary_unary(
            context_path + "/meteor.example.OrderDemoService/find",
            request_serializer=order_pb2.OrderQuery.SerializeToString,
            response_deserializer=order_pb2.Order.FromString,
        )

        # 保存订单，再按编号查询。
        order = order_pb2.Order(id=1001, description="Order from Python", attachment=b"Meteor")
        if nxn.enabled():
            order = order_pb2.Order(**nxn.request(binary=True))
        saved = save(order, timeout=10)
        found = find(order_pb2.OrderQuery(id=order.id), timeout=10)
        result = {
            "id": found.id,
            "description": found.description,
            "attachment": base64.b64encode(found.attachment).decode("ascii"),
        }
        if nxn.enabled():
            nxn.result(result, {"id": saved.id, "description": saved.description, "attachment": saved.attachment})
        else:
            print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()

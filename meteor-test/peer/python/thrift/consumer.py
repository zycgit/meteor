#!/usr/bin/env python3
# Copyright 2015-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0. See LICENSE.txt.
"""使用 Apache Thrift 调用 Meteor 订单服务。"""

import base64
import json
import os
from pathlib import Path
import sys
from urllib.parse import urlsplit

from thrift.protocol import TBinaryProtocol
from thrift.transport import TSocket, TTransport

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import nxn_control as nxn

sys.path.insert(0, str(Path(__file__).with_name("generated")))
from order import OrderThriftService
from order.ttypes import ThriftOrderQuery, ThriftOrder


def main():
    # 使用与提供者一致的 binary 编码和 framed 传输。
    target = urlsplit(os.environ.get("METEOR_ORDER_TARGET", "thrift://127.0.0.1:8080"))
    connection = TSocket.TSocket(target.hostname, target.port)
    connection.setTimeout(10000)
    transport = TTransport.TFramedTransport(connection)
    client = OrderThriftService.Client(TBinaryProtocol.TBinaryProtocol(transport))
    transport.open()
    try:
        # 保存订单，再按编号查询。
        order = ThriftOrder(id=1001, description="Order from Python", attachment=b"Meteor")
        if nxn.enabled():
            order = ThriftOrder(**nxn.request(binary=True))
        saved = client.save(order)
        found = client.find(ThriftOrderQuery(id=order.id))
        result = {
            "id": found.id,
            "description": found.description,
            "attachment": base64.b64encode(found.attachment).decode("ascii"),
        }
        if nxn.enabled():
            nxn.result(result, {"id": saved.id, "description": saved.description, "attachment": saved.attachment})
        else:
            print(json.dumps(result, ensure_ascii=False))
    finally:
        transport.close()


if __name__ == "__main__":
    main()

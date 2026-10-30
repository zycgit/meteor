#!/usr/bin/env python3
# Copyright 2015-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0. See LICENSE.txt.
"""使用 Apache Thrift 调用 Meteor 订单服务。"""

import base64
import json
from pathlib import Path
import sys

from thrift.protocol import TBinaryProtocol
from thrift.transport import TSocket, TTransport

sys.path.insert(0, str(Path(__file__).with_name("generated")))
from order import OrderThriftService
from order.ttypes import ThriftOrderQuery, ThriftOrder


def main():
    # 使用与提供者一致的 binary 编码和 framed 传输。
    connection = TSocket.TSocket("127.0.0.1", 8080)
    transport = TTransport.TFramedTransport(connection)
    client = OrderThriftService.Client(TBinaryProtocol.TBinaryProtocol(transport))
    transport.open()
    try:
        # 保存订单，再按编号查询。
        order = ThriftOrder(id=1001, description="Order from Python", attachment=b"Meteor")
        client.save(order)
        found = client.find(ThriftOrderQuery(id=1001))
        result = {
            "id": found.id,
            "description": found.description,
            "attachment": base64.b64encode(found.attachment).decode("ascii"),
        }
        print(json.dumps(result, ensure_ascii=False))
    finally:
        transport.close()


if __name__ == "__main__":
    main()

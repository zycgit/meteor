#!/usr/bin/env python3
# Copyright 2015-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0. See LICENSE.txt.
"""使用 Python 标准库调用 Meteor XML-RPC 订单服务。"""

import base64
import json
from xmlrpc.client import ServerProxy


def main():
    # 标准库将 Python bytes 编码为 XML-RPC Base64。
    with ServerProxy("http://127.0.0.1:8080/xmlrpc", use_builtin_types=True) as client:
        order = {"id": 1001, "description": "Order from Python", "attachment": b"Meteor"}
        client.orders.save(order)
        result = client.orders.find({"id": 1001})
        result["attachment"] = base64.b64encode(result["attachment"]).decode("ascii")
        print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
# Copyright 2015-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0. See LICENSE.txt.
"""使用 Python 标准库调用 Meteor JSON-RPC 订单服务。"""

import base64
import json
import urllib.request


def invoke(method, argument, request_id):
    # JSON-RPC 按位置传递参数，附件使用 Base64 字符串。
    payload = {"jsonrpc": "2.0", "id": request_id, "method": "orders." + method, "params": [argument]}
    request = urllib.request.Request(
        "http://127.0.0.1:8080/jsonrpc",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request) as response:
        return json.load(response)["result"]


def main():
    # 保存订单，再按编号查询。
    order = {
        "id": 1001,
        "description": "Order from Python",
        "attachment": base64.b64encode(b"Meteor").decode("ascii"),
    }
    invoke("save", order, 1)
    result = invoke("find", {"id": 1001}, 2)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()

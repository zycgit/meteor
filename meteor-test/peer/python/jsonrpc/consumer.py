#!/usr/bin/env python3
# Copyright 2015-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0. See LICENSE.txt.
"""使用 Python 标准库调用 Meteor JSON-RPC 订单服务。"""

import base64
import json
import os
from pathlib import Path
import sys
import urllib.request
from urllib.parse import urlsplit, urlunsplit

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import nxn_control as nxn


def invoke(method, argument, request_id):
    # JSON-RPC 按位置传递参数，附件使用 Base64 字符串。
    payload = {"jsonrpc": "2.0", "id": request_id, "method": "orders." + method, "params": [argument]}
    target = urlsplit(os.environ.get("METEOR_ORDER_TARGET", "http://127.0.0.1:8080/jsonrpc"))
    service_path = target.path if target.path not in ("", "/") else "/jsonrpc"
    if nxn.enabled():
        service_path = target.path
    address = urlunsplit((target.scheme, target.netloc, service_path, target.query, ""))
    request = urllib.request.Request(
        address,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=10) as response:
        document = json.load(response)
        if document.get("error") is not None:
            raise RuntimeError(document["error"])
        return document["result"]


def main():
    # 保存订单，再按编号查询。
    order = {
        "id": 1001,
        "description": "Order from Python",
        "attachment": base64.b64encode(b"Meteor").decode("ascii"),
    }
    if nxn.enabled():
        order = nxn.request()
    saved = invoke("save", order, 1)
    result = invoke("find", {"id": order["id"]}, 2)
    if nxn.enabled():
        nxn.result(result, saved)
    else:
        print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()

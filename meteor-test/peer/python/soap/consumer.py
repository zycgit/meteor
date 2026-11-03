#!/usr/bin/env python3
# Copyright 2015-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0. See LICENSE.txt.
"""使用 Zeep 根据 Meteor 的 WSDL 调用订单服务。"""

import base64
import json
import os
from pathlib import Path
import sys
from urllib.parse import urlsplit, urlunsplit

import requests
import zeep
from zeep.transports import Transport

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import nxn_control as nxn


def main():
    # 从 WSDL 读取服务地址和 XML 类型。
    target = urlsplit(os.environ.get("METEOR_ORDER_TARGET", "http://127.0.0.1:8080/soap/orders"))
    service_path = target.path if target.path not in ("", "/") else "/soap/orders"
    if nxn.enabled():
        service_path = target.path
    wsdl = urlunsplit((target.scheme, target.netloc, service_path, target.query or "wsdl", ""))
    with requests.Session() as session:
        client = zeep.Client(
            wsdl,
            transport=Transport(session=session, timeout=10, operation_timeout=10),
        )

        # 保存订单，再按编号查询。
        order = {"id": 1001, "description": "Order from Python", "attachment": b"Meteor"}
        if nxn.enabled():
            order = nxn.request(binary=True)
        saved = client.service.save(arg0=order)
        found = client.service.find(arg0={"id": order["id"]})
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

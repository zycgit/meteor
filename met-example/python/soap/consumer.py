#!/usr/bin/env python3
# Copyright 2015-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0. See LICENSE.txt.
"""使用 Zeep 根据 Meteor 的 WSDL 调用订单服务。"""

import base64
import json

import requests
import zeep
from zeep.transports import Transport


def main():
    # 从 WSDL 读取服务地址和 XML 类型。
    with requests.Session() as session:
        client = zeep.Client(
            "http://127.0.0.1:8080/soap/orders?wsdl",
            transport=Transport(session=session),
        )

        # 保存订单，再按编号查询。
        order = {"id": 1001, "description": "Order from Python", "attachment": b"Meteor"}
        client.service.save(arg0=order)
        found = client.service.find(arg0={"id": 1001})
        result = {
            "id": found.id,
            "description": found.description,
            "attachment": base64.b64encode(found.attachment).decode("ascii"),
        }
        print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()

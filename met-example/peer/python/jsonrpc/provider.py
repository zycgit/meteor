#!/usr/bin/env python3
# Copyright 2015-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0. See LICENSE.txt.
"""使用 Python 标准库发布 JSON-RPC 订单服务。"""

import json
from http.server import BaseHTTPRequestHandler, HTTPServer


class OrderRequestHandler(BaseHTTPRequestHandler):
    orders = {}

    def do_POST(self):
        # 本例只发布 orders.save 和 orders.find 两个方法。
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        argument = request["params"][0]
        if request["method"] == "orders.save":
            self.orders[argument["id"]] = argument
        result = self.orders[argument["id"]]

        content = json.dumps({"jsonrpc": "2.0", "id": request["id"], "result": result}).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(content)))
        self.end_headers()
        self.wfile.write(content)


def main():
    with HTTPServer(("127.0.0.1", 8080), OrderRequestHandler) as server:
        print("Order provider is ready: http://127.0.0.1:8080/jsonrpc", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()

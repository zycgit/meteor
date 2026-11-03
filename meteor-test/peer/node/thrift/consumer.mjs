#!/usr/bin/env node
// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
import thrift from "thrift";
import OrderThriftService from "./generated/OrderThriftService.js";
import types from "./generated/order_types.js";
import { orderRequest, orderResult } from "../order-nxn.mjs";

async function main() {
    // 使用官方 Node SDK 的 Binary framed TCP 客户端，不经过 HTTP 代理。
    const target = new URL(process.env.METEOR_ORDER_TARGET || "thrift://127.0.0.1:8080");
    const connection = thrift.createConnection(target.hostname, Number(target.port), {
        transport: thrift.TFramedTransport,
        protocol: thrift.TBinaryProtocol,
        connect_timeout: 10_000,
        timeout: 10_000,
    });
    try {
        const client = thrift.createClient(OrderThriftService, connection);
        const order = new types.ThriftOrder(orderRequest(true));

        // 回调由官方生成代码处理，连接或响应超时直接导致本次场景失败。
        const { found, saved } = await new Promise((resolve, reject) => {
            connection.on("error", reject);
            connection.on("timeout", () => reject(new Error("Thrift order request timed out")));
            connection.once("close", () => reject(new Error("Thrift order connection closed before response")));
            connection.once("connect", () => {
                client.save(order, (saveError, saved) => {
                    if (saveError) {
                        reject(saveError);
                        return;
                    }
                    const query = new types.ThriftOrderQuery({id: order.id});
                    client.find(query, (findError, result) => {
                        if (findError) {
                            reject(findError);
                        } else {
                            resolve({ found: result, saved });
                        }
                    });
                });
            });
        });
        orderResult("thrift", found, saved);
    } finally {
        connection.end();
        connection.destroy();
    }
}

main().catch((error) => {
    console.error(error.stack || error);
    process.exitCode = 1;
});

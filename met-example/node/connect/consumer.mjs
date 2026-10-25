#!/usr/bin/env node
// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
import { Agent } from "node:http";
import { createClient } from "@connectrpc/connect";
import { createConnectTransport } from "@connectrpc/connect-node";
import { OrderDemoService } from "./generated/order_pb.js";

async function main() {
    // 按 order.proto 创建官方 Connect 客户端。
    const agent = new Agent({ keepAlive: true });
    const transport = createConnectTransport({
        baseUrl: "http://127.0.0.1:8080/connect",
        httpVersion: "1.1",
        nodeOptions: { agent },
        useBinaryFormat: true,
        defaultTimeoutMs: 10_000,
    });
    const orders = createClient(OrderDemoService, transport);
    try {
        // 保存并查询订单，Protobuf 附件直接使用 Uint8Array。
        await orders.save({
            id: 1001,
            description: "Order from Node.js",
            attachment: Buffer.from("Meteor"),
        });
        const found = await orders.find({ id: 1001 });
        const result = {
            id: found.id,
            description: found.description,
            attachment: Buffer.from(found.attachment).toString("base64"),
        };
        console.log(JSON.stringify({ language: "node", protocol: "connect", result }));
    } finally {
        agent.destroy();
    }
}

main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
});

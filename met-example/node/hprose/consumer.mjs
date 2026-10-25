#!/usr/bin/env node
// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
import hprose from "hprose";

async function main() {
    // 连接 Meteor 的 Hprose 服务，附件使用原生 Buffer。
    const client = hprose.Client.create("http://127.0.0.1:8080/hprose");
    client.timeout = 10_000;
    const orders = await client.ready();
    await orders.orders_save({
        id: 1001,
        description: "Order from Node.js",
        attachment: Buffer.from("Meteor"),
    });

    // 查询同一订单，终端中的二进制附件以 Base64 展示。
    const found = await orders.orders_find({ id: 1001 });
    const result = { ...found, attachment: Buffer.from(found.attachment).toString("base64") };
    console.log(JSON.stringify({ language: "node", protocol: "hprose", result }));
}

main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
});

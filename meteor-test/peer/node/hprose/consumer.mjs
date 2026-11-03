#!/usr/bin/env node
// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
import hprose from "hprose";
import { orderRequest, orderResult, orderTarget } from "../order-nxn.mjs";

const target = orderTarget("http://127.0.0.1:8080/hprose", "/hprose");

async function main() {
    // 连接 Meteor 的 Hprose 服务，附件使用原生 Buffer。
    const client = hprose.Client.create(target.href);
    client.timeout = 10_000;
    const orders = await client.ready();
    const request = orderRequest(true);
    const saved = await orders.orders_save(request);

    // 查询同一订单，终端中的二进制附件以 Base64 展示。
    const found = await orders.orders_find({ id: request.id });
    orderResult("hprose", found, saved);
}

main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
});

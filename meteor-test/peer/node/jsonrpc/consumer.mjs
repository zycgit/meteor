#!/usr/bin/env node
// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
import { orderRequest, orderResult, orderTarget } from "../order-nxn.mjs";

const target = orderTarget("http://127.0.0.1:8080/jsonrpc", "/jsonrpc");

async function call(method, argument) {
    const response = await fetch(target, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ jsonrpc: "2.0", id: 1, method: "orders." + method, params: [argument] }),
        signal: AbortSignal.timeout(10_000),
    });
    const document = await response.json();
    if (!response.ok || document.error) {
        throw new Error(JSON.stringify(document));
    }
    return document.result;
}

async function main() {
    // JSON 中的二进制附件使用 Base64。
    const request = orderRequest();
    const saved = await call("save", request);

    // 保存后查询，打印业务结果。
    const result = await call("find", { id: request.id });
    orderResult("jsonrpc", result, saved);
}

main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
});

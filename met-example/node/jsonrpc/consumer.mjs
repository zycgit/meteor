#!/usr/bin/env node
// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
async function call(method, argument) {
    const response = await fetch("http://127.0.0.1:8080/jsonrpc", {
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
    await call("save", {
        id: 1001,
        description: "Order from Node.js",
        attachment: Buffer.from("Meteor").toString("base64"),
    });

    // 保存后查询，打印业务结果。
    const result = await call("find", { id: 1001 });
    console.log(JSON.stringify({ language: "node", protocol: "jsonrpc", result }));
}

main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
});

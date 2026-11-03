#!/usr/bin/env node
// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
import { createServer } from "node:http";
import { orderReady } from "../order-nxn.mjs";

const bindHost = process.env.METEOR_ORDER_BIND_HOST || "127.0.0.1";
const bindPort = Number(process.env.METEOR_ORDER_PORT || "8080");
const publishHost = process.env.METEOR_ORDER_PUBLISH_HOST || process.env.METEOR_TEST_PEER_HOST || bindHost;

// 订单存入当前进程的内存，JSON 的二进制附件使用 Base64。
const orders = new Map();
function dispatch(call) {
    const [argument] = call.params;
    if (call.method === "orders.save") {
        orders.set(argument.id, argument);
        return { result: argument };
    }
    if (call.method === "orders.find") {
        const order = orders.get(argument.id);
        if (order === undefined) {
            return { error: { code: -32004, message: "Order not found: " + argument.id } };
        }
        return { result: order };
    }
    return { error: { code: -32601, message: "Method not found" } };
}

function reply(response, body) {
    response.writeHead(200, { "content-type": "application/json; charset=utf-8" });
    response.end(JSON.stringify(body));
}

// 接收单次 JSON-RPC 调用；notification 完成后只返回 HTTP 状态。
const server = createServer(async (request, response) => {
    if (request.method !== "POST" || request.url !== "/jsonrpc") {
        response.writeHead(404).end();
        return;
    }
    let call;
    try {
        const chunks = [];
        for await (const chunk of request) {
            chunks.push(chunk);
        }
        call = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    } catch {
        reply(response, { jsonrpc: "2.0", id: null, error: { code: -32700, message: "Parse error" } });
        return;
    }
    if (call?.jsonrpc !== "2.0" || typeof call.method !== "string") {
        reply(response, { jsonrpc: "2.0", id: null, error: { code: -32600, message: "Invalid Request" } });
        return;
    }
    let result;
    try {
        result = dispatch(call);
    } catch (error) {
        result = { error: { code: -32602, message: error.message } };
    }
    if (call.id === undefined) {
        response.writeHead(204).end();
    } else {
        reply(response, { jsonrpc: "2.0", id: call.id, ...result });
    }
});

// 启动监听，退出时关闭当前进程创建的连接。
server.on("error", (error) => {
    console.error(error.message);
    process.exitCode = 1;
});
server.listen(bindPort, bindHost, () => {
    const port = server.address().port;
    orderReady(`http://${publishHost}:${port}/jsonrpc`);
});
const close = () => {
    server.close();
    server.closeAllConnections();
};
process.once("SIGINT", close);
process.once("SIGTERM", close);

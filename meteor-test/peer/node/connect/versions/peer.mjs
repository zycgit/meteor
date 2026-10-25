#!/usr/bin/env node
// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
import { Agent, createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { createInterface } from "node:readline";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const [role, outputDirectory, target] = process.argv.slice(2);
if (!outputDirectory || !["provider", "consumer"].includes(role)) {
    throw new Error("Usage: peer.mjs provider|consumer <outputRoot> [target]");
}

// 两代 SDK 从各自生成目录加载依赖；每个进程只使用一个明确版本。
const runtimeFile = join(resolve(outputDirectory), "runtime.mjs");
const sdk = await import(pathToFileURL(runtimeFile).href);
const manifestFile = join(resolve(outputDirectory), "node_modules/@connectrpc/connect/package.json");
const manifest = JSON.parse(await readFile(manifestFile, "utf8"));
if (manifest.version !== sdk.sdkVersion) {
    throw new Error(`Connect SDK changed after contract generation: ${sdk.sdkVersion} -> ${manifest.version}`);
}
console.log(`SDK Connect-ES@${manifest.version}`);
const contextPath = "/versions/connect";

function provide() {
    // 官方 SDK 负责 Connect 协议处理，当前入口不启用其它线协议。
    const orders = new Map();
    const handler = sdk.connectNodeAdapter({
        requestPathPrefix: contextPath,
        connect: true,
        grpc: false,
        grpcWeb: false,
        routes(router) {
            router.service(sdk.OrderDemoService, {
                save(order) {
                    orders.set(order.id, order);
                    return order;
                },
                find(query) {
                    const order = orders.get(query.id);
                    if (order === undefined) {
                        throw new sdk.ConnectError(`Order not found: ${query.id}`, sdk.Code.NotFound);
                    }
                    return order;
                },
            });
        },
    });
    const server = createServer(handler);
    server.on("error", fail);
    server.listen(0, "0.0.0.0", () => console.log(`READY ${server.address().port}`));

    // 生命周期归本次进程；stdin 关闭或终止信号只释放当前监听与连接。
    const sockets = new Set();
    server.on("connection", (socket) => {
        sockets.add(socket);
        socket.on("close", () => sockets.delete(socket));
    });
    const close = () => {
        server.close();
        for (const socket of sockets) {
            socket.destroy();
        }
    };
    createInterface({ input: process.stdin }).once("close", close);
    process.once("SIGINT", close);
    process.once("SIGTERM", close);
}

async function consume() {
    // 消费者使用当前 SDK 的正式客户端和 Protobuf 编码。
    const agent = new Agent({ keepAlive: true });
    const transport = sdk.createConnectTransport({
        baseUrl: target,
        httpVersion: "1.1",
        nodeOptions: { agent },
        useBinaryFormat: true,
        defaultTimeoutMs: 10_000,
    });
    const orders = sdk.createClient(sdk.OrderDemoService, transport);
    try {
        // 保存并查询同一订单，返回值由 Java 场景验证字段和二进制内容。
        await orders.save({ id: 1001, description: "Order from Node.js", attachment: Buffer.from("Meteor") });
        const found = await orders.find({ id: 1001 });
        const result = {
            id: found.id,
            description: found.description,
            attachment: Buffer.from(found.attachment).toString("base64"),
        };
        console.log(JSON.stringify(result));
    } finally {
        agent.destroy();
    }
}

function fail(error) {
    console.error(error.stack || error);
    process.exitCode = 1;
}

if (role === "provider") {
    provide();
} else {
    await consume().catch(fail);
}

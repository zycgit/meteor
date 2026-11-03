#!/usr/bin/env node
// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
import { Agent, createServer as createHttp1Server } from "node:http";
import { createServer as createHttp2Server } from "node:http2";
import { Code, ConnectError, createClient } from "@connectrpc/connect";
import {
    connectNodeAdapter,
    createGrpcTransport,
    createGrpcWebTransport,
    Http2SessionManager,
} from "@connectrpc/connect-node";
import { OrderDemoService } from "./generated/order_pb.js";
import { orderReady, orderRequest, orderResult, orderTarget } from "../order-nxn.mjs";

const [protocol, role, mountPath = `/${protocol}`] = process.argv.slice(2);
// 标准根路径用于 Triple 的 gRPC 兼容面；默认路径仍按当前协议隔离。
const contextPath = mountPath === "/" ? "" : mountPath;

async function consume() {
    // 根据当前用例显式选择线协议，不借用 Connect 请求代替 gRPC 或 gRPC-Web。
    const target = orderTarget(undefined, contextPath);
    let session;
    let agent;
    let transport;
    if (protocol === "grpc") {
        session = new Http2SessionManager(target.href);
        transport = createGrpcTransport({
            baseUrl: target.href,
            sessionManager: session,
            defaultTimeoutMs: 10_000,
        });
    } else {
        agent = new Agent({ keepAlive: true });
        transport = createGrpcWebTransport({
            baseUrl: target.href,
            httpVersion: "1.1",
            nodeOptions: { agent },
            defaultTimeoutMs: 10_000,
        });
    }
    const orders = createClient(OrderDemoService, transport);
    try {
        // 统一订单的保存与查询同时验证结构字段和二进制附件。
        const request = orderRequest(true);
        const saved = await orders.save(request);
        const found = await orders.find({ id: request.id });
        orderResult(protocol, found, saved);
    } finally {
        // 消费者只释放本次进程创建的 HTTP 连接和会话。
        session?.abort();
        agent?.destroy();
    }
}

function provide() {
    // 官方 SDK 直接发布当前协议；关闭其它协议，防止用错误协议得出通过结论。
    const orders = new Map();
    const handler = connectNodeAdapter({
        requestPathPrefix: contextPath,
        connect: false,
        grpc: protocol === "grpc",
        grpcWeb: protocol === "grpc-web",
        routes(router) {
            router.service(OrderDemoService, {
                save(order) {
                    orders.set(order.id, order);
                    return order;
                },
                find(query) {
                    const order = orders.get(query.id);
                    if (order === undefined) {
                        throw new ConnectError(`Order not found: ${query.id}`, Code.NotFound);
                    }
                    return order;
                },
            });
        },
    });
    const server = protocol === "grpc" ? createHttp2Server(handler) : createHttp1Server(handler);
    const host = process.env.METEOR_ORDER_BIND_HOST || "127.0.0.1";
    const port = Number(process.env.METEOR_ORDER_PORT || "0");
    const publishHost = process.env.METEOR_ORDER_PUBLISH_HOST || process.env.METEOR_TEST_PEER_HOST || host;
    server.on("error", (error) => {
        console.error(error.stack || error);
        process.exitCode = 1;
    });
    server.listen(port, host, () => {
        const target = `http://${publishHost}:${server.address().port}${contextPath}`;
        orderReady(target);
    });

    // 场景结束只关闭本次提供者监听与连接，不操作共享语言容器。
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
    process.once("SIGINT", close);
    process.once("SIGTERM", close);
}

if (!["grpc", "grpc-web"].includes(protocol)) {
    throw new Error(`Unknown order protocol: ${protocol}`);
}
if (role === "consumer") {
    consume().catch((error) => {
        console.error(error.stack || error);
        process.exitCode = 1;
    });
} else if (role === "provider") {
    provide();
} else {
    throw new Error(`Unknown order peer role: ${role}`);
}

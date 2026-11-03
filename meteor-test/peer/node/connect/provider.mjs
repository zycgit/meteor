#!/usr/bin/env node
// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
import { createServer } from "node:http";
import { Code, ConnectError } from "@connectrpc/connect";
import { connectNodeAdapter } from "@connectrpc/connect-node";
import { OrderDemoService } from "./generated/order_pb.js";
import { orderReady } from "../order-nxn.mjs";

const bindHost = process.env.METEOR_ORDER_BIND_HOST || "127.0.0.1";
const bindPort = Number(process.env.METEOR_ORDER_PORT || "8080");
const publishHost = process.env.METEOR_ORDER_PUBLISH_HOST || process.env.METEOR_TEST_PEER_HOST || bindHost;

// 按 order.proto 发布订单服务，挂载路径与 Meteor 保持一致。
const orders = new Map();
const handler = connectNodeAdapter({
    requestPathPrefix: "/connect",
    connect: true,
    grpc: false,
    grpcWeb: false,
    routes(router) {
        router.service(OrderDemoService, {
            save(order) {
                orders.set(order.id, order);
                return order;
            },
            find(query) {
                const order = orders.get(query.id);
                if (order === undefined) {
                    throw new ConnectError("Order not found: " + query.id, Code.NotFound);
                }
                return order;
            },
        });
    },
});

// 启动监听，退出时关闭当前进程创建的连接。
const server = createServer(handler);
server.on("error", (error) => {
    console.error(error.message);
    process.exitCode = 1;
});
server.listen(bindPort, bindHost, () => {
    const port = server.address().port;
    orderReady(`http://${publishHost}:${port}/connect`);
});
const close = () => {
    server.close();
    server.closeAllConnections();
};
process.once("SIGINT", close);
process.once("SIGTERM", close);

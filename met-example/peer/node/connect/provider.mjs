#!/usr/bin/env node
// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
import { createServer } from "node:http";
import { Code, ConnectError } from "@connectrpc/connect";
import { connectNodeAdapter } from "@connectrpc/connect-node";
import { OrderDemoService } from "./generated/order_pb.js";

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
server.listen(8080, "127.0.0.1", () => {
    console.log("Order provider is ready: http://127.0.0.1:8080/connect");
});
const close = () => {
    server.close();
    server.closeAllConnections();
};
process.once("SIGINT", close);
process.once("SIGTERM", close);

#!/usr/bin/env node
// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
import thrift from "thrift";
import OrderThriftService from "./generated/OrderThriftService.js";

const bindHost = process.env.METEOR_ORDER_BIND_HOST || "127.0.0.1";
const bindPort = Number(process.env.METEOR_ORDER_PORT || "8080");
const publishHost = process.env.METEOR_TEST_PEER_HOST || bindHost;

// 官方生成的 Processor 使用回调接口，存储保留订单的字符串及二进制内容。
const orders = new Map();
const server = thrift.createServer(OrderThriftService, {
    save(order, callback) {
        orders.set(order.id, order);
        callback(null, order);
    },
    find(query, callback) {
        const order = orders.get(query.id);
        if (order === undefined) {
            callback(new Error("Order not found: " + query.id));
        } else {
            callback(null, order);
        }
    },
}, {
    transport: thrift.TFramedTransport,
    protocol: thrift.TBinaryProtocol,
});

// 每个场景独占监听端口；退出只释放当前进程拥有的连接。
const sockets = new Set();
server.on("connection", (socket) => {
    sockets.add(socket);
    socket.once("close", () => sockets.delete(socket));
});
const close = () => {
    server.close();
    for (const socket of sockets) {
        socket.destroy();
    }
};
server.on("error", (error) => {
    console.error(error.stack || error);
    process.exitCode = 1;
    close();
});
server.listen(bindPort, bindHost, () => {
    const port = server.address().port;
    console.log(`Order provider is ready: thrift://${publishHost}:${port}`);
});
process.once("SIGINT", close);
process.once("SIGTERM", close);

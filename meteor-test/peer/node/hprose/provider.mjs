#!/usr/bin/env node
// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
import hprose from "hprose";

const bindHost = process.env.METEOR_ORDER_BIND_HOST || "127.0.0.1";
const bindPort = Number(process.env.METEOR_ORDER_PORT || "8080");
const publishHost = process.env.METEOR_ORDER_PUBLISH_HOST || process.env.METEOR_TEST_PEER_HOST || bindHost;

// 注册与 Meteor 订单接口对应的 Hprose 方法。
const orders = new Map();
const server = new hprose.HttpServer(bindPort, bindHost);
server.addFunction((order) => {
    orders.set(order.id, order);
    return order;
}, "orders_save");
server.addFunction((query) => {
    const order = orders.get(query.id);
    if (order === undefined) {
        throw new Error("Order not found: " + query.id);
    }
    return order;
}, "orders_find");

// 启动监听，退出时关闭当前进程创建的连接。
server.server.on("error", (error) => {
    console.error(error.message);
    process.exitCode = 1;
});
server.server.once("listening", () => {
    const port = server.server.address().port;
    console.log(`Order provider is ready: http://${publishHost}:${port}/hprose`);
});
server.start();
const close = () => {
    server.close();
    server.server.closeAllConnections();
};
process.once("SIGINT", close);
process.once("SIGTERM", close);

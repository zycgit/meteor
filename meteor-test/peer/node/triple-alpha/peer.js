#!/usr/bin/env node
// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
'use strict';
const assert = require('node:assert/strict');
const {createServer} = require('node:http2');
const {createPromiseClient, Code, DubboError} = require('@apachedubbo/dubbo');
const {createGrpcTransport, dubboNodeAdapter, Http2SessionManager} = require('@apachedubbo/dubbo-node');
const {OrderDemoService} = require('./generated/order_connect.js');

async function consume(target) {
    // 使用 Apache Dubbo alpha SDK 的公开 gRPC 传输，服务与消息定义来自共同订单 proto。
    const session = new Http2SessionManager(target, {idleConnectionTimeoutMs: 100});
    const transport = createGrpcTransport({baseUrl: target, httpVersion: '2', sessionManager: session});
    const orders = createPromiseClient(OrderDemoService, transport);
    const callOptions = {
        timeoutMs: 10_000,
        headers: {'tri-service-group': 'default', 'tri-service-version': '1.0.0'},
    };
    try {
        const order = {
            id: 1001,
            description: 'Order from Apache Dubbo Node alpha',
            attachment: Buffer.from('Meteor'),
        };
        await orders.save(order, callOptions);
        const result = await orders.find({id: order.id}, callOptions);

        // 断言实际查询结果，SDK 自行处理 Protobuf 编解码和 HTTP/2 RPC。
        assert.equal(result.id, order.id);
        assert.equal(result.description, order.description);
        assert.deepEqual(Buffer.from(result.attachment), order.attachment);
        console.log('OK');
    } finally {
        session.abort();
    }
}

function provide() {
    // 当前对端仅启用 SDK 的 gRPC/Protobuf 兼容面，不通过其它协议的回退完成调用。
    const orders = new Map();
    const handler = dubboNodeAdapter({
        grpc: true,
        grpcWeb: false,
        triple: false,
        routes(router) {
            router.service(OrderDemoService, {
                save(order) {
                    orders.set(order.id, order);
                    return order;
                },
                find(query) {
                    const result = orders.get(query.id);
                    if (result === undefined) {
                        throw new DubboError(`Order not found: ${query.id}`, Code.NotFound);
                    }
                    return result;
                },
            }, {
                grpc: true,
                grpcWeb: false,
                triple: false,
                serviceGroup: 'default',
                serviceVersion: '1.0.0',
            });
        },
    });
    const server = createServer(handler);
    const sessions = new Set();
    server.on('session', session => {
        sessions.add(session);
        session.on('close', () => sessions.delete(session));
    });
    server.listen(0, '0.0.0.0', () => console.log(`READY ${server.address().port}`));

    // 外层场景通过 stdin EOF 关闭本次提供者；只释放本进程创建的监听与会话。
    const close = () => {
        server.close();
        for (const session of sessions) {
            session.destroy();
        }
    };
    process.stdin.resume();
    process.stdin.once('end', close);
    process.once('SIGTERM', close);
    server.on('error', error => {
        console.error(error.stack || error);
        process.exitCode = 1;
    });
}

const [role, target] = process.argv.slice(2);
if (role === 'client') {
    consume(target).catch(error => {
        console.error(error.stack || error);
        process.exitCode = 1;
    });
} else if (role === 'server') {
    provide();
} else {
    throw new Error(`Unknown Triple alpha SDK role: ${role}`);
}

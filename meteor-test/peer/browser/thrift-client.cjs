// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
'use strict';
const thrift = require('thrift');
const orders = require('../node/thrift/generated/OrderThriftService');
const types = require('../node/thrift/generated/order_types');

window.meteorThriftSave = async target => {
    // 官方 XHR 连接提供 HTTP 头配置入口，TJSONProtocol 与生成 Client 自行处理消息表示。
    const endpoint = new URL(target);
    const connection = thrift.createXHRConnection(endpoint.hostname, Number(endpoint.port), {
        path: endpoint.pathname,
        transport: thrift.TBufferedTransport,
        protocol: thrift.TJSONProtocol,
        timeout: 10000,
        headers: {'Content-Type': 'application/x-thrift'},
    });
    const client = thrift.createXHRClient(orders, connection);
    const order = new types.ThriftOrder({
        id: 1001,
        description: 'Order from browser Thrift',
        attachment: Buffer.from('Meteor'),
    });
    try {
        // 此场景只有一次 save unary；超时、HTTP 失败和 SDK 错误保持为测试失败。
        return await new Promise((resolve, reject) => {
            const deadline = setTimeout(() => reject(new Error('Thrift XHR response timed out')), 15000);
            const fail = error => {
                clearTimeout(deadline);
                reject(error);
            };
            connection.on('error', fail);
            client.save(order, (error, saved) => {
                clearTimeout(deadline);
                if (error) {
                    reject(error);
                } else {
                    resolve({
                        id: saved.id,
                        description: saved.description,
                        attachment: Buffer.from(saved.attachment).toString('base64'),
                    });
                }
            });
        });
    } finally {
        connection.close();
    }
};

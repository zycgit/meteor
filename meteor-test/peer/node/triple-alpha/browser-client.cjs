// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
'use strict';
const {createPromiseClient} = require('@apachedubbo/dubbo');
const {createDubboTransport} = require('@apachedubbo/dubbo-web');
const {OrderDemoService} = require('./generated/order_connect.js');

window.meteorTripleAlphaSave = async target => {
    // Apache Dubbo Web alpha 的 Triple HTTP unary 传输使用裸 Protobuf，不借用 gRPC-Web。
    const transport = createDubboTransport({baseUrl: target, useBinaryFormat: true});
    const orders = createPromiseClient(OrderDemoService, transport);
    const order = {
        id: 1001,
        description: 'Order from Apache Dubbo Web alpha',
        attachment: new TextEncoder().encode('Meteor'),
    };
    await orders.save(order, {timeoutMs: 10_000});
    const result = await orders.find({id: order.id}, {timeoutMs: 10_000});

    // 返回浏览器真实 RPC 结果，Java 场景验证字段和二进制附件。
    return {
        id: result.id,
        description: result.description,
        attachment: btoa(String.fromCharCode(...result.attachment)),
    };
};

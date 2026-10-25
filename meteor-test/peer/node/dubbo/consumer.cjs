#!/usr/bin/env node
// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
'use strict';
const {DirectlyDubbo, java} = require('dubbo2.js');

async function main() {
    // SDK 的正式直连入口不创建注册中心，也不自行编码 Dubbo 帧。
    const target = new URL(process.env.METEOR_ORDER_TARGET);
    const client = DirectlyDubbo.from({
        dubboAddress: `${target.hostname}:${target.port}`,
        dubboVersion: '2.0.2',
        dubboInvokeTimeout: 10,
    });
    const service = client.proxyService({
        dubboInterface: 'net.hasor.example.protocol.dubbo.interop.DubboInteropService',
        group: 'interop',
        version: '1.0.0',
        methods: {
            echo(message) {
                return [java.String(message)];
            },
        },
    });

    // 当前独立进程只完成一次 String 调用，失败直接交给测试报告。
    const result = await service.echo('Node Dubbo 中文 🙂');
    if (result.err) {
        throw result.err;
    }

    // 此版本 SDK 没有公开关闭入口；结果写完后结束本次独占进程并释放其 Socket。
    process.stdout.write(JSON.stringify({value: result.res}) + '\n', () => process.exit(0));
}

main().catch(error => {
    process.stderr.write(String(error.stack || error) + '\n', () => process.exit(1));
});

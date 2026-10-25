#!/usr/bin/env node
// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
'use strict';

const {DirectlyDubbo, java} = require('dubbo2.js');
const sdkVersion = require('dubbo2.js/package.json').version;

async function main() {
    // 准备阶段把本入口放到独立版本依赖旁，直连和参数编码均由当前 SDK 处理。
    const [role, targetUrl, message = 'Node Dubbo → Meteor 中文🙂 <&>'] = process.argv.slice(2);
    if (role !== 'consumer') {
        throw new Error('dubbo2.js version peer only supports consumer role');
    }
    const target = new URL(targetUrl);
    // 1.x 按原厂 README 声明旧协议；2.x 的解码器同时支持带附件结果。
    const protocolVersion = sdkVersion.startsWith('1.') ? '2.0.0' : '2.0.2';
    console.log(`Dubbo request protocol ${protocolVersion}`);
    const client = DirectlyDubbo.from({
        dubboAddress: `${target.hostname}:${target.port}`,
        dubboVersion: protocolVersion,
        dubboInvokeTimeout: 10,
    });
    const service = client.proxyService({
        dubboInterface: 'net.hasor.example.protocol.dubbo.interop.DubboInteropService',
        group: 'interop',
        version: '1.0.0',
        methods: {
            echo(value) {
                return [java.String(value)];
            },
        },
    });

    // 两个发行版均公开返回 {err, res}；只调用一次，不修改报文或掩盖原始 SDK 错误。
    const result = await service.echo(message);
    if (result.err) {
        throw result.err;
    }
    if (result.res !== message) {
        throw new Error(`Dubbo echo mismatch: expected ${message}, received ${result.res}`);
    }

    // SDK 无公开关闭接口，写完结果后结束独占进程，由操作系统释放该进程的 Socket。
    process.stdout.write(JSON.stringify({value: result.res}) + '\nOK\n', () => process.exit(0));
}

main().catch(error => {
    process.stderr.write(String(error.stack || error) + '\n', () => process.exit(1));
});

#!/usr/bin/env node
// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
'use strict';

const {loadRuntime, argumentValue, resultValue} = require('./runtime.cjs');

async function main() {
    // 版本依赖和生成契约都由 profile 显式指定；每个进程只使用一个 SDK。
    const [profile, targetUrl, encoding = 'binary', kind = 'string', suppliedValue] = process.argv.slice(2);
    let text = suppliedValue;
    if (text === undefined) {
        text = kind === 'string' ? 'Meteor SDK version' : '9223372036854775807';
    }
    const runtime = loadRuntime(profile, encoding, kind);
    const target = new URL(targetUrl);
    const connection = runtime.thrift.createConnection(target.hostname, Number(target.port), {
        transport: runtime.thrift.TFramedTransport,
        protocol: runtime.protocol,
        connect_timeout: 10_000,
        timeout: 10_000,
    });
    try {
        // 线上调用、帧和编解码全部交给官方 SDK；回调式入口跨版本保持一致。
        const client = runtime.thrift.createClient(runtime.service, connection);
        const method = kind === 'string' ? 'echo' : 'echoI64';
        const argument = argumentValue(runtime, kind, text);
        const returned = await new Promise((resolve, reject) => {
            connection.on('error', reject);
            connection.once('timeout', () => reject(new Error('Thrift SDK request timed out')));
            connection.once('close', () => reject(new Error('Thrift connection closed before response')));
            connection.once('connect', () => {
                client[method](argument, (error, value) => error ? reject(error) : resolve(value));
            });
        });

        // 输出精确值和实际 JS 类型，避免 64 位整数先转为 Number 丢失精度。
        const result = resultValue(kind, returned);
        if (result !== text) {
            throw new Error(`Thrift echo mismatch: expected ${text}, received ${result}`);
        }
        if (kind === 'bigint' && typeof returned !== 'bigint') {
            throw new Error('BigInt binding returned ' + typeof returned);
        }
        console.log(JSON.stringify({sdk: 'Apache Thrift Node.js', version: runtime.version,
            encoding, kind, resultType: typeof returned, result}));
        console.log('OK');
    } finally {
        connection.end();
        connection.destroy();
    }
}

main().catch(error => {
    console.error(error.stack || error);
    process.exitCode = 1;
});

#!/usr/bin/env node
// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
'use strict';

const {loadRuntime} = require('./runtime.cjs');
const readline = require('node:readline');

// 官方 Processor 执行 JavaScript 服务，版本及线协议由本次进程的显式 profile 确定。
const [profile, host, port, encoding = 'binary', kind = 'string'] = process.argv.slice(2);
const runtime = loadRuntime(profile, encoding, kind);
const server = runtime.thrift.createServer(runtime.service, {
    echo(value, callback) {
        callback(null, value);
    },
    echoI64(value, callback) {
        if (kind === 'bigint' && typeof value !== 'bigint') {
            callback(new Error('BigInt binding received ' + typeof value));
            return;
        }
        callback(null, value);
    },
}, {
    transport: runtime.thrift.TFramedTransport,
    protocol: runtime.protocol,
});

// 该进程只拥有自己的监听及连接；退出不干扰常驻语言容器。
const sockets = new Set();
server.on('connection', socket => {
    sockets.add(socket);
    socket.once('close', () => sockets.delete(socket));
});
const close = () => {
    server.close();
    for (const socket of sockets) {
        socket.destroy();
    }
};
server.on('error', error => {
    console.error(error.stack || error);
    process.exitCode = 1;
    close();
});
server.listen(Number(port), host, () => {
    const address = server.address();
    console.log(`Thrift ${runtime.version} ${encoding} provider is ready: thrift://${host}:${address.port}`);
    console.log('READY ' + address.port);
});
process.once('SIGINT', close);
process.once('SIGTERM', close);
readline.createInterface({input: process.stdin}).once('close', close);

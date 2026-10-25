#!/usr/bin/env node
// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
'use strict';
const {Proxy} = require('hessian-proxy');

// 公开 HTTP RPC 代理负责编码、发起 HTTP 调用和解码响应，不自行拼装协议报文。
const client = new Proxy(process.env.METEOR_ORDER_TARGET);
// SDK 的公开事件保留真实回复；非 200 空响应不会产生 Buffer，诊断不能先行抛错。
client.on('reply', reply => {
    const bytes = reply === undefined ? '<empty HTTP response body>' : reply.toString('hex');
    console.error(`Hessian SDK reply bytes: ${bytes}`);
});
const deadline = setTimeout(() => {
    console.error('Hessian SDK call timed out');
    process.exit(1);
}, 15000);

// 当前进程只验证一次普通 String 调用，保留 SDK 产生的错误。
client.invoke('echo', ['Node Hessian'], (error, value) => {
    clearTimeout(deadline);
    if (error) {
        console.error(error.stack || String(error));
        process.exitCode = 1;
    } else if (typeof value !== 'string') {
        console.error(`Hessian SDK returned ${typeof value}; expected the String echo result`);
        process.exitCode = 1;
    } else {
        console.log(JSON.stringify({value}));
    }
});

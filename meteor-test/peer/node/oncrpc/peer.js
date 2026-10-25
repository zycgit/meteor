#!/usr/bin/env node
// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
'use strict';
const util = require('node:util');
const rpc = require('oncrpc');
const bunyan = require('bunyan');
const log = bunyan.createLogger({name: 'meteor-node-oncrpc-peer', level: 'warn', stream: process.stderr});

const PROGRAM = 0x31234567;
const VERSION = 1;

function EchoCall(options) {
    // SDK 会传入已收到的流对象；仅传公开协议选项，避免其 read/write 方法被现代 Node 当成流实现。
    rpc.RpcCall.call(this, {incoming: options.incoming, xid: options.xid, proc: options.proc});
}
util.inherits(EchoCall, rpc.RpcCall);

function EchoReply(options) {
    rpc.RpcReply.call(this, {incoming: options.incoming, xid: options.xid});
}
util.inherits(EchoReply, rpc.RpcReply);

function writeValue() {
    // 消息头和 TCP record 由 SDK 生成，业务只通过官方 XDR 写一个标准 ASCII string。
    const xdr = this._serialize(rpc.XDR.byteLength(this.value));
    xdr.writeString(this.value);
    this.write(xdr.buffer());
}

function transferValue(chunk, encoding, completed) {
    if (this.incoming) {
        this.value = new rpc.XDR(chunk).readString();
    } else {
        this.push(chunk);
    }
    completed();
}
EchoCall.prototype.writeHead = writeValue;
EchoCall.prototype._transform = transferValue;
EchoReply.prototype.writeHead = writeValue;
EchoReply.prototype._transform = transferValue;

async function consume(target, value) {
    const client = new rpc.RpcClient({log, program: PROGRAM, version: VERSION, url: target});
    let timeout;
    try {
        const result = await new Promise((resolve, reject) => {
            timeout = setTimeout(() => reject(new Error('ONC RPC SDK call timed out')), 10000);
            client.once('error', reject);
            client.once('connect', () => {
                const call = new EchoCall({incoming: false, proc: 1});
                call.value = value;

                // 沿用上游服务扩展入口；官方 parser 验证事务及接受状态，业务回复在 EOF 后读取。
                client._rpc(call, EchoReply, (error, reply) => {
                    if (error) {
                        reject(error);
                        return;
                    }
                    reply.once('error', reject);
                    reply.once('end', () => resolve(reply.value));
                    reply.resume();
                });
                call.end();
            });
        });
        console.log(JSON.stringify({value: result}));
    } finally {
        clearTimeout(timeout);
        client.close();
    }
}

function provide() {
    const server = new rpc.RpcServer({log, program: PROGRAM, version: VERSION});
    server._rpc({name: 'echo', procedure: 1, call: EchoCall, reply: EchoReply}, (request, reply, next) => {
        reply.value = request.value;
        reply.writeHead();
        reply.end();
        next();
    });
    const sockets = new Set();
    server.on('connection', socket => {
        sockets.add(socket);
        socket.once('close', () => sockets.delete(socket));
    });
    server.on('error', error => {
        console.error(error.stack || error);
        process.exitCode = 1;
        close();
    });
    server.on('uncaughtException', (request, reply, error) => {
        console.error(error.stack || error);
        process.exitCode = 1;
        close();
    });

    // 当前进程仅拥有自己的监听器和连接，退出时不操作共享语言容器。
    const close = () => {
        server.close();
        for (const socket of sockets) socket.destroy();
    };
    const host = process.env.METEOR_ORDER_BIND_HOST || '127.0.0.1';
    const port = Number(process.env.METEOR_ORDER_PORT || '0');
    const publishHost = process.env.METEOR_TEST_PEER_HOST || host;
    server.listen(port, host, () => {
        console.log(`ONC RPC provider is ready: tcp://${publishHost}:${server.address().port}`);
    });
    process.once('SIGTERM', close);
    process.once('SIGINT', close);
}

const [role, target, value] = process.argv.slice(2);
if (role === 'consumer') {
    consume(target, value).catch(error => {
        console.error(error.stack || error);
        process.exitCode = 1;
    });
} else if (role === 'provider') {
    provide();
} else {
    throw new Error(`Unknown ONC RPC peer role: ${role}`);
}

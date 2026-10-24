/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const readline = require('node:readline');
const hprose = require('hprose');

// JSON 仅用于父进程控制；业务订单和 Buffer 直接交给官方 Hprose SDK 编解码。
function respond(value) {
    process.stdout.write(JSON.stringify(value) + '\n');
}

function copyOrder(value) {
    return {
        id: value.id,
        description: value.description,
        attachment: Buffer.from(value.attachment)
    };
}

async function callOrder(command, vectors) {
    // 消费者启动参数没有业务地址；只有收到 resolver 的订阅结果后才能连接。
    assert.equal(typeof command.address, 'string');
    assert.ok(command.address.startsWith('http://'));
    const expected = copyOrder(vectors[command.scenario]);
    const client = hprose.Client.create(command.address);
    client.timeout = 10000;
    const service = await client.ready();

    // save 与 find 都通过官方客户端真实发往提供者，查询只携带统一 OrderQuery 字段。
    const saved = await service.orders_save(expected);
    const found = await service.orders_find({id: expected.id});
    assert.ok(saved && typeof saved === 'object');
    assert.ok(found && typeof found === 'object');
    assert.equal(typeof found.id, 'number');
    assert.equal(found.id, expected.id);
    assert.equal(found.description, expected.description);
    assert.ok(Buffer.isBuffer(found.attachment) || found.attachment instanceof Uint8Array);
    assert.deepEqual(Array.from(found.attachment), Array.from(expected.attachment));

    // 控制结果明确展开字段，不能把 JSON 字符串当作 Hprose 业务返回对象。
    return {
        id: found.id,
        description: found.description,
        attachment: Array.from(found.attachment)
    };
}

async function start() {
    const role = process.argv[2];
    assert.ok(role === 'provider' || role === 'consumer');
    let server;
    let vectors;
    if (role === 'provider') {
        // 与 Java OrderDemoServiceImpl 一致，每个进程拥有自己的订单存储和拷贝语义。
        const orders = new Map();
        server = new hprose.HttpServer(0, '0.0.0.0');
        server.errorDelay = 0;
        server.addFunction(order => {
            const stored = copyOrder(order);
            orders.set(stored.id, stored);
            return copyOrder(stored);
        }, 'orders_save');
        server.addFunction(query => {
            const stored = orders.get(query.id);
            if (!stored) {
                throw new Error('Order not found: ' + query.id);
            }
            return copyOrder(stored);
        }, 'orders_find');

        // 注册适配器必须等待真实监听成功，不能预先猜测端口或登记未就绪的服务。
        await new Promise((resolve, reject) => {
            server.server.once('error', reject);
            server.server.once('listening', resolve);
            server.start();
        });
        respond({ready: true, port: server.server.address().port});
    } else {
        // 公共向量的整数数组只作为测试材料，网络发送前转换成原生 Buffer。
        vectors = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));
        respond({ready: true});
    }

    // 控制任务串行处理；stdin 关闭或 quit 都结束当前进程拥有的服务器。
    const input = readline.createInterface({input: process.stdin, crlfDelay: Infinity});
    let tasks = Promise.resolve();
    let closing;
    function close() {
        if (!closing) {
            closing = tasks.finally(async () => {
                if (server) {
                    await new Promise(resolve => server.close(resolve));
                }
            }).finally(() => process.exit(0));
        }
        return closing;
    }
    input.on('line', line => {
        let command;
        try {
            command = JSON.parse(line);
        } catch (failure) {
            respond({error: failure.message});
            return;
        }
        if (command.operation === 'quit') {
            input.close();
            return;
        }
        tasks = tasks.then(async () => {
            try {
                assert.equal(role, 'consumer');
                respond({result: await callOrder(command, vectors)});
            } catch (failure) {
                respond({error: failure.stack || failure.message});
            }
        });
    });
    input.on('close', close);
}

start().catch(failure => {
    console.error(failure.stack || failure);
    process.exit(1);
});

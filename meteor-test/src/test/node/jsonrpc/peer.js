'use strict';
const assert = require('node:assert/strict');
const {once} = require('node:events');
const jayson = require('jayson');
const WebSocket = require('ws');

// 每次进程只执行一个 Java 测试选择的场景，防止网络等待无限延长。
const deadline = setTimeout(() => {
    console.error('JSON-RPC peer exceeded its 20 second deadline');
    process.exit(2);
}, 20000);

function request(client, method, parameters, id) {
    return new Promise((resolve, reject) => {
        client.request(method, parameters, id, (failure, response) => {
            if (failure) {
                reject(failure);
            } else {
                resolve(response);
            }
        });
    });
}

function batchRequest(client, requests) {
    return new Promise((resolve, reject) => {
        client.request(requests, (failure, responses) => {
            if (failure) {
                reject(failure);
            } else {
                resolve(responses);
            }
        });
    });
}

async function awaitNotification(client, expected) {
    // 通知的传输完成不表示业务已执行，通过公开查询等待业务可见结果。
    const expires = Date.now() + 3000;
    while (Date.now() < expires) {
        const response = await request(client, 'peer.lastNotification', [], 'notification-observer');
        if (response.result === expected) {
            return;
        }
        await new Promise(resolve => setTimeout(resolve, 10));
    }
    assert.fail('Notification was not applied by the published service');
}

async function callMeteor(url, scenario) {
    // 使用 Jayson 自身的 HTTP 或 WebSocket 客户端进行编码和响应关联。
    let socket;
    let client;
    const incoming = [];
    if (url.startsWith('ws:')) {
        socket = new WebSocket(url);
        socket.on('message', data => incoming.push(JSON.parse(data.toString())));
        client = jayson.client.websocket({ws: socket, timeout: 5000});
        await once(socket, 'open');
    } else {
        client = jayson.client.http(url);
        client.options.timeout = 5000;
    }

    const scenarios = {
        positional: async () => {
            // 字符串 ID 和 Unicode 内容由独立客户端生成并校验。
            const response = await request(client, 'peer.echo', ['中文🙂'], 'external-string-id');
            assert.deepEqual(response, {jsonrpc: '2.0', id: 'external-string-id', result: '中文🙂'});
        },
        named: async () => {
            // 命名参数顺序与 Java 声明不同，仍按参数名匹配。
            const response = await request(client, 'peer.add', {right: 4, left: 3}, 17);
            assert.deepEqual(response, {jsonrpc: '2.0', id: 17, result: 7});
        },
        notification: async () => {
            // null 是 Jayson 生成无 ID 通知的 API 参数，不会写入 JSON 请求。
            const notification = client.request('peer.notification', ['notification'], null);
            assert.equal(Object.hasOwn(notification, 'id'), false);
            const response = await request(client, 'peer.notification', ['notification'], null);
            assert.equal(response, undefined);
            await awaitNotification(client, 'notification');
            if (socket) {
                // Jayson 会忽略未关联的响应，这里同时观察网络帧以确认通知没有迟到回执。
                await new Promise(resolve => setTimeout(resolve, 100));
                assert.ok(incoming.every(reply => reply.id === 'notification-observer'));
            }
        },
        batch: async () => {
            // 同一批次同时含成功、错误和通知，只关联有 ID 的两条响应。
            const calls = [
                client.request('peer.echo', ['batch'], 'success'),
                client.request('peer.missing', [], 'missing'),
                client.request('peer.notification', ['batch-notification'], null)
            ];
            const responses = await batchRequest(client, calls);
            assert.equal(responses.length, 2);
            const success = responses.find(response => response.id === 'success');
            const missing = responses.find(response => response.id === 'missing');
            assert.equal(success.result, 'batch');
            assert.equal(missing.error.code, -32601);
            await awaitNotification(client, 'batch-notification');
        },
        missing: async () => {
            // 标准客户端能读取 Meteor 对不存在方法返回的标准错误码。
            const response = await request(client, 'peer.missing', [], 19);
            assert.equal(response.id, 19);
            assert.equal(response.error.code, -32601);
        }
    };

    try {
        assert.equal(typeof scenarios[scenario], 'function', 'Unknown peer scenario: ' + scenario);
        await scenarios[scenario]();
        console.log('OK');
    } finally {
        if (socket) {
            socket.terminate();
        }
    }
}

function serve(transport) {
    // Jayson 持有独立业务实现；Meteor 不参与对端消息编解码。
    let notification;
    const service = new jayson.Server({
        'peer.echo': (args, done) => done(null, args[0]),
        'peer.add': (args, done) => done(null, args[0] + args[1]),
        'peer.notification': (args, done) => {
            notification = args[0];
            done();
        },
        'peer.lastNotification': (args, done) => done(null, notification || null),
        'peer.fail': (args, done) => done({code: -32042, message: 'Independent peer rejected request'})
    });

    // 只监听随机本地端口，启动消息与异常均交由父进程有界等待。
    let listener;
    if (transport === 'websocket') {
        listener = service.websocket({host: '127.0.0.1', port: 0, path: '/rpc'});
    } else {
        listener = service.http();
        listener.listen(0, '127.0.0.1');
    }
    listener.on('error', failure => {
        console.error(failure.stack || failure);
        process.exit(1);
    });
    listener.on('listening', () => console.log('READY ' + listener.address().port));

    // 父进程拥有测试对端；关闭标准输入后主动释放所有 WebSocket 和监听器。
    process.stdin.resume();
    process.stdin.on('end', () => {
        if (listener.clients) {
            for (const client of listener.clients) {
                client.terminate();
            }
        }
        listener.close(() => process.exit(0));
    });
}

// 一次只启动服务端或运行客户端场景，错误退出码不会被成功标记覆盖。
if (process.argv[2] === 'server') {
    serve(process.argv[3]);
} else if (process.argv[2] === 'client') {
    callMeteor(process.argv[3], process.argv[4]).then(() => {
        clearTimeout(deadline);
        process.exit(0);
    }, failure => {
        console.error(failure.stack || failure);
        process.exit(1);
    });
} else {
    throw new Error('Expected server or client role');
}

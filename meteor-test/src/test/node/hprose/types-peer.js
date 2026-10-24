'use strict';
const assert = require('node:assert/strict');
const hprose = require('hprose');
const values = require('../types/type-values');

async function consume(endpoint, vector, file) {
    // 官方 SDK 自己发送和读取 Hprose 报文，共享 JSON 只提供本场景的输入。
    const client = hprose.Client.create(endpoint, ['types_echo']);
    client.timeout = 5000;
    client.useHarmonyMap = true;
    const service = await client.ready();
    const request = values.value(file, vector, 'hprose');
    const response = await service.types_echo(request);

    // 每次进程只验证一个字段；超范围整数的文本映射与负零限制单独标识。
    values.assertVector(file, vector, 'hprose', response, 'accepted:');
    if (values.isUnsafeInteger(file, vector)) {
        console.log('MAPPED decimal-text');
    } else if (values.isNegativeZero(file, vector)) {
        console.log('LIMIT negative-zero');
    } else {
        console.log('OK');
    }
}

function provide(file) {
    // 官方 Hprose 服务端拥有监听和业务实现，Java 不代理任何网络收发。
    const server = new hprose.HttpServer(0, '0.0.0.0');
    server.errorDelay = 0;
    server.addFunction(request => {
        const vector = request.caseId;
        const negativeZero = values.isNegativeZero(file, vector);
        if (!negativeZero) {
            values.assertVector(file, vector, 'hprose', request, 'client:');
        } else {
            // 入站仍可收到 Java 的负零，官方 Node 写响应时会丢失其符号。
            const field = values.entry(file, vector).vector.field;
            assert.equal(Object.is(request[field], -0), true);
        }
        return Object.assign({}, request, {receipt: 'accepted:' + vector});
    }, 'types_echo', {useHarmonyMap: true});
    server.start();
    server.server.on('listening', () => console.log('READY ' + server.server.address().port));

    // 控制输入关闭后释放拥有的监听器，服务端不会结束创建它的测试容器。
    process.stdin.resume();
    process.stdin.on('end', () => server.close(() => process.exit(0)));
}

const [role, file, endpoint, vector] = process.argv.slice(2);
if (role === 'provider') {
    provide(file);
} else if (role === 'consumer') {
    consume(endpoint, vector, file).then(() => process.exit(0), failure => {
        console.error(failure.stack || failure);
        process.exit(1);
    });
} else {
    throw new Error('Expected provider or consumer role');
}

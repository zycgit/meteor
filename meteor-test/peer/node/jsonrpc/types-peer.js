'use strict';
const assert = require('node:assert/strict');
const jayson = require('jayson');
const values = require('../types/type-values');

function invoke(client, request, vector) {
    return new Promise((resolve, reject) => {
        client.request('types.echo', [values.toJsonWire(request)], vector, (failure, response) => {
            if (failure) {
                reject(failure);
            } else {
                resolve(response);
            }
        });
    });
}

async function consume(endpoint, vector, file) {
    // 使用锁定的 Jayson SDK，数值保持 JavaScript Number，二进制使用 JSON Base64。
    const client = jayson.client.http(endpoint);
    client.options.timeout = 5000;
    let responseBody = '';
    client.on('http response', incoming => {
        // 只被动观察官方客户端收到的原始响应，不替换其 HTTP 或 JSON 编解码。
        incoming.on('data', chunk => responseBody += chunk);
    });
    const request = values.value(file, vector, 'jsonrpc');
    const unsafeInteger = values.isUnsafeInteger(file, vector);
    const precision = unsafeInteger ? values.jsonPrecisionExpectation(file, vector, request) : null;
    const response = await invoke(client, request, vector);
    assert.equal(response.id, vector);

    // 超范围整数必须作为真实限制报告，不能把舍入后的回显计为精确整数通过。
    if (unsafeInteger) {
        if (precision.outcome === 'invalid-params') {
            assert.equal(response.result, undefined);
            assert.ok(response.error);
            assert.equal(response.error.code, -32602);
            assert.equal(response.error.message, 'Invalid params');
        } else {
            values.assertJsonRoundedResult(file, vector, precision, response, responseBody);
        }
        console.log('LIMIT integer-precision');
        return;
    }

    // 其余实例只断言对应类型和服务端收据，负零规范化属于已验证限制。
    assert.equal(response.error, undefined);
    values.assertVector(file, vector, 'jsonrpc', values.fromJsonWire(response.result), 'accepted:');
    console.log(values.isNegativeZero(file, vector) ? 'LIMIT negative-zero' : 'OK');
}

function provide(file) {
    // Jayson 自行读取 JSON 数字；超范围数值不能恢复，明确拒绝而不返回失真的值。
    const service = new jayson.Server({
        'types.echo': (parameters, done) => {
            const request = values.fromJsonWire(parameters[0]);
            const vector = request.caseId;
            if (values.isUnsafeInteger(file, vector)) {
                values.assertJsonPrecisionLimit(file, vector, request);
                done({code: -32064, message: 'JavaScript Number cannot preserve integer precision: ' + vector});
                return;
            }
            if (!values.isNegativeZero(file, vector)) {
                values.assertVector(file, vector, 'jsonrpc', request, 'client:');
            }
            const result = Object.assign({}, request, {receipt: 'accepted:' + vector});
            done(null, values.toJsonWire(result));
        }
    });
    const listener = service.http();
    listener.on('error', failure => {
        console.error(failure.stack || failure);
        process.exit(1);
    });
    listener.listen(0, '0.0.0.0', () => console.log('READY ' + listener.address().port));

    // 创建者结束控制输入时关闭监听，独立容器由 ContainerProcess 统一回收。
    process.stdin.resume();
    process.stdin.on('end', () => listener.close(() => process.exit(0)));
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

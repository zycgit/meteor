'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');

function entry(file, name) {
    const table = JSON.parse(fs.readFileSync(file, 'utf8'));
    const vector = table.vectors[name];
    assert.ok(vector, 'Unknown shared vector: ' + name);
    const raw = structuredClone(table.defaults);
    raw[vector.field] = vector.value;
    raw.caseId = name;
    raw.receipt = 'client:' + name;
    return {raw, vector};
}

function integer(value, protocol) {
    // Hprose 的官方 Node SDK 以十进制文本保留不安全整数，这只是显式类型映射。
    const number = Number(value);
    if (protocol === 'hprose' && !Number.isSafeInteger(number)) {
        return value;
    }
    return number;
}

function value(file, name, protocol) {
    // 文本只属于共享输入文件；发送前恢复数值、字节以及协议自己的枚举表示。
    const result = entry(file, name).raw;
    result.int64Value = integer(result.int64Value, protocol);
    result.unsigned64Value = integer(result.unsigned64Value, protocol);
    result.float32Value = Math.fround(Number(result.float32Value));
    result.float64Value = Number(result.float64Value);
    for (const key of Object.keys(result.mapValue)) {
        result.mapValue[key] = integer(result.mapValue[key], protocol);
    }
    result.binaryValue = Buffer.from(result.binaryValue, 'base64');
    if (protocol === 'hprose') {
        result.enumValue = result.enumValue === 'FIRST' ? 0 : 1;
        result.mapValue = new Map(Object.entries(result.mapValue));
    }
    return result;
}

function isUnsafeInteger(file, name) {
    // 只检查本实例对应字段，不因无关默认字段增加其它测试场景。
    const {raw, vector} = entry(file, name);
    if (vector.type === 'int64' || vector.type.startsWith('uint')) {
        return !Number.isSafeInteger(Number(raw[vector.field]));
    }
    if (vector.type === 'map') {
        return Object.values(raw.mapValue).some(item => !Number.isSafeInteger(Number(item)));
    }
    return false;
}

function isNegativeZero(file, name) {
    const {raw, vector} = entry(file, name);
    const floating = vector.type === 'float32' || vector.type === 'float64';
    return floating && Object.is(Number(raw[vector.field]), -0);
}

function assertVector(file, name, protocol, actual, receipt) {
    // 收据必须由服务端生成，业务调用不能被本地复制输入替代。
    const expected = value(file, name, protocol);
    const {vector} = entry(file, name);
    assert.equal(actual.caseId, name);
    assert.equal(actual.receipt, receipt + name);
    const expectedField = expected[vector.field];
    const actualField = actual[vector.field];

    // 不安全 Hprose 长整数保持 SDK 的文本结果，不能将其记为 Number 原生支持。
    if (protocol === 'hprose' && isUnsafeInteger(file, name)) {
        if (vector.type === 'map') {
            assert.ok(actualField instanceof Map);
            for (const [key, expectedNumber] of expectedField) {
                assert.equal(typeof actualField.get(key), 'string');
                assert.equal(actualField.get(key), expectedNumber);
            }
        } else {
            assert.equal(typeof actualField, 'string');
            assert.equal(actualField, expectedField);
        }
    } else if (isNegativeZero(file, name)) {
        // 官方 Node 编码器把负零规范化为正零，此实例验证限制而不声称保留符号。
        assert.equal(Object.is(actualField, 0), true);
        assert.equal(Object.is(actualField, -0), false);
    } else if (vector.type === 'float32') {
        assert.equal(Object.is(Math.fround(actualField), expectedField), true);
    } else if (vector.type === 'float64') {
        assert.equal(Object.is(actualField, expectedField), true);
    } else if (vector.type === 'binary') {
        assert.ok(Buffer.isBuffer(actualField) || actualField instanceof Uint8Array);
        assert.deepEqual(Array.from(actualField), Array.from(expectedField));
    } else if (vector.type === 'record') {
        assert.equal(actualField.index, expectedField.index);
        assert.equal(actualField.name, expectedField.name);
    } else if (vector.type === 'map' && protocol === 'hprose') {
        assert.ok(actualField instanceof Map);
        assert.deepEqual(actualField, expectedField);
    } else {
        assert.deepEqual(actualField, expectedField);
    }
}

function assertJsonPrecisionLimit(file, name, request) {
    // 真实 Number 与其 JSON 数字字面量都无法携带输入契约中的精确整数。
    assert.equal(isUnsafeInteger(file, name), true);
    const {raw, vector} = entry(file, name);
    const expected = raw[vector.field];
    const actual = request[vector.field];
    if (vector.type === 'map') {
        for (const key of Object.keys(expected)) {
            assert.equal(typeof actual[key], 'number');
            assert.equal(Number.isSafeInteger(actual[key]), false);
            assert.notEqual(JSON.stringify(actual[key]), expected[key]);
        }
    } else {
        assert.equal(typeof actual, 'number');
        assert.equal(Number.isSafeInteger(actual), false);
        assert.notEqual(JSON.stringify(actual), expected);
    }
}

function jsonPrecisionExpectation(file, name, request) {
    // 依据 SDK 实际写出的十进制词法判断 Java 声明范围，不能接受错误或舍入任意一种结果。
    assertJsonPrecisionLimit(file, name, request);
    const {vector} = entry(file, name);
    const field = vector.field;
    const numbers = vector.type === 'map' ? request[field] : {[field]: request[field]};
    const lexemes = {};
    let outsideLong = false;
    for (const [key, number] of Object.entries(numbers)) {
        const lexeme = JSON.stringify(number);
        assert.match(lexeme, /^-?[0-9]+$/);
        lexemes[key] = lexeme;
        if (vector.type === 'int64' || vector.type === 'map') {
            const integer = BigInt(lexeme);
            if (integer < -9223372036854775808n || integer > 9223372036854775807n) {
                outsideLong = true;
            }
        }
    }
    return {outcome: outsideLong ? 'invalid-params' : 'rounded-result', field, lexemes};
}

function assertJsonRoundedResult(file, name, expected, response, rawBody) {
    // 同时断言 SDK 解析后的结果和被动观察到的服务端整数词法，防止解析再次舍入掩盖回值。
    assert.equal(expected.outcome, 'rounded-result');
    assert.equal(response.error, undefined);
    assert.equal(response.result.caseId, name);
    assert.equal(response.result.receipt, 'accepted:' + name);
    const {vector} = entry(file, name);
    const selected = response.result[expected.field];
    const actual = vector.type === 'map' ? selected : {[expected.field]: selected};
    for (const [key, lexeme] of Object.entries(expected.lexemes)) {
        assert.equal(typeof actual[key], 'number');
        assert.equal(actual[key], Number(lexeme));
        const pattern = new RegExp('"' + key + '"\\s*:\\s*(-?[0-9]+)(?=\\s*[,}])', 'g');
        const matches = Array.from(rawBody.matchAll(pattern));
        assert.equal(matches.length, 1, 'Expected one numeric field: ' + key);
        assert.equal(matches[0][1], lexeme);
    }
}

function toJsonWire(value) {
    // JSON 没有字节 token；只在协议边界把真实 Buffer 映射为标准 Base64。
    assert.ok(Buffer.isBuffer(value.binaryValue));
    return Object.assign({}, value, {binaryValue: value.binaryValue.toString('base64')});
}

function fromJsonWire(value) {
    assert.equal(typeof value.binaryValue, 'string');
    return Object.assign({}, value, {binaryValue: Buffer.from(value.binaryValue, 'base64')});
}

module.exports = {
    entry, value, isUnsafeInteger, isNegativeZero, assertVector, assertJsonPrecisionLimit,
    jsonPrecisionExpectation, assertJsonRoundedResult, toJsonWire, fromJsonWire
};

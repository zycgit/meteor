// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
'use strict';

const path = require('node:path');
const {createRequire} = require('node:module');

function loadRuntime(profileDirectory, encoding, kind) {
    // 每个版本目录独占官方 SDK 及生成绑定，正常 Node 解析不会借用其他场景的依赖。
    const profile = path.resolve(profileDirectory);
    const dependency = createRequire(path.join(profile, 'package.json'));
    const thrift = dependency('thrift');
    const metadata = dependency('thrift/package.json');
    const expectedVersion = path.basename(profile).replace(/^thrift-/, '');
    if (metadata.version !== expectedVersion) {
        throw new Error(`Thrift SDK version mismatch: profile ${expectedVersion}, installed ${metadata.version}`);
    }
    console.log('SDK Apache Thrift@' + metadata.version);
    const generatedDirectory = kind === 'bigint' ? 'generated-bigint' : 'generated';
    const service = require(path.join(profile, generatedDirectory, 'VersionEcho.js'));
    const protocols = {
        binary: thrift.TBinaryProtocol,
        compact: thrift.TCompactProtocol,
        json: thrift.TJSONProtocol,
    };
    const protocol = protocols[encoding];
    if (!protocol) {
        throw new Error('Unknown Thrift encoding: ' + encoding);
    }
    if (!['string', 'i64', 'bigint'].includes(kind)) {
        throw new Error('Unknown Thrift value kind: ' + kind);
    }
    return {thrift, service, dependency, protocol, version: metadata.version};
}

function argumentValue(runtime, kind, text) {
    // 普通契约采用 node-int64 的完整字节；BigInt 场景必须使用官方 bigint 生成绑定。
    if (kind === 'string') {
        return text;
    }
    const value = BigInt(text);
    if (kind === 'bigint') {
        return value;
    }
    const bytes = Buffer.alloc(8);
    bytes.writeBigInt64BE(value);
    const Int64 = runtime.dependency('node-int64');
    return new Int64(bytes);
}

function resultValue(kind, value) {
    if (kind === 'string') {
        return value;
    }
    if (typeof value === 'bigint') {
        return value.toString();
    }
    return value.toBuffer().readBigInt64BE().toString();
}

module.exports = {loadRuntime, argumentValue, resultValue};

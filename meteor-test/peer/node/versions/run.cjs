#!/usr/bin/env node
// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {pathToFileURL} = require('node:url');

// 显式的版本目录决定依赖图，不修改全局模块解析或其它场景的 SDK。
const selected = JSON.parse(fs.readFileSync(path.join(__dirname, 'sdk.json'), 'utf8'));
const installed = path.join(__dirname, 'node_modules', selected.package, 'package.json');
const actual = JSON.parse(fs.readFileSync(installed, 'utf8')).version;
assert.equal(actual, selected.version, `SDK version mismatch: ${selected.profile}`);
console.log(`SDK ${selected.sdk}@${actual}`);

// 共用已有业务脚本；契约生成由准备阶段完成，运行时只执行本次选择的 SDK。
const [role, ...argumentsList] = process.argv.slice(2);
let script;
if (selected.protocol === 'connect') {
    script = path.join(selected.source, 'connect/versions/peer.mjs');
    process.argv = [process.execPath, script, role, __dirname, ...argumentsList];
} else if (selected.protocol === 'thrift') {
    script = path.join(selected.source, `thrift/versions/${role}.cjs`);
    process.argv = [process.execPath, script, __dirname, ...argumentsList];
} else {
    script = path.join(__dirname, selected.protocol === 'grpc-web' ? 'browser.js' : 'peer.js');
    process.argv = [process.execPath, script, role, ...argumentsList];
}
if (script.endsWith('.mjs')) {
    import(pathToFileURL(script).href).catch(error => {
        console.error(error.stack || error);
        process.exitCode = 1;
    });
} else {
    require(script);
}

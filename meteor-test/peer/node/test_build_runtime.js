#!/usr/bin/env node
'use strict';

// Shared Node/browser build checks use temporary sources without Docker or SDK installation.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {test} = require('node:test');
const {prepare} = require('./build-runtime.js');

function fixture(context) {
    const temporaryRoot = path.resolve(__dirname, '../../build/tmp/node-runtime');
    fs.mkdirSync(temporaryRoot, {recursive: true});
    const directory = fs.mkdtempSync(path.join(temporaryRoot, 'run-'));
    context.after(() => fs.rmSync(directory, {recursive: true, force: true}));
    const source = path.join(directory, 'source');
    const output = path.join(directory, 'output');
    const toolchain = path.join(directory, 'toolchain');
    const counter = path.join(directory, 'compile-count.txt');
    fs.mkdirSync(output);
    fs.mkdirSync(path.join(source, 'versions'), {recursive: true});
    fs.writeFileSync(path.join(source, 'versions/profiles.json'), '{"profiles":[]}\n');
    for (const peer of ['hprose', 'connect', 'jsonrpc', 'grpcweb', 'thrift', 'soap', 'xmlrpc', 'dubbo',
        'oncrpc', 'triple-alpha', 'hessian']) {
        fs.mkdirSync(path.join(source, peer), {recursive: true});
        fs.mkdirSync(path.join(toolchain, peer, 'node_modules'), {recursive: true});
        fs.writeFileSync(path.join(source, peer, 'package-lock.json'), peer);
        fs.writeFileSync(path.join(toolchain, peer, 'package-lock.json'), peer);
    }
    const build = [
        "const fs = require('node:fs');",
        `fs.appendFileSync(${JSON.stringify(counter)}, 'compiled\\n');`,
        "fs.writeFileSync('browser.bundle.js', 'browser.bundle.js');",
        "fs.writeFileSync('types-browser.bundle.js', 'types-browser.bundle.js');",
    ].join('\n');
    fs.writeFileSync(path.join(source, 'grpcweb', 'build.js'), build);
    return {source, output, toolchain, counter};
}

test('browser reuses the Node build', context => {
    // Both runtime services use the same source fingerprint and generated output.
    const {source, output, toolchain, counter} = fixture(context);
    const first = prepare(source, output, toolchain);
    const second = prepare(source, output, toolchain);
    assert.equal(second, first);
    assert.equal(fs.readFileSync(counter, 'utf8'), 'compiled\n');
});

test('source changes rebuild the shared output', context => {
    // A reused named volume must not preserve application code from an earlier checkout.
    const {source, output, toolchain, counter} = fixture(context);
    const first = prepare(source, output, toolchain);
    fs.writeFileSync(path.join(source, 'hprose', 'peer.js'), 'changed');
    const second = prepare(source, output, toolchain);
    assert.notEqual(second, first);
    assert.equal(fs.readFileSync(counter, 'utf8'), 'compiled\ncompiled\n');
});

test('SDK lock changes require an image rebuild', context => {
    // Installing dependencies during an individual test is not an allowed fallback.
    const {source, output, toolchain} = fixture(context);
    fs.writeFileSync(path.join(source, 'hprose', 'package-lock.json'), 'changed');
    assert.throws(() => prepare(source, output, toolchain), /rebuild the image: hprose/);
});

test('failed compilation does not publish a build marker', context => {
    // Failed generation cannot be mistaken for a reusable successful build.
    const {source, output, toolchain} = fixture(context);
    prepare(source, output, toolchain);
    fs.writeFileSync(path.join(source, 'grpcweb', 'build.js'), 'process.exit(7);');
    assert.throws(() => prepare(source, output, toolchain), /Node bundle build failed: 7/);
    assert.equal(fs.existsSync(path.join(output, 'node-build.json')), false);
});

test('installed SDK version must match its profile', context => {
    // 锁文件一致也不能掩盖实际安装版本错误，准备失败时不发布成功标记。
    const {source, output, toolchain} = fixture(context);
    const selected = {profile: 'jsonrpc-2.1.2', package: 'jayson', version: '2.1.2'};
    fs.writeFileSync(path.join(source, 'versions/profiles.json'), JSON.stringify({profiles: [selected]}));
    const declared = path.join(source, 'versions/manifests', selected.profile);
    const installed = path.join(toolchain, 'sdk-versions', selected.profile);
    fs.mkdirSync(declared, {recursive: true});
    fs.mkdirSync(path.join(installed, 'node_modules/jayson'), {recursive: true});
    fs.writeFileSync(path.join(declared, 'package-lock.json'), 'locked');
    fs.writeFileSync(path.join(installed, 'package-lock.json'), 'locked');
    fs.writeFileSync(path.join(installed, 'node_modules/jayson/package.json'), '{"version":"5.0.0"}');

    assert.throws(() => prepare(source, output, toolchain), /Installed SDK differs from profile/);
    assert.equal(fs.existsSync(path.join(output, 'node-build.json')), false);
});

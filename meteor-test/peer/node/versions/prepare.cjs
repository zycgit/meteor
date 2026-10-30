#!/usr/bin/env node
// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {spawnSync} = require('node:child_process');

function profiles(source) {
    return JSON.parse(fs.readFileSync(path.join(source, 'versions/profiles.json'), 'utf8')).profiles;
}

function verifyInstalled(source, toolchain) {
    // 每条版本线独占锁定的依赖图；启动准备阶段不安装或替换 SDK。
    return profiles(source).map(profile => {
        const installed = path.join(toolchain, 'sdk-versions', profile.profile);
        const declared = path.join(source, 'versions/manifests', profile.profile, 'package-lock.json');
        const lock = fs.readFileSync(path.join(installed, 'package-lock.json'));
        if (!lock.equals(fs.readFileSync(declared))) {
            throw new Error(`Node version lockfile changed; rebuild the image: ${profile.profile}`);
        }
        const metadata = path.join(installed, 'node_modules', profile.package, 'package.json');
        const actual = JSON.parse(fs.readFileSync(metadata, 'utf8')).version;
        assert.equal(actual, profile.version, `Installed SDK differs from profile ${profile.profile}`);
        return {...profile, installed, version: actual};
    });
}

function run(script, argumentsList) {
    const child = spawnSync(process.execPath, [script, ...argumentsList], {stdio: 'inherit'});
    if (child.error) {
        throw child.error;
    }
    if (child.status !== 0) {
        throw new Error(`Node SDK preparation failed: ${script}: ${child.signal || child.status}`);
    }
}

function prepare(source, output, toolchain, entries) {
    // 同一语言容器共享构建卷，各 SDK 版本只隔离依赖和编译产物。
    const directory = path.join(output, 'sdk-versions');
    fs.mkdirSync(directory, {recursive: true});
    const runtime = [];
    for (const entry of entries) {
        const profile = path.join(directory, entry.profile);
        fs.rmSync(profile, {recursive: true, force: true});
        fs.mkdirSync(profile, {recursive: true});
        fs.copyFileSync(path.join(entry.installed, 'package.json'), path.join(profile, 'package.json'));
        fs.symlinkSync(path.join(entry.installed, 'node_modules'), path.join(profile, 'node_modules'), 'dir');

        // 沿用真实业务入口，只有代码生成 ABI 不同的协议分别准备自己的契约。
        if (entry.protocol === 'connect') {
            const order = path.resolve(source, '../../resource/protocol/proto/order.proto');
            const buf = path.join(toolchain, 'connect/node_modules/.bin/buf');
            run(path.join(source, 'connect/versions/prepare.mjs'), [entry.installed, profile, order, buf]);
        } else if (entry.protocol === 'thrift') {
            run(path.join(source, 'thrift/versions/prepare.cjs'), [profile]);
        } else if (entry.protocol === 'grpc-web') {
            for (const name of ['client.js', 'browser.js', 'browser-diagnostics.js']) {
                fs.cpSync(path.join(source, 'grpcweb', name), path.join(profile, name), {recursive: true});
            }
            // 旧版 protobuf 运行时只接收对应生成器产物，不能借用新版 reader API。
            let generated = path.join(source, 'grpcweb/generated');
            if (entry.version.startsWith('1.')) {
                generated = path.join(source, 'grpcweb/versions/generated');
            }
            fs.cpSync(generated, path.join(profile, 'generated'), {recursive: true});
            const esbuild = require(path.join(toolchain, 'grpcweb/node_modules/esbuild'));
            esbuild.buildSync({
                entryPoints: [path.join(profile, 'client.js')],
                outfile: path.join(profile, 'browser.bundle.js'),
                bundle: true,
                platform: 'browser',
                target: 'es2020',
            });
        } else {
            let script = path.join(source, entry.protocol, 'peer.js');
            if (entry.protocol === 'dubbo') {
                script = path.join(source, 'dubbo/versions/consumer.cjs');
            }
            fs.copyFileSync(script, path.join(profile, 'peer.js'));
        }

        // 运行期再次核对实际 SDK，报告不会仅使用声明版本作为成功证据。
        fs.copyFileSync(path.join(source, 'versions/run.cjs'), path.join(profile, 'run.cjs'));
        const selected = {...entry, root: profile, source};
        fs.writeFileSync(path.join(profile, 'sdk.json'), JSON.stringify(selected) + '\n');
        runtime.push(selected);
    }
    fs.writeFileSync(path.join(directory, 'runtimes.json'), JSON.stringify({profiles: runtime}, null, 2) + '\n');
}

module.exports = {profiles, verifyInstalled, prepare};

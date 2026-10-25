#!/usr/bin/env node
// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
'use strict';
const path = require('node:path');
const {chromium} = require('/opt/meteor-peer/node/grpcweb/node_modules/playwright');
const esbuild = require('/opt/meteor-peer/node/grpcweb/node_modules/esbuild');

async function main() {
    // 依赖保持镜像内的锁定版本；现场打包只生成内存脚本，不安装 SDK、不修改源码。
    const dependencies = '/opt/meteor-peer/node/thrift/node_modules';
    const bundle = esbuild.buildSync({
        entryPoints: [path.join(__dirname, 'thrift-client.cjs')],
        bundle: true,
        platform: 'browser',
        target: 'es2020',
        write: false,
        nodePaths: [dependencies],
        alias: {
            util: path.join(dependencies, 'util/util.js'),
            events: path.join(dependencies, 'events/events.js'),
        },
        inject: [path.join(__dirname, 'thrift-buffer.mjs')],
        define: {global: 'globalThis'},
    });
    const target = process.env.METEOR_ORDER_TARGET;
    const browser = await chromium.launch({headless: true});
    try {
        // 真实导航建立同源地址空间；不拦截业务请求，不关闭浏览器安全策略。
        const page = await browser.newPage();
        page.on('console', message => console.error(`[Chromium] ${message.text()}`));
        page.on('pageerror', error => console.error(error.stack || error));
        const response = await page.goto(target);
        console.error(`[Chromium] Thrift same-origin document: ${response.status()} ${page.url()}`);
        await page.addScriptTag({content: bundle.outputFiles[0].text});

        // XHR 与官方生成 Client 在 Chromium 内运行，Node 不执行或代理 RPC。
        const result = await page.evaluate(target => window.meteorThriftSave(target), target);
        console.log(JSON.stringify(result));
    } finally {
        await browser.close();
    }
}

main().catch(error => {
    console.error(error.stack || error);
    process.exitCode = 1;
});

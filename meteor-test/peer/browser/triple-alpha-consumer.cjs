#!/usr/bin/env node
// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
'use strict';
const {chromium} = require('/opt/meteor-peer/node/grpcweb/node_modules/playwright');
const esbuild = require('/opt/meteor-peer/node/grpcweb/node_modules/esbuild');

async function main() {
    // 只打包镜像中已锁定的 SDK，运行期不下载依赖，浏览器执行真实 SDK 和网络请求。
    const bundle = esbuild.buildSync({
        entryPoints: ['/opt/meteor-peer/node/triple-alpha/browser-client.cjs'],
        bundle: true,
        platform: 'browser',
        target: 'es2020',
        write: false,
        nodePaths: ['/opt/meteor-peer/node/triple-alpha/node_modules'],
    });
    const target = process.argv[2];
    const browser = await chromium.launch({headless: true});
    try {
        // 真实导航保留远端地址与同源关系，不拦截业务响应或关闭浏览器安全策略。
        const page = await browser.newPage();
        page.on('console', message => console.error(`[Chromium] ${message.text()}`));
        page.on('pageerror', error => console.error(error.stack || error));
        const response = await page.goto(target);
        console.error(`[Chromium] Triple same-origin document: ${response.status()} ${page.url()}`);
        await page.addScriptTag({content: bundle.outputFiles[0].text});

        // SDK 调用在 Chromium 页面中完成，Node 只负责浏览器生命周期和读取业务结果。
        const result = await page.evaluate(target => window.meteorTripleAlphaSave(target), target);
        console.log(JSON.stringify(result));
    } finally {
        await browser.close();
    }
}

main().catch(error => {
    console.error(error.stack || error);
    process.exitCode = 1;
});

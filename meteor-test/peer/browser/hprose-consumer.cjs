#!/usr/bin/env node
// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
'use strict';
const {chromium} = require('/opt/meteor-peer/node/grpcweb/node_modules/playwright');

async function main() {
    const target = new URL('/hprose', process.env.METEOR_ORDER_TARGET).href;
    const sdk = require.resolve('/opt/meteor-peer/node/hprose/node_modules/hprose-html5');
    const browser = await chromium.launch({headless: true});
    try {
        const page = await browser.newPage();
        page.on('console', message => console.error(`[Chromium] ${message.text()}`));
        page.on('pageerror', error => console.error(error.stack || error));

        // 真实访问同一 Meteor 监听端口，保留响应的远端 IP 和浏览器地址空间判定。
        // 未挂载的路径返回 404；该导航仍建立真实来源，不合成页面响应或拦截业务请求。
        const pageAddress = new URL('/meteor-test-hprose-page', target).href;
        await page.goto(pageAddress);
        const origin = await page.evaluate(() => location.origin);
        if (origin !== new URL(target).origin) {
            throw new Error(`Browser document origin ${origin} does not match the Meteor provider`);
        }
        await page.addScriptTag({path: sdk});

        // 在真实 Chromium 中使用官方 HTML5 客户端，不以 Node 客户端代替浏览器。
        const result = await page.evaluate(async target => {
            const orders = new hprose.HttpClient(target, ['orders_save', 'orders_find']);
            orders.timeout = 10_000;
            orders.retry = 0;
            const order = {
                id: 1001,
                description: 'Order from Hprose HTML5',
                attachment: new TextEncoder().encode('Meteor'),
            };

            // 保存并查询一次完整订单，附件由 SDK 处理为 Hprose 二进制字节。
            await orders.orders_save(order);
            const found = await orders.orders_find({id: order.id});
            return {
                id: found.id,
                description: found.description,
                attachment: btoa(String.fromCharCode(...found.attachment)),
            };
        }, target);
        console.log(JSON.stringify(result));
    } finally {
        await browser.close();
    }
}

main().catch(error => {
    console.error(error.stack || error);
    process.exitCode = 1;
});

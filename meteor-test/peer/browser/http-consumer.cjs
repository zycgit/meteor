#!/usr/bin/env node
// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
'use strict';
const {chromium} = require('/opt/meteor-peer/node/grpcweb/node_modules/playwright');

async function main() {
    const protocol = process.argv[2];
    const target = new URL(process.env.METEOR_ORDER_TARGET).origin;
    const browser = await chromium.launch({headless: true});
    try {
        const page = await browser.newPage();
        page.on('console', message => console.error(`[Chromium] ${message.text()}`));
        page.on('pageerror', error => console.error(error.stack || error));

        // 先真实访问同源端点，让 Chromium 记录服务器的网络地址空间；不伪造页面响应。
        let pageAddress = `${target}/${protocol}`;
        if (protocol === 'soap') pageAddress += '/orders?wsdl';
        if (protocol === 'connect') pageAddress += '/meteor.example.OrderDemoService/find';
        const documentResponse = await page.goto(pageAddress);
        console.error(`[Chromium] Same-origin document: ${documentResponse.status()} ${page.url()}`);

        // 编码、发送和解释响应全部在 Chromium 中执行，不借用 Node 的 HTTP 或 XML 能力。
        const result = await page.evaluate(async ({protocol, target}) => {
            const order = {id: 1001, description: 'Order from browser fetch', attachment: 'TWV0ZW9y'};
            const namespace = 'urn:hasor:meteor:soap:orders';
            async function call(method, value) {
                let address = `${target}/${protocol}`;
                let contentType = 'application/json';
                let body;
                const headers = {};
                if (protocol === 'jsonrpc') {
                    body = JSON.stringify({jsonrpc: '2.0', id: 1, method: `orders.${method}`, params: [value]});
                } else if (protocol === 'connect') {
                    address += `/meteor.example.OrderDemoService/${method}`;
                    headers['Connect-Protocol-Version'] = '1';
                    body = JSON.stringify(value);
                } else if (protocol === 'soap') {
                    address += '/orders';
                    contentType = 'text/xml; charset=utf-8';
                    headers.SOAPAction = `"${namespace}/${method}"`;
                    const fields = Object.entries(value).map(([key, text]) => `<${key}>${text}</${key}>`).join('');
                    body = '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">'
                        + `<s:Body><m:${method} xmlns:m="${namespace}"><arg0>${fields}</arg0>`
                        + `</m:${method}></s:Body></s:Envelope>`;
                } else if (protocol === 'xmlrpc') {
                    contentType = 'text/xml';
                    const fields = Object.entries(value).map(([key, text]) => {
                        let kind = 'string';
                        if (key === 'id') kind = 'int';
                        if (key === 'attachment') kind = 'base64';
                        return `<member><name>${key}</name><value><${kind}>${text}</${kind}></value></member>`;
                    }).join('');
                    body = `<methodCall><methodName>orders.${method}</methodName>`
                        + `<params><param><value><struct>${fields}</struct></value></param></params></methodCall>`;
                } else {
                    throw new Error(`Unsupported HTTP browser protocol: ${protocol}`);
                }

                // 同源模式会拒绝意外的跨域跳转，HTTP 和协议错误均直接失败。
                headers['Content-Type'] = contentType;
                const response = await fetch(address, {
                    method: 'POST', headers, body, mode: 'same-origin', signal: AbortSignal.timeout(15000),
                });
                const text = await response.text();
                if (!response.ok) throw new Error(`HTTP ${response.status}: ${text}`);
                if (protocol === 'jsonrpc') {
                    const envelope = JSON.parse(text);
                    if (envelope.error) throw new Error(JSON.stringify(envelope.error));
                    return envelope.result;
                }
                if (protocol === 'connect') return JSON.parse(text);

                const xml = new DOMParser().parseFromString(text, 'application/xml');
                if (xml.getElementsByTagName('parsererror').length) throw new Error(`Invalid XML: ${text}`);
                if (xml.getElementsByTagNameNS('*', 'Fault').length) throw new Error(`SOAP fault: ${text}`);
                if (xml.getElementsByTagName('fault').length) throw new Error(`XML-RPC fault: ${text}`);
                if (protocol === 'soap') {
                    const field = name => xml.getElementsByTagNameNS('*', name)[0].textContent;
                    return {
                        id: Number(field('id')),
                        description: field('description'),
                        attachment: field('attachment'),
                    };
                }
                const decoded = {};
                for (const member of xml.querySelectorAll('params > param > value > struct > member')) {
                    const key = member.querySelector('name').textContent;
                    const value = member.querySelector('value').textContent;
                    decoded[key] = key === 'id' ? Number(value) : value;
                }
                return decoded;
            }

            // 一次场景只验证一个协议的保存及查询，不探测其它能力或重试失败调用。
            await call('save', order);
            return await call('find', {id: order.id});
        }, {protocol, target});
        console.log(JSON.stringify(result));
    } finally {
        await browser.close();
    }
}

main().catch(error => {
    console.error(error.stack || error);
    process.exitCode = 1;
});

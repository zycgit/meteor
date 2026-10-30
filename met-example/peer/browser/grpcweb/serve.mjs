#!/usr/bin/env node
// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
import { createServer } from "node:http";
import { readFile } from "node:fs/promises";

// 静态服务器只发布页面和脚本；浏览器直接调用 Meteor。
const files = {
    "/": ["text/html; charset=utf-8", await readFile(new URL("index.html", import.meta.url))],
    "/consumer.js": ["text/javascript; charset=utf-8", await readFile(new URL("dist/consumer.js", import.meta.url))],
};
const server = createServer((request, response) => {
    const file = files[request.url];
    if (request.method !== "GET" || !file) {
        response.writeHead(404).end();
        return;
    }
    response.writeHead(200, { "content-type": file[0] });
    response.end(file[1]);
});
server.listen(8090, "127.0.0.1", () => {
    console.log("Browser example is ready: http://127.0.0.1:8090");
});

// 退出时释放静态服务器。
const close = () => {
    server.close();
    server.closeAllConnections();
};
process.once("SIGINT", close);
process.once("SIGTERM", close);

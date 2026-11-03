// Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0.
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { createServer } from "node:http";
import { resolve, join } from "node:path";
import { createInterface } from "node:readline";
import { fileURLToPath } from "node:url";
import { test } from "node:test";

const root = fileURLToPath(new URL(".", import.meta.url));
const request = { id: 2718, description: "标准订单🙂", attachment: "AAEAf/8=" };

function environment(context) {
    const directory = resolve(root, "../../build/tmp/node-nxn");
    mkdirSync(directory, { recursive: true });
    const workingDirectory = mkdtempSync(join(directory, "run-"));
    context.after(() => rmSync(workingDirectory, { recursive: true, force: true }));
    const file = join(workingDirectory, "request.json");
    writeFileSync(file, JSON.stringify(request));
    return { ...process.env, METEOR_NXN_REQUEST_FILE: file, METEOR_ORDER_PORT: "0" };
}

function consumer(env) {
    return new Promise((accept, reject) => {
        const child = spawn(process.execPath, [join(root, "jsonrpc/consumer.mjs")], { env });
        let output = "";
        let errors = "";
        child.stdout.on("data", (chunk) => output += chunk);
        child.stderr.on("data", (chunk) => errors += chunk);
        child.once("error", reject);
        child.once("close", (code) => {
            if (code !== 0) {
                reject(new Error(`Consumer failed (${code}): ${errors}`));
            } else {
                accept(JSON.parse(output.trim()));
            }
        });
    });
}

test("NXN JSON-RPC executes the shared Unicode and binary order", { timeout: 10000 }, async (context) => {
    const env = environment(context);
    const provider = spawn(process.execPath, [join(root, "jsonrpc/provider.mjs")], { env });
    context.after(() => provider.kill("SIGTERM"));
    const ready = await new Promise((accept, reject) => {
        const lines = createInterface({ input: provider.stdout });
        lines.once("line", (line) => accept(JSON.parse(line)));
        provider.once("error", reject);
        provider.once("exit", (code) => reject(new Error(`Provider exited before ready: ${code}`)));
    });
    assert.equal(ready.event, "ready");
    const result = await consumer({ ...env, METEOR_ORDER_TARGET: ready.target });
    assert.deepEqual(result, { event: "result", order: request, saved: request });
});

test("NXN JSON-RPC honors a provider mounted at the root", { timeout: 10000 }, async (context) => {
    const env = environment(context);
    let saved;
    const paths = [];
    const server = createServer(async (incoming, response) => {
        paths.push(incoming.url);
        const chunks = [];
        for await (const chunk of incoming) {
            chunks.push(chunk);
        }
        const call = JSON.parse(Buffer.concat(chunks).toString("utf8"));
        if (call.method === "orders.save") {
            saved = call.params[0];
        }
        response.writeHead(200, { "content-type": "application/json" });
        response.end(JSON.stringify({ jsonrpc: "2.0", id: call.id, result: saved }));
    });
    context.after(() => { server.closeAllConnections(); server.close(); });
    await new Promise((accept) => server.listen(0, "127.0.0.1", accept));
    const target = `http://127.0.0.1:${server.address().port}/`;
    const result = await consumer({ ...env, METEOR_ORDER_TARGET: target });
    assert.deepEqual(paths, ["/", "/"]);
    assert.deepEqual(result.order, request);
});

test("NXN consumer rejects a changed save response", { timeout: 10000 }, async (context) => {
    const env = environment(context);
    const server = createServer(async (incoming, response) => {
        const chunks = [];
        for await (const chunk of incoming) {
            chunks.push(chunk);
        }
        const call = JSON.parse(Buffer.concat(chunks).toString("utf8"));
        const result = { ...request };
        if (call.method === "orders.save") {
            result.description = "unexpected";
        }
        response.writeHead(200, { "content-type": "application/json" });
        response.end(JSON.stringify({ jsonrpc: "2.0", id: call.id, result }));
    });
    context.after(() => { server.closeAllConnections(); server.close(); });
    await new Promise((accept) => server.listen(0, "127.0.0.1", accept));
    const target = `http://127.0.0.1:${server.address().port}/jsonrpc`;
    await assert.rejects(consumer({ ...env, METEOR_ORDER_TARGET: target }), /save returned a different order/);
});

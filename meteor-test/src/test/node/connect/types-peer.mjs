/* Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0. */
import { readFileSync } from "node:fs";
import { createServer, Agent } from "node:http";
import { createInterface } from "node:readline";
import { createClient } from "@connectrpc/connect";
import { connectNodeAdapter, createConnectTransport } from "@connectrpc/connect-node";
import { TypeEchoService, acceptValue, plainValue, restoreValue } from "./types-codec.mjs";

const [role, format] = process.argv.slice(2);
const controls = createInterface({ input: process.stdin });
const report = value => console.log(`RESULT ${JSON.stringify(value)}`);

function vector(name) {
  const table = JSON.parse(readFileSync(process.env.METEOR_TYPE_VECTORS, "utf8"));
  const selected = table.vectors[name];
  if (!selected) {
    throw new Error(`Unknown type vector: ${name}`);
  }
  return { ...table.defaults, [selected.field]: selected.value, caseId: name, receipt: `client:${name}` };
}

async function consume() {
  const agent = new Agent({ keepAlive: true });
  console.log(`READY ${JSON.stringify({ role: "consumer" })}`);
  try {
    // The official SDK owns the full HTTP/Connect request; the control pipe only supplies one fixture.
    for await (const line of controls) {
      const command = JSON.parse(line);
      const transport = createConnectTransport({
        baseUrl: command.target,
        httpVersion: "1.1",
        nodeOptions: { agent },
        useBinaryFormat: format === "proto",
        defaultTimeoutMs: 10000,
      });
      const client = createClient(TypeEchoService, transport);
      const response = await client.echo(restoreValue(vector(command.vector)));
      report({ value: plainValue(response) });
    }
  } finally {
    agent.destroy();
  }
}

async function provide() {
  // The official Connect router binds its generated schema to an ordinary native JavaScript service.
  const handler = connectNodeAdapter({
    requestPathPrefix: "/types",
    routes(router) {
      router.service(TypeEchoService, { echo: acceptValue });
    },
  });
  const server = createServer(handler);
  const sockets = new Set();
  server.on("connection", socket => {
    sockets.add(socket);
    socket.on("close", () => sockets.delete(socket));
  });
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "0.0.0.0", resolve);
  });
  console.log(`READY ${JSON.stringify({ role: "provider", port: server.address().port })}`);

  // The process owns this listener and all accepted sockets, and releases them when its owner closes stdin.
  await new Promise(resolve => controls.once("close", resolve));
  server.close();
  for (const socket of sockets) {
    socket.destroy();
  }
}

if (role === "consumer") {
  await consume();
} else if (role === "provider") {
  await provide();
} else {
  throw new Error(`Unknown type peer role: ${role}`);
}

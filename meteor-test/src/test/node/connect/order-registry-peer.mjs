/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
import { deepStrictEqual } from "node:assert";
import { Agent, createServer } from "node:http";
import { createInterface } from "node:readline";
import { Code, ConnectError, createClient } from "@connectrpc/connect";
import { connectNodeAdapter, createConnectTransport } from "@connectrpc/connect-node";
import { OrderDemoService } from "./generated/order_pb.js";

const role = process.argv[2];
const controls = createInterface({ input: process.stdin });

function report(message) {
  process.stdout.write(`${JSON.stringify(message)}\n`);
}

function plain(order) {
  return {
    id: order.id,
    description: order.description,
    attachment: Buffer.from(order.attachment).toString("base64"),
  };
}

async function consume() {
  const agent = new Agent({ keepAlive: true });
  try {
    // 消费者启动时没有业务地址；地址只能由真实注册订阅的 resolver 经控制管道提供。
    report({ ready: true, role: "consumer", discovery: "adapter" });
    for await (const line of controls) {
      const command = JSON.parse(line);
      if (command.operation === "quit") {
        controls.close();
        process.stdin.destroy();
        return;
      }

      try {
        if (command.operation !== "roundtrip") {
          throw new Error(`Unknown operation: ${command.operation}`);
        }
        if (!Array.isArray(command.endpoints) || command.endpoints.length !== 1) {
          throw new Error("Exactly one resolved provider is required");
        }

        // 官方 SDK 直接向发现的 HTTP context 发出 save/find；Java 控制进程不代理业务请求。
        const transport = createConnectTransport({
          baseUrl: command.endpoints[0],
          httpVersion: "1.1",
          nodeOptions: { agent },
          useBinaryFormat: true,
          defaultTimeoutMs: 5000,
        });
        const orders = createClient(OrderDemoService, transport);
        const input = {
          id: command.order.id,
          description: command.order.description,
          attachment: Buffer.from(command.order.attachment, "base64"),
        };
        const saved = await orders.save(input);
        const found = await orders.find({ id: saved.id });
        deepStrictEqual(plain(saved), plain(input));
        deepStrictEqual(plain(found), plain(input));
        report({ result: plain(found) });
      } catch (failure) {
        report({ error: { message: failure.message, code: failure.code } });
      }
    }
  } finally {
    // 当前进程创建并持有 HTTP Agent，在控制通道结束后主动释放复用连接。
    agent.destroy();
  }
}

async function provide() {
  // 复用统一订单契约，只有官方 Connect Node SDK 承担协议解析与响应编码。
  const records = new Map();
  const handler = connectNodeAdapter({
    requestPathPrefix: "/connect",
    routes(router) {
      router.service(OrderDemoService, {
        save(order) {
          const copy = { ...order, attachment: new Uint8Array(order.attachment) };
          records.set(order.id, copy);
          return copy;
        },
        find(query) {
          const order = records.get(query.id);
          if (order === undefined) {
            throw new ConnectError(`Order not found: ${query.id}`, Code.NotFound);
          }
          return { ...order, attachment: new Uint8Array(order.attachment) };
        },
      });
    },
  });
  const server = createServer(handler);
  const sockets = new Set();
  server.on("connection", (socket) => {
    sockets.add(socket);
    socket.on("close", () => sockets.delete(socket));
  });
  server.listen(0, "127.0.0.1", () => {
    report({ ready: true, role: "provider", port: server.address().port, discovery: "adapter" });
  });

  // 所有者只在实际监听成功后登记，关闭控制管道时结束独占监听和现存连接。
  let stopping = false;
  const stop = () => {
    if (stopping) {
      return;
    }
    stopping = true;
    server.close();
    for (const socket of sockets) {
      socket.destroy();
    }
  };
  controls.on("line", (line) => {
    const command = JSON.parse(line);
    if (command.operation === "quit") {
      controls.close();
      process.stdin.destroy();
    }
  });
  controls.on("close", stop);
}

if (role === "consumer") {
  await consume();
} else if (role === "provider") {
  await provide();
} else {
  throw new Error(`Unknown registry peer role: ${role}`);
}

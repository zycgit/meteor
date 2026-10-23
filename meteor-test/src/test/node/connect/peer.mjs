/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
import { createServer as createHttp1Server } from "node:http";
import { createServer as createHttp2Server } from "node:http2";
import { createInterface } from "node:readline";
import { ConnectError, Code, createClient } from "@connectrpc/connect";
import { connectNodeAdapter, createConnectTransport, compressionGzip } from "@connectrpc/connect-node";
import { ConnectDemoService, EchoReplySchema } from "./generated/connect-demo_pb.js";

const [mode, ...argumentsList] = process.argv.slice(2);
const options = Object.fromEntries(argumentsList.map((value) => {
  const separator = value.indexOf("=");
  return [value.slice(0, separator), value.slice(separator + 1)];
}));

function metadata(context) {
  // 原样回送请求元数据，并独立发布初始元数据和结束元数据。
  const requestValue = context.requestHeader.get("x-connect-test-request");
  if (requestValue !== null) {
    context.responseHeader.set("x-connect-test-request", requestValue);
  }
  context.responseHeader.append("x-connect-test-repeat", "first");
  context.responseHeader.append("x-connect-test-repeat", "second");
  context.responseTrailer.set("x-connect-test-trailer", "finished");
  context.responseTrailer.set("x-connect-test-trailer-bin", "AAH/");
}

function response(request) {
  return { text: request.text, number: request.number + 1 };
}

async function serve() {
  // 使用官方 Connect Node 路由，独立于 Meteor 的任何协议代码。
  const handler = connectNodeAdapter({
    requestPathPrefix: "/connect",
    acceptCompression: [compressionGzip],
    compressMinBytes: 0,
    routes(router) {
      router.service(ConnectDemoService, {
        echo(request, context) {
          metadata(context);
          return response(request);
        },
        safeEcho(request, context) {
          metadata(context);
          context.responseHeader.set("x-connect-test-http-method", context.requestMethod);
          return response(request);
        },
        async *count(request, context) {
          metadata(context);
          for (let number = 0; number < request.number; number++) {
            yield { text: request.text, number };
          }
        },
        async collect(requests, context) {
          metadata(context);
          let number = 0;
          for await (const request of requests) {
            number += request.number;
          }
          return { text: "sum", number };
        },
        async *chat(requests, context) {
          metadata(context);
          for await (const request of requests) {
            yield { text: request.text, number: request.number };
          }
        },
        async *broken(request, context) {
          metadata(context);
          yield { text: request.text, number: request.number };
          throw new ConnectError("stream failure 中文", Code.ResourceExhausted, undefined, [
            { desc: EchoReplySchema, value: { text: "stream detail 中文", number: 9 } },
          ]);
        },
        fail(request, context) {
          metadata(context);
          throw new ConnectError("connect failure 中文", Code.InvalidArgument, undefined, [
            { desc: EchoReplySchema, value: { text: "failure detail 中文", number: 9 } },
          ]);
        },
      });
    },
  });
  const server = options["--http-version"] === "1.1" ? createHttp1Server(handler) : createHttp2Server(handler);
  server.listen(0, "127.0.0.1", () => console.log(`READY ${server.address().port}`));

  // 进程所有者关闭 stdin 时停止接入并销毁现存的独占测试连接。
  const sockets = new Set();
  server.on("connection", (socket) => {
    sockets.add(socket);
    socket.on("close", () => sockets.delete(socket));
  });
  createInterface({ input: process.stdin }).on("close", () => {
    server.close();
    for (const socket of sockets) {
      socket.destroy();
    }
  });
}

function plain(message) {
  return { text: message.text, number: message.number };
}

async function* requests() {
  yield { text: "first", number: 2 };
  yield { text: "中文", number: 3 };
  yield { text: "last", number: 5 };
}

async function call() {
  const gzip = options["--gzip"] === "true";
  const scenario = options["--scenario"];
  const transport = createConnectTransport({
    baseUrl: options["--url"],
    httpVersion: options["--http-version"] ?? "2",
    useBinaryFormat: options["--format"] !== "json",
    useHttpGet: options["--get"] === "true",
    defaultTimeoutMs: 5000,
    acceptCompression: [compressionGzip],
    sendCompression: gzip ? compressionGzip : undefined,
    compressMinBytes: 0,
  });
  const client = createClient(ConnectDemoService, transport);
  let header = {};
  let trailer = {};
  const callOptions = {
    headers: { "x-connect-test-request": "request-value", "x-connect-test-request-bin": "AAH/" },
    onHeader(value) { header = Object.fromEntries(value.entries()); },
    onTrailer(value) { trailer = Object.fromEntries(value.entries()); },
  };

  // 每次进程仅执行一个独立场景，Java 测试分别断言调用方向与消息形态。
  let result;
  try {
    if (scenario === "echo" || scenario === "metadata") {
      result = plain(await client.echo({ text: "Connect 中文 🙂", number: 41 }, callOptions));
    } else if (scenario === "safeEcho") {
      result = plain(await client.safeEcho({ text: "Connect GET 中文 🙂", number: 41 }, callOptions));
    } else if (scenario === "count") {
      result = [];
      for await (const message of client.count({ text: "Connect 中文 🙂", number: 3 }, callOptions)) {
        result.push(plain(message));
      }
    } else if (scenario === "collect") {
      result = plain(await client.collect(requests(), callOptions));
    } else if (scenario === "chat") {
      result = [];
      for await (const message of client.chat(requests(), callOptions)) {
        result.push(plain(message));
      }
    } else if (scenario === "broken") {
      result = [];
      for await (const message of client.broken({ text: "first", number: 1 }, callOptions)) {
        result.push(plain(message));
      }
    } else if (scenario === "fail") {
      result = plain(await client.fail({ text: "failure", number: 0 }, callOptions));
    } else {
      throw new Error(`Unknown peer scenario: ${scenario}`);
    }
    console.log(`RESULT ${JSON.stringify({ result, header, trailer })}`);
  } catch (error) {
    if (!(error instanceof ConnectError)) {
      throw error;
    }
    const details = error.findDetails(EchoReplySchema).map(plain);
    const failure = { code: error.code, message: error.rawMessage, details, metadata: Object.fromEntries(error.metadata) };
    console.log(`RESULT ${JSON.stringify({ error: failure, result, header, trailer })}`);
  }
}

if (mode === "server") {
  await serve();
} else if (mode === "client") {
  await call();
} else {
  throw new Error(`Unknown peer mode: ${mode}`);
}

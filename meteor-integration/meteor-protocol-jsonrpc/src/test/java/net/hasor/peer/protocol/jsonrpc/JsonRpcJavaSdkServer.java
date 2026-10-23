/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.jsonrpc;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import com.googlecode.jsonrpc4j.JsonRpcMultiServer;
import com.sun.net.httpserver.HttpServer;

/** 测试持有的 jsonrpc4j 服务，别名、反射分派和 JSON-RPC 编解码均由 SDK 完成。 */
public final class JsonRpcJavaSdkServer implements AutoCloseable {
    private final HttpServer server;

    private JsonRpcJavaSdkServer() throws IOException {
        JsonRpcMultiServer rpc = new JsonRpcMultiServer();
        rpc.addService("demo", new JsonRpcJavaSdkService());
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            this.server.createContext("/rpc", exchange -> {
                try (exchange) {
                    ByteArrayOutputStream response = new ByteArrayOutputStream();
                    rpc.handleRequest(exchange.getRequestBody(), response);
                    byte[] body = response.toByteArray();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            });
            this.server.start();
        } catch (RuntimeException | Error failure) {
            this.server.stop(0);
            throw failure;
        }
    }

    public static JsonRpcJavaSdkServer start() throws IOException {
        return new JsonRpcJavaSdkServer();
    }

    public int getPort() {
        return this.server.getAddress().getPort();
    }

    @Override
    public void close() {
        this.server.stop(0);
    }
}

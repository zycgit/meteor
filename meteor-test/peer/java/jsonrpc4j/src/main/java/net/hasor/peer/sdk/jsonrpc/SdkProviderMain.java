/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.sdk.jsonrpc;
import java.io.ByteArrayOutputStream;
import com.googlecode.jsonrpc4j.JsonRpcMultiServer;
import com.sun.net.httpserver.HttpServer;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderExampleUtils;
import net.hasor.peer.sdk.OrderPeerSettings;

/** 使用 jsonrpc4j 发布订单服务，供 Meteor 消费者调用。 */
public final class SdkProviderMain {
    private SdkProviderMain() {
    }

    public static void main(String[] arguments) throws Exception {
        // SDK 负责方法分派和 JSON-RPC 编解码，共享实现保存订单数据。
        JsonRpcMultiServer rpc = new JsonRpcMultiServer();
        rpc.addService("orders", new OrderDemoServiceImpl());
        HttpServer server = HttpServer.create(OrderPeerSettings.bindAddress(), 0);
        try {
            // JDK HTTP 容器只传递正文，协议处理全部交给 jsonrpc4j。
            server.createContext("/jsonrpc", exchange -> {
                try (exchange) {
                    ByteArrayOutputStream response = new ByteArrayOutputStream();
                    rpc.handleRequest(exchange.getRequestBody(), response);
                    byte[] body = response.toByteArray();
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            });
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0), "jsonrpc-sdk-shutdown"));
            server.start();
            System.out.println("SDK order provider is ready: "
                    + OrderPeerSettings.publishAddress("http", server.getAddress().getPort(), "/jsonrpc"));

            // 当前进程拥有 HTTP 服务，退出时释放监听与连接。
            OrderExampleUtils.awaitShutdown();
        } finally {
            server.stop(0);
        }
    }
}

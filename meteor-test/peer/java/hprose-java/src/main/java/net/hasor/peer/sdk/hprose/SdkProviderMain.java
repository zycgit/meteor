/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.sdk.hprose;
import java.io.IOException;
import java.nio.ByteBuffer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hprose.server.HproseService;
import hprose.util.concurrent.Threads;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderExampleUtils;
import net.hasor.peer.sdk.OrderPeerSettings;

/** 使用 Hprose Java SDK 发布订单，供 Meteor 消费者调用。 */
public final class SdkProviderMain extends HproseService implements AutoCloseable {
    private final HttpServer server;

    private SdkProviderMain() throws IOException {
        // SDK 注册普通业务实现，HTTP 监听仅负责转交正文。
        this.add(new OrderDemoServiceImpl(), OrderDemoService.class, "orders");
        this.server = HttpServer.create(OrderPeerSettings.bindAddress(), 0);
        this.server.createContext("/hprose", this::handleHttp);
    }

    public static void main(String[] arguments) throws Exception {
        // 独立 SDK 进程持有监听及线程；退出时关闭自己创建的资源。
        try (SdkProviderMain provider = new SdkProviderMain()) {
            Runtime.getRuntime().addShutdownHook(new Thread(provider::close, "hprose-sdk-shutdown"));
            provider.server.start();
            int actualPort = provider.server.getAddress().getPort();
            String target = OrderPeerSettings.publishAddress("http", actualPort, "/hprose").toString();
            OrderExampleUtils.ready(target, "SDK order provider is ready: ");
            OrderExampleUtils.awaitShutdown();
        }
    }

    private void handleHttp(HttpExchange exchange) throws IOException {
        try (exchange) {
            // 函数名、参数、DTO 和结果均交给 Hprose 引擎编解码与分派。
            byte[] request = exchange.getRequestBody().readAllBytes();
            ByteBuffer response;
            try {
                SdkServiceContext context = new SdkServiceContext(this);
                response = this.handle(ByteBuffer.wrap(request), context).toFuture().get();
            } catch (Exception failure) {
                failure.printStackTrace(System.err);
                throw new IOException("Hprose SDK invocation failed", failure);
            }

            // HTTP 层发送 SDK 已经生成的完整响应。
            byte[] body = new byte[response.remaining()];
            response.get(body);
            exchange.getResponseHeaders().set("Content-Type", "application/hprose");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    @Override
    public void close() {
        this.server.stop(0);
        // SDK 的静态线程池只服务于当前独立 Demo 进程。
        Threads.runShutdownHandler();
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.sdk.hessian;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import com.caucho.hessian.io.SerializerFactory;
import com.caucho.hessian.server.HessianSkeleton;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderExampleUtils;
import net.hasor.peer.sdk.OrderPeerSettings;

/** 使用 Caucho Hessian SDK 发布订单，供 Meteor 消费者调用。 */
public final class SdkProviderMain implements AutoCloseable {
    private final HttpServer server;
    private final HessianSkeleton skeleton;
    private final SerializerFactory serializers;

    private SdkProviderMain() throws IOException {
        // Skeleton 负责 Hessian 调用解析、Java 接口分派以及结果编码。
        this.skeleton = new HessianSkeleton(new OrderDemoServiceImpl(), OrderDemoService.class);
        this.serializers = new SerializerFactory(OrderDemoService.class.getClassLoader());
        this.server = HttpServer.create(OrderPeerSettings.bindAddress(), 0);
        this.server.createContext("/hessian/orders", this::handleHttp);
    }

    public static void main(String[] arguments) throws Exception {
        // 启动 SDK 服务，Ctrl+C 时关闭监听。
        try (SdkProviderMain provider = new SdkProviderMain()) {
            Runtime.getRuntime().addShutdownHook(new Thread(provider::close, "hessian-sdk-shutdown"));
            provider.server.start();
            int actualPort = provider.server.getAddress().getPort();
            String target = OrderPeerSettings.publishAddress("http", actualPort, "/hessian/orders").toString();
            OrderExampleUtils.ready(target, "SDK order provider is ready: ");
            OrderExampleUtils.awaitShutdown();
        }
    }

    private void handleHttp(HttpExchange exchange) throws IOException {
        try (exchange) {
            // HTTP 壳只传递正文，正常结果与业务异常均由 Skeleton 写入。
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try {
                this.skeleton.invoke(exchange.getRequestBody(), output, this.serializers);
            } catch (Exception failure) {
                throw new IOException("Hessian SDK invocation failed", failure);
            }

            // 完整响应由 SDK 生成后，再填写 HTTP 响应头并发送。
            byte[] body = output.toByteArray();
            exchange.getResponseHeaders().set("Content-Type", "application/x-hessian");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    @Override
    public void close() {
        this.server.stop(0);
    }
}

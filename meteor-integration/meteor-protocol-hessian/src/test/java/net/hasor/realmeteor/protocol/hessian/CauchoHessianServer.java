/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.hessian;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.caucho.hessian.io.SerializerFactory;
import com.caucho.hessian.server.HessianSkeleton;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.hasor.example.protocol.hessian.HessianInteropDemo;

/** 独立 HTTP 对端只用 Caucho Skeleton 解码与调度，不依赖 Meteor 协议实现。 */
final class CauchoHessianServer implements AutoCloseable {
    private final HttpServer        server;
    private final ExecutorService   executor;
    private final HessianSkeleton   skeleton;
    private final SerializerFactory serializers;

    CauchoHessianServer(HessianInteropDemo implementation) throws IOException {
        // Skeleton 自行识别 Hessian 1、1 请求/2 响应和 Hessian 2 调用信封。
        this.skeleton = new HessianSkeleton(implementation, HessianInteropDemo.class);
        this.serializers = new SerializerFactory(HessianInteropDemo.class.getClassLoader());
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.executor = Executors.newFixedThreadPool(2);
        this.server.setExecutor(this.executor);
        this.server.createContext("/hessian/demo", this::invoke);
        this.server.start();
    }

    int getPort() {
        return this.server.getAddress().getPort();
    }

    private void invoke(HttpExchange request) throws IOException {
        try (request) {
            // 业务调用与 fault 均由官方实现完成。
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try {
                this.skeleton.invoke(request.getRequestBody(), output, this.serializers);
            } catch (Exception failure) {
                request.sendResponseHeaders(500, -1);
                return;
            }

            // 真实服务器的响应只包含官方 Skeleton 生成的 Hessian 二进制。
            byte[] body = output.toByteArray();
            request.getResponseHeaders().set("Content-Type", "x-application/hessian");
            request.sendResponseHeaders(200, body.length);
            request.getResponseBody().write(body);
        }
    }

    @Override
    public void close() throws InterruptedException {
        // 本夹具只关闭自行创建的 HTTP 监听和执行器。
        this.server.stop(0);
        this.executor.shutdownNow();
        if (!this.executor.awaitTermination(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Caucho HTTP server did not stop");
        }
    }
}

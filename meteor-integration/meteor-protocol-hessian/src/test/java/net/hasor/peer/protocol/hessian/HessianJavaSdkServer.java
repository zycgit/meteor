/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.hessian;
import java.io.IOException;
import java.net.InetSocketAddress;
import com.caucho.hessian.server.HessianSkeleton;
import com.sun.net.httpserver.HttpServer;
import net.hasor.example.protocol.hessian.HessianDemoService;
import net.hasor.example.protocol.hessian.HessianDemoServiceImpl;

/** 测试持有的 Caucho 服务，JDK HTTP 只转交字节，Skeleton 负责完整协议处理。 */
public final class HessianJavaSdkServer implements AutoCloseable {
    private final HttpServer server;

    private HessianJavaSdkServer() throws IOException {
        HessianDemoService implementation = new HessianDemoServiceImpl() {
            @Override
            public String echo(String value) {
                return "sdk-server:" + value;
            }
        };
        HessianSkeleton skeleton = new HessianSkeleton(implementation, HessianDemoService.class);
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            this.server.createContext("/rpc/demo", exchange -> {
                try (exchange) {
                    exchange.getResponseHeaders().set("Content-Type", "application/x-hessian");
                    exchange.sendResponseHeaders(200, 0);
                    try {
                        skeleton.invoke(exchange.getRequestBody(), exchange.getResponseBody());
                    } catch (Exception failure) {
                        throw new IOException("Caucho Hessian server failed", failure);
                    }
                }
            });
            this.server.start();
        } catch (RuntimeException | Error failure) {
            this.server.stop(0);
            throw failure;
        }
    }

    public static HessianJavaSdkServer start() throws IOException {
        return new HessianJavaSdkServer();
    }

    public int getPort() {
        return this.server.getAddress().getPort();
    }

    @Override
    public void close() {
        this.server.stop(0);
    }
}

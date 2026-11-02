/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.hprose;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import com.sun.net.httpserver.HttpServer;
import hprose.server.HproseService;
import net.hasor.example.protocol.hprose.Echo;
import net.hasor.example.protocol.hprose.EchoService;

/** 测试持有的官方 Hprose 服务引擎，HTTP 层不读取或构造协议消息。 */
public final class HproseJavaSdkServer extends HproseService implements AutoCloseable {
    private final HttpServer server;

    private HproseJavaSdkServer() throws IOException {
        Echo implementation = new EchoService() {
            @Override
            public String echo(String value) {
                return "sdk-server:" + value;
            }
        };
        this.add(implementation, Echo.class, "demo");
        this.setErrorDelay(0);
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            this.server.createContext("/rpc", exchange -> {
                try (exchange) {
                    ByteBuffer request = ByteBuffer.wrap(exchange.getRequestBody().readAllBytes());
                    HproseJavaSdkContext context = new HproseJavaSdkContext(this);
                    ByteBuffer response;
                    try {
                        response = this.handle(request, context).toFuture().get(10, TimeUnit.SECONDS);
                    } catch (Exception failure) {
                        throw new IOException("Official Hprose server failed", failure);
                    }
                    byte[] body = new byte[response.remaining()];
                    response.get(body);
                    exchange.getResponseHeaders().set("Content-Type", "application/hprose");
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

    public static HproseJavaSdkServer start() throws IOException {
        return new HproseJavaSdkServer();
    }

    public int getPort() {
        return this.server.getAddress().getPort();
    }

    @Override
    public void close() {
        // SDK 静态线程池由同一 JVM 共享，不能在关闭当前监听时全局停用。
        this.server.stop(0);
    }
}

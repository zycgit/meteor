/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.hprose;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hprose.client.HproseHttpClient;
import hprose.server.HproseService;
import hprose.util.concurrent.Threads;
import net.hasor.example.order.Order;
import net.hasor.example.order.OrderDemoService;
import net.hasor.example.order.OrderDemoServiceImpl;
import net.hasor.example.order.OrderQuery;

/** 独立官方 Java SDK 对端；JDK HTTP 壳只传正文，官方引擎负责 Hprose 的完整收发与分派。 */
public final class HproseOrderPeer extends HproseService {
    private HproseOrderPeer() {
        this.add(new OrderDemoServiceImpl(), OrderDemoService.class, "orders");
        this.setErrorDelay(0);
    }

    public static void main(String[] arguments) throws Exception {
        // 对端只加载普通订单合同和官方 SDK，不能回退到 Meteor 的协议实现。
        requireIsolatedClasspath();
        try {
            switch (arguments[0]) {
                case "provider":
                    new HproseOrderPeer().serve();
                    break;
                case "consumer":
                    consume();
                    break;
                default:
                    throw new IllegalArgumentException("Unknown Hprose peer role: " + arguments[0]);
            }
        } finally {
            // SDK 静态线程池属于此独立进程，在控制管道关闭后统一释放。
            Threads.runShutdownHandler();
        }
    }

    private void serve() throws Exception {
        // HTTP 监听随机端口；业务方法及 DTO 始终由官方 HproseService 处理。
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            server.setExecutor(executor);
            server.createContext("/orders", this::exchange);
            server.start();
            System.out.println("READY " + server.getAddress().getPort());
            System.out.flush();

            // 注册适配器只在收到 READY 后登记；stdin EOF 结束此服务进程。
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            while (input.readLine() != null) {
                // 控制管道关闭前维持监听，业务请求只走 HTTP。
            }
        } finally {
            server.stop(0);
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private void exchange(HttpExchange exchange) {
        try (exchange) {
            // HTTP 壳不读取 Hprose 方法名，也不进行业务分派或自行构造协议响应。
            byte[] request = exchange.getRequestBody().readAllBytes();
            HproseOrderContext context = new HproseOrderContext(this);
            ByteBuffer response = this.handle(ByteBuffer.wrap(request), context) //
                    .toFuture().get(10, TimeUnit.SECONDS);
            byte[] body = new byte[response.remaining()];
            response.get(body);
            exchange.getResponseHeaders().set("Content-Type", "application/hprose");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } catch (Exception failure) {
            // 原生引擎或 HTTP 故障留在独立进程日志，调用方仍会收到真实传输失败。
            failure.printStackTrace(System.err);
        }
    }

    private static void consume() throws Exception {
        // 启动不接收业务 URL；地址只能由外部 StorageAccess 订阅结果经控制管道送入。
        System.out.println("READY 0");
        System.out.flush();
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String command;
        while ((command = input.readLine()) != null) {
            byte[] bytes = Base64.getDecoder().decode(command);
            Object[] request;
            try (ObjectInputStream stream = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
                request = (Object[]) stream.readObject();
            }

            // save/find 的网络连接与协议读写完全由官方 Java 客户端执行。
            HproseHttpClient client = new HproseHttpClient((String) request[0]);
            try {
                client.setTimeout(10000);
                client.setRetry(0);
                OrderDemoService orders = client.useService(OrderDemoService.class, "orders");
                Order expected = (Order) request[1];
                Order saved = orders.save(expected);
                OrderQuery query = new OrderQuery();
                query.setId(expected.getId());
                Order found = orders.find(query);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (ObjectOutputStream stream = new ObjectOutputStream(output)) {
                    stream.writeObject(new Order[] { saved, found });
                }
                String result = Base64.getEncoder().encodeToString(output.toByteArray());
                System.out.println("RESULT " + result);
                System.out.flush();
            } finally {
                client.close();
            }
        }
    }

    private static void requireIsolatedClasspath() throws ClassNotFoundException {
        try {
            Class.forName("net.hasor.meteor.MetContext", false, HproseOrderPeer.class.getClassLoader());
        } catch (ClassNotFoundException expected) {
            return;
        }
        throw new IllegalStateException("Official Hprose peer must not load Meteor runtime");
    }
}

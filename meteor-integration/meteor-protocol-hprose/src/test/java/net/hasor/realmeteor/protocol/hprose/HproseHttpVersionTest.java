/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.hprose;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import net.hasor.example.protocol.hprose.TextService;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HproseHttpVersionTest {
    @Test(timeout = 15000)
    public void http1CallsTheMountedService() throws Exception {
        // 在同一个 HTTP 端口的 /api 挂载 Hprose。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("hprose");
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("hprose") //
                .contextPath("/api");
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布接口，以真实服务验证协议挂载。
            server.start();
            server.publisher() //
                    .rsfService(TextService.class, value -> value) //
                    .aliasName("Hprose", "echo") //
                    .register();

            int port = server.bindAddress("hprose").getPort();
            URI uri = URI.create("http://127.0.0.1:" + port + "/api");

            // 限定 HTTP/1.1，避免客户端自动升级掩盖该版本的行为。
            HttpClient.Version version = HttpClient.Version.HTTP_1_1;
            HttpClient client = HttpClient.newBuilder().version(version).build();
            String call = "Cs9\"echo_echo\"a1{s5\"hello\"}z";
            var body = HttpRequest.BodyPublishers.ofString(call, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(uri).POST(body).build();

            // 发送 Hprose 调用报文，限定响应等待时间。
            var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            try {
                HttpResponse<String> response = pending.get(5, TimeUnit.SECONDS);
                assertEquals(200, response.statusCode());
                assertEquals(version, response.version());
                assertEquals("Rs5\"hello\"z", response.body());
            } finally {
                // 断言失败时也停止仍在等待的请求。
                pending.cancel(true);
            }
        }
    }

    @Test(timeout = 15000)
    public void http1ReturnsTheFunctionList() throws Exception {
        // 在同一个 HTTP 端口的 /api 挂载 Hprose。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("hprose");
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("hprose") //
                .contextPath("/api");
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布接口，以真实服务验证协议挂载。
            server.start();
            server.publisher() //
                    .rsfService(TextService.class, value -> value) //
                    .aliasName("Hprose", "echo") //
                    .register();

            int port = server.bindAddress("hprose").getPort();
            URI uri = URI.create("http://127.0.0.1:" + port + "/api");

            // 限定 HTTP/1.1，避免客户端自动升级掩盖该版本的行为。
            HttpClient.Version version = HttpClient.Version.HTTP_1_1;
            HttpClient client = HttpClient.newBuilder().version(version).build();
            var listBody = HttpRequest.BodyPublishers.ofString("z");
            HttpRequest list = HttpRequest.newBuilder(uri).POST(listBody).build();

            // 请求函数列表，验证挂载服务的方法已通过协议公开。
            var pending = client.sendAsync(list, HttpResponse.BodyHandlers.ofString());
            try {
                HttpResponse<String> response = pending.get(5, TimeUnit.SECONDS);
                assertEquals(200, response.statusCode());
                assertEquals(version, response.version());
                assertTrue(response.body().contains("echo_echo"));
            } finally {
                // 断言失败时也停止仍在等待的请求。
                pending.cancel(true);
            }
        }
    }

    @Test(timeout = 15000)
    public void http1RejectsAnUnknownPath() throws Exception {
        // 在同一个 HTTP 端口的 /api 挂载 Hprose。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("hprose");
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("hprose") //
                .contextPath("/api");
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布接口，以真实服务验证协议挂载。
            server.start();
            server.publisher() //
                    .rsfService(TextService.class, value -> value) //
                    .aliasName("Hprose", "echo") //
                    .register();

            int port = server.bindAddress("hprose").getPort();

            // 限定 HTTP/1.1，避免客户端自动升级掩盖该版本的行为。
            HttpClient.Version version = HttpClient.Version.HTTP_1_1;
            HttpClient client = HttpClient.newBuilder().version(version).build();
            URI missing = URI.create("http://127.0.0.1:" + port + "/missing");
            HttpRequest unknown = HttpRequest.newBuilder(missing).GET().build();

            // 请求未挂载的路径，确认传输层返回 404。
            var pending = client.sendAsync(unknown, HttpResponse.BodyHandlers.discarding());
            try {
                HttpResponse<Void> response = pending.get(5, TimeUnit.SECONDS);
                assertEquals(404, response.statusCode());
                assertEquals(version, response.version());
            } finally {
                // 断言失败时也停止仍在等待的请求。
                pending.cancel(true);
            }
        }
    }

    @Test(timeout = 15000)
    public void h2cUpgradeCallsTheMountedService() throws Exception {
        // 在同一个 HTTP 端口的 /api 挂载 Hprose。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("hprose");
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("hprose") //
                .contextPath("/api");
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布接口，以真实服务验证协议挂载。
            server.start();
            server.publisher() //
                    .rsfService(TextService.class, value -> value) //
                    .aliasName("Hprose", "echo") //
                    .register();

            int port = server.bindAddress("hprose").getPort();
            URI uri = URI.create("http://127.0.0.1:" + port + "/api");

            // 明文连接通过 h2c 升级到 HTTP/2，响应版本用于验证升级结果。
            HttpClient.Version version = HttpClient.Version.HTTP_2;
            HttpClient client = HttpClient.newBuilder().version(version).build();
            String call = "Cs9\"echo_echo\"a1{s5\"hello\"}z";
            var body = HttpRequest.BodyPublishers.ofString(call, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(uri).POST(body).build();

            // 发送 Hprose 调用报文，限定响应等待时间。
            var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            try {
                HttpResponse<String> response = pending.get(5, TimeUnit.SECONDS);
                assertEquals(200, response.statusCode());
                assertEquals(version, response.version());
                assertEquals("Rs5\"hello\"z", response.body());
            } finally {
                // 断言失败时也停止仍在等待的请求。
                pending.cancel(true);
            }
        }
    }

    @Test(timeout = 15000)
    public void h2cUpgradeReturnsTheFunctionList() throws Exception {
        // 在同一个 HTTP 端口的 /api 挂载 Hprose。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("hprose");
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("hprose") //
                .contextPath("/api");
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布接口，以真实服务验证协议挂载。
            server.start();
            server.publisher() //
                    .rsfService(TextService.class, value -> value) //
                    .aliasName("Hprose", "echo") //
                    .register();

            int port = server.bindAddress("hprose").getPort();
            URI uri = URI.create("http://127.0.0.1:" + port + "/api");

            // 明文连接通过 h2c 升级到 HTTP/2，响应版本用于验证升级结果。
            HttpClient.Version version = HttpClient.Version.HTTP_2;
            HttpClient client = HttpClient.newBuilder().version(version).build();
            var listBody = HttpRequest.BodyPublishers.ofString("z");
            HttpRequest list = HttpRequest.newBuilder(uri).POST(listBody).build();

            // 请求函数列表，验证挂载服务的方法已通过协议公开。
            var pending = client.sendAsync(list, HttpResponse.BodyHandlers.ofString());
            try {
                HttpResponse<String> response = pending.get(5, TimeUnit.SECONDS);
                assertEquals(200, response.statusCode());
                assertEquals(version, response.version());
                assertTrue(response.body().contains("echo_echo"));
            } finally {
                // 断言失败时也停止仍在等待的请求。
                pending.cancel(true);
            }
        }
    }

    @Test(timeout = 15000)
    public void h2cUpgradeRejectsAnUnknownPath() throws Exception {
        // 在同一个 HTTP 端口的 /api 挂载 Hprose。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("hprose");
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("hprose") //
                .contextPath("/api");
        try (MetContext server = configuration.buildContext()) {
            // 容器启动后发布接口，以真实服务验证协议挂载。
            server.start();
            server.publisher() //
                    .rsfService(TextService.class, value -> value) //
                    .aliasName("Hprose", "echo") //
                    .register();

            int port = server.bindAddress("hprose").getPort();

            // 明文连接通过 h2c 升级到 HTTP/2，响应版本用于验证升级结果。
            HttpClient.Version version = HttpClient.Version.HTTP_2;
            HttpClient client = HttpClient.newBuilder().version(version).build();
            URI missing = URI.create("http://127.0.0.1:" + port + "/missing");
            HttpRequest unknown = HttpRequest.newBuilder(missing).GET().build();

            // 请求未挂载的路径，确认传输层返回 404。
            var pending = client.sendAsync(unknown, HttpResponse.BodyHandlers.discarding());
            try {
                HttpResponse<Void> response = pending.get(5, TimeUnit.SECONDS);
                assertEquals(404, response.statusCode());
                assertEquals(version, response.version());
            } finally {
                // 断言失败时也停止仍在等待的请求。
                pending.cancel(true);
            }
        }
    }
}

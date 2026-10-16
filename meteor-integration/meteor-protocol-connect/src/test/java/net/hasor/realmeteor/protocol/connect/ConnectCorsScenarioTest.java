/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.connect;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import net.hasor.example.protocol.connect.ConnectDemoService;
import net.hasor.example.protocol.connect.ConnectDemoServiceImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ConnectCorsScenarioTest {
    @Test(timeout = 15000)
    public void browserPreflightUsesThePublishedContext() throws Exception {
        // 启动公开 HTTP 端点，允许一个浏览器来源使用 Connect 挂载。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService").serialize("Json").register();

            // 使用普通 HTTP 客户端模拟浏览器对同一路径的预检。
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .timeout(Duration.ofSeconds(5)) //
                    .header("Origin", "https://app.example") //
                    .header("Access-Control-Request-Method", "POST") //
                    .header("Access-Control-Request-Headers", "content-type,connect-protocol-version") //
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
            HttpClient browser = HttpClient.newHttpClient();
            HttpResponse<String> response = browser.send(request, HttpResponse.BodyHandlers.ofString());

            // 预检由协议插件直接响应，并授权后续请求所需字段。
            assertEquals(200, response.statusCode());
            String allowedOrigin = response.headers().firstValue("access-control-allow-origin").orElse(null);
            assertEquals("https://app.example", allowedOrigin);
            String allowed = response.headers().firstValue("access-control-allow-headers").orElse("");
            assertTrue(allowed.contains("connect-protocol-version"));
        }
    }

    @Test(timeout = 15000)
    public void crossOriginUnaryResponseIncludesTheAllowedOrigin() throws Exception {
        // 通过公开入口启动容器并发布 JSON 接口。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                    .aliasName("connect", "meteor.connect.demo.ConnectDemoService").serialize("Json").register();

            // 已获授权的浏览器来源发送标准 Connect unary POST。
            String json = "{\"text\":\"browser\",\"number\":3}";
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .timeout(Duration.ofSeconds(5)) //
                    .header("Origin", "https://app.example") //
                    .header("Content-Type", "application/json") //
                    .header("Connect-Protocol-Version", "1") //
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build();
            HttpClient browser = HttpClient.newHttpClient();
            HttpResponse<String> response = browser.send(request, HttpResponse.BodyHandlers.ofString());

            // 正常业务结果与来源授权一起交付，便于浏览器读取。
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("browser"));
            String allowedOrigin = response.headers().firstValue("access-control-allow-origin").orElse(null);
            assertEquals("https://app.example", allowedOrigin);
        }
    }

    private MetConfiguration configuration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("connect");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("connect").contextPath("/connect").option("corsOrigins", "https://app.example");
        return configuration;
    }

    private URI endpoint(MetContext context) {
        InterAddress address = context.getListenAddress("connect");
        return URI.create("http://127.0.0.1:" + address.getPort() //
                + "/connect/meteor.connect.demo.ConnectDemoService/echo");
    }
}

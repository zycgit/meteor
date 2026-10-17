/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.jsonrpc;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.example.protocol.jsonrpc.JsonRpcDemoService;
import net.hasor.example.protocol.jsonrpc.JsonRpcDemoServiceImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class JsonRpcContextScenarioTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final        HttpClient   http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Test(timeout = 15000)
    public void unspecifiedServiceContextUsesOnlyTheDefault() throws Exception {
        // 一个监听端口声明默认与附加挂载，服务按发布配置选择暴露位置。
        MetConfiguration configuration = this.configuration();
        AtomicInteger calls = new AtomicInteger();
        JsonRpcDemoServiceImpl implementation = new JsonRpcDemoServiceImpl() {
            @Override
            public String echo(String value) {
                calls.incrementAndGet();
                return value;
            }
        };
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, implementation) //
                    .aliasName("jsonrpc", "echo") //
                    .register();

            // 被选中的路径能调用；另一个已挂载路径不能越过服务暴露限制。
            int port = server.getListenAddress("jsonrpc").getPort();
            String body = """
                    {"jsonrpc":"2.0","method":"echo.echo","params":["selected"],"id":1}
                    """;
            HttpRequest selected = this.post(port, "/rpc", body);
            HttpResponse<String> accepted = this.http.send(selected, HttpResponse.BodyHandlers.ofString());
            assertEquals("selected", JSON.readTree(accepted.body()).path("result").asText());

            HttpRequest other = this.post(port, "/private", body);
            HttpResponse<String> rejected = this.http.send(other, HttpResponse.BodyHandlers.ofString());
            JsonNode denied = JSON.readTree(rejected.body());
            assertTrue(denied.has("error"));
            assertEquals(1, calls.get());
        }
    }

    @Test(timeout = 15000)
    public void explicitServiceContextExcludesTheDefault() throws Exception {
        // 一个监听端口声明默认与附加挂载，服务按发布配置选择暴露位置。
        MetConfiguration configuration = this.configuration();
        AtomicInteger calls = new AtomicInteger();
        JsonRpcDemoServiceImpl implementation = new JsonRpcDemoServiceImpl() {
            @Override
            public String echo(String value) {
                calls.incrementAndGet();
                return value;
            }
        };
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, implementation) //
                    .aliasName("jsonrpc", "echo") //
                    .contextPathByPrefix("jsonrpc", "/private") //
                    .register();

            // 被选中的路径能调用；另一个已挂载路径不能越过服务暴露限制。
            int port = server.getListenAddress("jsonrpc").getPort();
            String body = """
                    {"jsonrpc":"2.0","method":"echo.echo","params":["selected"],"id":1}
                    """;
            HttpRequest selected = this.post(port, "/private", body);
            HttpResponse<String> accepted = this.http.send(selected, HttpResponse.BodyHandlers.ofString());
            assertEquals("selected", JSON.readTree(accepted.body()).path("result").asText());

            HttpRequest other = this.post(port, "/rpc", body);
            HttpResponse<String> rejected = this.http.send(other, HttpResponse.BodyHandlers.ofString());
            JsonNode denied = JSON.readTree(rejected.body());
            assertTrue(denied.has("error"));
            assertEquals(1, calls.get());
        }
    }

    @Test(timeout = 15000)
    public void explicitMultipleContextsExposeTheServiceOnOnePort() throws Exception {
        // 同一 Java 服务显式选择默认和附加挂载。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo") //
                    .contextPathByPrefix("jsonrpc", "/rpc", "/private") //
                    .register();

            // 两个挂载地址共享端口，均调用同一个已发布服务。
            int port = server.getListenAddress("jsonrpc").getPort();
            String body = """
                    {"jsonrpc":"2.0","method":"echo.current","id":1}
                    """;
            HttpRequest defaultRequest = this.post(port, "/rpc", body);
            HttpResponse<String> defaults = this.http.send(defaultRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals("ready", JSON.readTree(defaults.body()).path("result").asText());

            HttpRequest extraRequest = this.post(port, "/private", body);
            HttpResponse<String> extra = this.http.send(extraRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals("ready", JSON.readTree(extra.body()).path("result").asText());
        }
    }

    @Test(timeout = 15000)
    public void mountedServiceOptionAllowsUnqualifiedMethodNames() throws Exception {
        // 单服务端点通过协议选项绑定别名，外部客户端只发送方法名。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("jsonrpc");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("jsonrpc").contextPath("/calculator").option("service", "calculator");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "calculator") //
                    .register();

            // 标准 JSON-RPC 客户端无需了解 Meteor 的服务分组或版本。
            int port = server.getListenAddress("jsonrpc").getPort();
            String body = """
                    {"jsonrpc":"2.0","method":"add","params":[8,13],"id":1}
                    """;
            HttpRequest request = this.post(port, "/calculator", body);
            HttpResponse<String> response = this.http.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(21, JSON.readTree(response.body()).path("result").asInt());
        }
    }

    private MetConfiguration configuration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("jsonrpc");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("jsonrpc").contextPath("/rpc").addContextPath("/private");
        return configuration;
    }

    private HttpRequest post(int port, String path, String body) {
        URI uri = URI.create("http://127.0.0.1:" + port + path);
        return HttpRequest.newBuilder(uri) //
                .timeout(Duration.ofSeconds(5)) //
                .header("Content-Type", "application/json") //
                .POST(HttpRequest.BodyPublishers.ofString(body)) //
                .build();
    }
}

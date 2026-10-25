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
import java.util.Set;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.example.protocol.jsonrpc.JsonRpcDemoService;
import net.hasor.example.protocol.jsonrpc.JsonRpcDemoServiceImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.*;

public class JsonRpcWireScenarioTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final        HttpClient   http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Test(timeout = 15000)
    public void positionalParametersCallTheJavaInterface() throws Exception {
        // 位置参数对应 Java 方法声明顺序。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo") //
                    .register();

            // 外部客户端发送标准 JSON-RPC 报文，不依赖 Meteor 编解码器。
            int port = server.getListenAddress("jsonrpc").getPort();
            String body = "{\"jsonrpc\":\"2.0\",\"method\":\"echo.subtract\",\"params\":[7,5],\"id\":1}";
            HttpResponse<String> response = this.http.send(this.post(port, body), HttpResponse.BodyHandlers.ofString());

            // 检查线上的响应结构与当前场景对应的结果。
            assertEquals(200, response.statusCode());
            JsonNode result = JSON.readTree(response.body());
            assertEquals("2.0", result.path("jsonrpc").asText());
            assertEquals(2, result.path("result").asInt());
            assertEquals(1, result.path("id").asInt());
        }
    }

    @Test(timeout = 15000)
    public void namedParametersUseJavaParameterNames() throws Exception {
        // 命名参数按照 Java 参数名绑定，与 JSON 属性顺序无关。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo") //
                    .register();

            // 外部客户端发送标准 JSON-RPC 报文，不依赖 Meteor 编解码器。
            int port = server.getListenAddress("jsonrpc").getPort();
            String body = """
                    {"jsonrpc":"2.0","method":"echo.subtract","params":{"right":5,"left":7},"id":2}
                    """;
            HttpResponse<String> response = this.http.send(this.post(port, body), HttpResponse.BodyHandlers.ofString());

            // 检查线上的响应结构与当前场景对应的结果。
            assertEquals(200, response.statusCode());
            JsonNode result = JSON.readTree(response.body());
            assertEquals(2, result.path("result").asInt());
        }
    }

    @Test(timeout = 15000)
    public void omittedParametersInvokeAZeroArgumentMethod() throws Exception {
        // 无参数的方法可以省略 params。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo") //
                    .register();

            // 外部客户端发送标准 JSON-RPC 报文，不依赖 Meteor 编解码器。
            int port = server.getListenAddress("jsonrpc").getPort();
            String body = "{\"jsonrpc\":\"2.0\",\"method\":\"echo.current\",\"id\":3}";
            HttpResponse<String> response = this.http.send(this.post(port, body), HttpResponse.BodyHandlers.ofString());

            // 检查线上的响应结构与当前场景对应的结果。
            assertEquals(200, response.statusCode());
            JsonNode result = JSON.readTree(response.body());
            assertEquals("ready", result.path("result").asText());
        }
    }

    @Test(timeout = 15000)
    public void nullBusinessResultIsPresentInResponse() throws Exception {
        // 业务返回 null 时仍保留 result 属性。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo") //
                    .register();

            // 外部客户端发送标准 JSON-RPC 报文，不依赖 Meteor 编解码器。
            int port = server.getListenAddress("jsonrpc").getPort();
            String body = "{\"jsonrpc\":\"2.0\",\"method\":\"echo.echo\",\"params\":[null],\"id\":4}";
            HttpResponse<String> response = this.http.send(this.post(port, body), HttpResponse.BodyHandlers.ofString());

            // 检查线上的响应结构与当前场景对应的结果。
            assertEquals(200, response.statusCode());
            JsonNode result = JSON.readTree(response.body());
            assertTrue(result.has("result"));
            assertTrue(result.path("result").isNull());
            assertFalse(result.has("error"));
        }
    }

    @Test(timeout = 15000)
    public void stringRequestIdIsReturnedUnchanged() throws Exception {
        // 字符串 ID 原样返回，不强制转换为内部请求序号。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo") //
                    .register();

            // 外部客户端发送标准 JSON-RPC 报文，不依赖 Meteor 编解码器。
            int port = server.getListenAddress("jsonrpc").getPort();
            String body = "{\"jsonrpc\":\"2.0\",\"method\":\"echo.echo\",\"params\":[\"ok\"],\"id\":\"external-42\"}";
            HttpResponse<String> response = this.http.send(this.post(port, body), HttpResponse.BodyHandlers.ofString());

            // 检查线上的响应结构与当前场景对应的结果。
            assertEquals(200, response.statusCode());
            JsonNode result = JSON.readTree(response.body());
            assertEquals("external-42", result.path("id").asText());
        }
    }

    @Test(timeout = 15000)
    public void largeNumericRequestIdRetainsItsPrecision() throws Exception {
        // 超过 long 范围的数字 ID 在响应中保留精度。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo") //
                    .register();

            // 外部客户端发送标准 JSON-RPC 报文，不依赖 Meteor 编解码器。
            int port = server.getListenAddress("jsonrpc").getPort();
            String body = "{\"jsonrpc\":\"2.0\",\"method\":\"echo.current\",\"id\":123456789012345678901234567890}";
            HttpResponse<String> response = this.http.send(this.post(port, body), HttpResponse.BodyHandlers.ofString());

            // 检查线上的响应结构与当前场景对应的结果。
            assertEquals(200, response.statusCode());
            JsonNode result = JSON.readTree(response.body());
            assertEquals("123456789012345678901234567890", result.path("id").asText());
        }
    }

    @Test(timeout = 15000)
    public void explicitNullIdStillProducesAResponse() throws Exception {
        // 显式 null ID 是请求，区别于未包含 id 的通知。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo") //
                    .register();

            // 外部客户端发送标准 JSON-RPC 报文，不依赖 Meteor 编解码器。
            int port = server.getListenAddress("jsonrpc").getPort();
            String body = "{\"jsonrpc\":\"2.0\",\"method\":\"echo.current\",\"id\":null}";
            HttpResponse<String> response = this.http.send(this.post(port, body), HttpResponse.BodyHandlers.ofString());

            // 检查线上的响应结构与当前场景对应的结果。
            assertEquals(200, response.statusCode());
            JsonNode result = JSON.readTree(response.body());
            assertTrue(result.has("id"));
            assertTrue(result.path("id").isNull());
            assertEquals("ready", result.path("result").asText());
        }
    }

    @Test(timeout = 15000)
    public void malformedJsonReturnsParseError() throws Exception {
        // 无法解析的 JSON 返回 Parse error。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo") //
                    .register();

            // 外部客户端发送标准 JSON-RPC 报文，不依赖 Meteor 编解码器。
            int port = server.getListenAddress("jsonrpc").getPort();
            String body = "{\"jsonrpc\":";
            HttpResponse<String> response = this.http.send(this.post(port, body), HttpResponse.BodyHandlers.ofString());

            // 检查线上的响应结构与当前场景对应的结果。
            assertEquals(200, response.statusCode());
            JsonNode result = JSON.readTree(response.body());
            assertEquals(-32700, result.path("error").path("code").asInt());
            assertTrue(result.path("id").isNull());
        }
    }

    @Test(timeout = 15000)
    public void wrongVersionReturnsInvalidRequest() throws Exception {
        // 版本号不符合 JSON-RPC 2.0 时返回 Invalid Request。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo") //
                    .register();

            // 外部客户端发送标准 JSON-RPC 报文，不依赖 Meteor 编解码器。
            int port = server.getListenAddress("jsonrpc").getPort();
            String body = "{\"jsonrpc\":\"1.0\",\"method\":\"echo.current\",\"id\":1}";
            HttpResponse<String> response = this.http.send(this.post(port, body), HttpResponse.BodyHandlers.ofString());

            // 检查线上的响应结构与当前场景对应的结果。
            assertEquals(200, response.statusCode());
            JsonNode result = JSON.readTree(response.body());
            assertEquals(-32600, result.path("error").path("code").asInt());
        }
    }

    @Test(timeout = 15000)
    public void booleanIdReturnsInvalidRequest() throws Exception {
        // 布尔 ID 不能作为请求标识。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo") //
                    .register();

            // 外部客户端发送标准 JSON-RPC 报文，不依赖 Meteor 编解码器。
            int port = server.getListenAddress("jsonrpc").getPort();
            String body = "{\"jsonrpc\":\"2.0\",\"method\":\"echo.current\",\"id\":true}";
            HttpResponse<String> response = this.http.send(this.post(port, body), HttpResponse.BodyHandlers.ofString());

            // 检查线上的响应结构与当前场景对应的结果。
            assertEquals(200, response.statusCode());
            JsonNode result = JSON.readTree(response.body());
            assertEquals(-32600, result.path("error").path("code").asInt());
        }
    }

    @Test(timeout = 15000)
    public void scalarDocumentReturnsInvalidRequest() throws Exception {
        // JSON 标量不是请求对象或请求批次。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo") //
                    .register();

            // 外部客户端发送标准 JSON-RPC 报文，不依赖 Meteor 编解码器。
            int port = server.getListenAddress("jsonrpc").getPort();
            String body = "17";
            HttpResponse<String> response = this.http.send(this.post(port, body), HttpResponse.BodyHandlers.ofString());

            // 检查线上的响应结构与当前场景对应的结果。
            assertEquals(200, response.statusCode());
            JsonNode result = JSON.readTree(response.body());
            assertEquals(-32600, result.path("error").path("code").asInt());
        }
    }

    @Test(timeout = 15000)
    public void unknownMethodReturnsMethodNotFound() throws Exception {
        // 未发布的方法返回 Method not found。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo") //
                    .register();

            // 外部客户端发送标准 JSON-RPC 报文，不依赖 Meteor 编解码器。
            int port = server.getListenAddress("jsonrpc").getPort();
            String body = "{\"jsonrpc\":\"2.0\",\"method\":\"echo.missing\",\"id\":11}";
            HttpResponse<String> response = this.http.send(this.post(port, body), HttpResponse.BodyHandlers.ofString());

            // 检查线上的响应结构与当前场景对应的结果。
            assertEquals(200, response.statusCode());
            JsonNode result = JSON.readTree(response.body());
            assertEquals(-32601, result.path("error").path("code").asInt());
            assertEquals(11, result.path("id").asInt());
        }
    }

    @Test(timeout = 15000)
    public void wrongArgumentCountReturnsInvalidParams() throws Exception {
        // 参数数量不匹配时返回 Invalid params。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo") //
                    .register();

            // 外部客户端发送标准 JSON-RPC 报文，不依赖 Meteor 编解码器。
            int port = server.getListenAddress("jsonrpc").getPort();
            String body = "{\"jsonrpc\":\"2.0\",\"method\":\"echo.add\",\"params\":[1],\"id\":12}";
            HttpResponse<String> response = this.http.send(this.post(port, body), HttpResponse.BodyHandlers.ofString());

            // 检查线上的响应结构与当前场景对应的结果。
            assertEquals(200, response.statusCode());
            JsonNode result = JSON.readTree(response.body());
            assertEquals(-32602, result.path("error").path("code").asInt());
        }
    }

    @Test(timeout = 15000)
    public void businessFailureReturnsAnErrorWithTheOriginalId() throws Exception {
        // 未声明协议错误码的业务异常转换成标准 Internal error。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo") //
                    .register();

            // 外部客户端调用抛出异常的业务方法。
            int port = server.getListenAddress("jsonrpc").getPort();
            String body = """
                    {"jsonrpc":"2.0","method":"echo.fail","params":["invalid"],"id":"failed-call"}
                    """;
            HttpResponse<String> response = this.http.send(this.post(port, body), HttpResponse.BodyHandlers.ofString());

            // 错误与结果互斥，外部 ID 仍能用于对应失败调用。
            JsonNode result = JSON.readTree(response.body());
            assertEquals(-32603, result.path("error").path("code").asInt());
            assertEquals("failed-call", result.path("id").asText());
            assertFalse(result.has("result"));
        }
    }

    @Test(timeout = 15000)
    public void wrongArgumentTypeReturnsInvalidParams() throws Exception {
        // 无法转换为 Java 参数类型时返回 Invalid params。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo") //
                    .register();

            // 外部客户端发送标准 JSON-RPC 报文，不依赖 Meteor 编解码器。
            int port = server.getListenAddress("jsonrpc").getPort();
            String body = "{\"jsonrpc\":\"2.0\",\"method\":\"echo.add\",\"params\":[{},5],\"id\":13}";
            HttpResponse<String> response = this.http.send(this.post(port, body), HttpResponse.BodyHandlers.ofString());

            // 检查线上的响应结构与当前场景对应的结果。
            assertEquals(200, response.statusCode());
            JsonNode result = JSON.readTree(response.body());
            assertEquals(-32602, result.path("error").path("code").asInt());
        }
    }

    @Test(timeout = 15000)
    public void unknownNamedParameterReturnsInvalidParams() throws Exception {
        // 未知命名参数不能被静默丢弃。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo") //
                    .register();

            // 外部客户端发送标准 JSON-RPC 报文，不依赖 Meteor 编解码器。
            int port = server.getListenAddress("jsonrpc").getPort();
            String body = """
                    {"jsonrpc":"2.0","method":"echo.add","params":{"left":1,"right":2,"extra":3},"id":14}
                    """;
            HttpResponse<String> response = this.http.send(this.post(port, body), HttpResponse.BodyHandlers.ofString());

            // 检查线上的响应结构与当前场景对应的结果。
            assertEquals(200, response.statusCode());
            JsonNode result = JSON.readTree(response.body());
            assertEquals(-32602, result.path("error").path("code").asInt());
        }
    }

    @Test(timeout = 15000)
    public void emptyBatchReturnsOneInvalidRequestObject() throws Exception {
        // 空批次返回单个 Invalid Request 对象。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo") //
                    .register();

            // 外部客户端发送标准 JSON-RPC 报文，不依赖 Meteor 编解码器。
            int port = server.getListenAddress("jsonrpc").getPort();
            String body = "[]";
            HttpResponse<String> response = this.http.send(this.post(port, body), HttpResponse.BodyHandlers.ofString());

            // 检查线上的响应结构与当前场景对应的结果。
            assertEquals(200, response.statusCode());
            JsonNode result = JSON.readTree(response.body());
            assertTrue(result.isObject());
            assertEquals(-32600, result.path("error").path("code").asInt());
        }
    }

    @Test(timeout = 15000)
    public void singleEntryBatchReturnsAnArray() throws Exception {
        // 仅包含一条普通请求的批次仍返回数组。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo") //
                    .register();

            // 外部客户端发送标准 JSON-RPC 报文，不依赖 Meteor 编解码器。
            int port = server.getListenAddress("jsonrpc").getPort();
            String body = "[{\"jsonrpc\":\"2.0\",\"method\":\"echo.current\",\"id\":15}]";
            HttpResponse<String> response = this.http.send(this.post(port, body), HttpResponse.BodyHandlers.ofString());

            // 检查线上的响应结构与当前场景对应的结果。
            assertEquals(200, response.statusCode());
            JsonNode result = JSON.readTree(response.body());
            assertTrue(result.isArray());
            assertEquals(1, result.size());
            assertEquals("ready", result.get(0).path("result").asText());
        }
    }

    @Test(timeout = 15000)
    public void invalidBatchEntryProducesAnErrorEntry() throws Exception {
        // 批次中的非法元素独立返回错误，不阻断其它请求。
        MetConfiguration configuration = this.configuration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo") //
                    .register();

            // 外部客户端发送标准 JSON-RPC 报文，不依赖 Meteor 编解码器。
            int port = server.getListenAddress("jsonrpc").getPort();
            String body = "[17,{\"jsonrpc\":\"2.0\",\"method\":\"echo.current\",\"id\":16}]";
            HttpResponse<String> response = this.http.send(this.post(port, body), HttpResponse.BodyHandlers.ofString());

            // 检查线上的响应结构与当前场景对应的结果。
            assertEquals(200, response.statusCode());
            JsonNode result = JSON.readTree(response.body());
            assertEquals(2, result.size());
            JsonNode error = result.get(0).has("error") ? result.get(0) : result.get(1);
            JsonNode success = result.get(0).has("result") ? result.get(0) : result.get(1);
            assertEquals(-32600, error.path("error").path("code").asInt());
            assertEquals("ready", success.path("result").asText());
        }
    }

    @Test(timeout = 15000)
    public void notificationReturnsNoJsonResponse() throws Exception {
        // 发布业务接口，通知通过 Dispatcher 执行但不生成 JSON-RPC 响应。
        MetConfiguration configuration = this.configuration();
        JsonRpcDemoServiceImpl implementation = new JsonRpcDemoServiceImpl();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, implementation) //
                    .aliasName("jsonrpc", "echo") //
                    .register();

            // 请求省略 id，明确采用 notification 语义。
            int port = server.getListenAddress("jsonrpc").getPort();
            String body = "{\"jsonrpc\":\"2.0\",\"method\":\"echo.notify\",\"params\":[\"notified\"]}";
            HttpResponse<String> response = this.http.send(this.post(port, body), HttpResponse.BodyHandlers.ofString());

            // HTTP 交换正常结束，正文为空。
            assertEquals(204, response.statusCode());
            assertEquals("", response.body());
            assertEquals("notified", implementation.getNotifications().poll(5, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 15000)
    public void unknownNotificationReturnsNoErrorResponse() throws Exception {
        // 发布业务接口，通知通过 Dispatcher 执行但不生成 JSON-RPC 响应。
        MetConfiguration configuration = this.configuration();
        JsonRpcDemoServiceImpl implementation = new JsonRpcDemoServiceImpl();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, implementation) //
                    .aliasName("jsonrpc", "echo") //
                    .register();

            // 请求省略 id，明确采用 notification 语义。
            int port = server.getListenAddress("jsonrpc").getPort();
            String body = "{\"jsonrpc\":\"2.0\",\"method\":\"echo.unknown\"}";
            HttpResponse<String> response = this.http.send(this.post(port, body), HttpResponse.BodyHandlers.ofString());

            // HTTP 交换正常结束，正文为空。
            assertEquals(204, response.statusCode());
            assertEquals("", response.body());
        }
    }

    @Test(timeout = 15000)
    public void failedNotificationReturnsNoErrorResponse() throws Exception {
        // 发布业务接口，通知通过 Dispatcher 执行但不生成 JSON-RPC 响应。
        MetConfiguration configuration = this.configuration();
        JsonRpcDemoServiceImpl implementation = new JsonRpcDemoServiceImpl();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, implementation) //
                    .aliasName("jsonrpc", "echo") //
                    .register();

            // 请求省略 id，明确采用 notification 语义。
            int port = server.getListenAddress("jsonrpc").getPort();
            String body = "{\"jsonrpc\":\"2.0\",\"method\":\"echo.fail\",\"params\":[\"notified-failure\"]}";
            HttpResponse<String> response = this.http.send(this.post(port, body), HttpResponse.BodyHandlers.ofString());

            // HTTP 交换正常结束，正文为空。
            assertEquals(204, response.statusCode());
            assertEquals("", response.body());
        }
    }

    @Test(timeout = 15000)
    public void mixedBatchReturnsOnlyOrdinaryCallResults() throws Exception {
        // 同一批次包含通知与普通调用，服务实现记录实际收到的通知。
        MetConfiguration configuration = this.configuration();
        JsonRpcDemoServiceImpl implementation = new JsonRpcDemoServiceImpl();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, implementation) //
                    .aliasName("jsonrpc", "echo") //
                    .register();

            // 通知、成功调用和未知方法共同组成一个批次。
            int port = server.getListenAddress("jsonrpc").getPort();
            String body = """
                    [
                      {"jsonrpc":"2.0","method":"echo.notify","params":["batch"]},
                      {"jsonrpc":"2.0","method":"echo.add","params":[2,3],"id":"sum"},
                      {"jsonrpc":"2.0","method":"echo.unknown","id":"missing"}
                    ]
                    """;
            HttpResponse<String> response = this.http.send(this.post(port, body), HttpResponse.BodyHandlers.ofString());

            // 批次只有两个有 ID 的响应，通知仍然被正常执行。
            assertEquals(200, response.statusCode());
            JsonNode result = JSON.readTree(response.body());
            assertEquals(2, result.size());
            int successes = 0;
            int failures = 0;
            for (JsonNode item : result) {
                if (item.has("result")) {
                    successes++;
                    assertEquals("sum", item.path("id").asText());
                    assertEquals(5, item.path("result").asInt());
                } else {
                    failures++;
                    assertEquals("missing", item.path("id").asText());
                    assertEquals(-32601, item.path("error").path("code").asInt());
                }
            }
            assertEquals(1, successes);
            assertEquals(1, failures);
            assertEquals("batch", implementation.getNotifications().poll(5, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 15000)
    public void notificationOnlyBatchReturnsNoJsonArray() throws Exception {
        // 全通知批次只提交调用，不生成空数组或占位结果。
        MetConfiguration configuration = this.configuration();
        JsonRpcDemoServiceImpl implementation = new JsonRpcDemoServiceImpl();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, implementation) //
                    .aliasName("jsonrpc", "echo") //
                    .register();

            // 发送两个通知，独立观察应用侧接收结果。
            int port = server.getListenAddress("jsonrpc").getPort();
            String body = """
                    [
                      {"jsonrpc":"2.0","method":"echo.notify","params":["first"]},
                      {"jsonrpc":"2.0","method":"echo.notify","params":["second"]}
                    ]
                    """;
            HttpResponse<String> response = this.http.send(this.post(port, body), HttpResponse.BodyHandlers.ofString());

            // HTTP 正文为空，两个通知均到达服务方法。
            assertEquals(204, response.statusCode());
            assertEquals("", response.body());
            String first = implementation.getNotifications().poll(5, TimeUnit.SECONDS);
            String second = implementation.getNotifications().poll(5, TimeUnit.SECONDS);
            assertTrue(Set.of(first, second).containsAll(Set.of("first", "second")));
        }
    }

    private MetConfiguration configuration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("jsonrpc");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("jsonrpc").contextPath("/rpc");
        return configuration;
    }

    private HttpRequest post(int port, String body) {
        URI uri = URI.create("http://127.0.0.1:" + port + "/rpc");
        return HttpRequest.newBuilder(uri) //
                .timeout(Duration.ofSeconds(5)) //
                .header("Content-Type", "application/json") //
                .POST(HttpRequest.BodyPublishers.ofString(body)) //
                .build();
    }
}

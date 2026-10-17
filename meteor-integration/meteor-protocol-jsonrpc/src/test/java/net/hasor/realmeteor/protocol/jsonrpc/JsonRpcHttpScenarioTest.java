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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.jsonrpc.JsonRpcDemoService;
import net.hasor.example.protocol.jsonrpc.JsonRpcDemoServiceImpl;
import net.hasor.example.protocol.jsonrpc.JsonRpcPerson;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.domain.MetTimeoutException;
import org.junit.Test;
import static org.junit.Assert.*;

public class JsonRpcHttpScenarioTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test(timeout = 15000)
    public void jsonRpcAndHproseShareOneHttpPortThroughContextRouting() throws Exception {
        // 两个正式协议插件通过 SPI 发现，并挂载到同一个 HTTP 监听端口。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("jsonrpc");
        ConnectorSettings listener = configuration.connector("http").bind("127.0.0.1", 0);
        listener.protocol("jsonrpc").contextPath("/jsonrpc");
        listener.protocol("hprose").contextPath("/hprose");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo") //
                    .aliasName("Hprose", "echo") //
                    .protocol("jsonrpc", "hprose") //
                    .register();

            // 相同业务接口从不同路径进入对应协议；两者公开的监听端口必须相同。
            int port = server.getListenAddress("jsonrpc").getPort();
            assertEquals(port, server.getListenAddress("hprose").getPort());
            HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            URI jsonUri = URI.create("http://127.0.0.1:" + port + "/jsonrpc");
            String jsonBody = """
                    {"jsonrpc":"2.0","method":"echo.echo","params":["jsonrpc"],"id":1}
                    """;
            HttpRequest jsonRequest = HttpRequest.newBuilder(jsonUri) //
                    .timeout(Duration.ofSeconds(5)) //
                    .header("Content-Type", "application/json") //
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody)) //
                    .build();
            HttpResponse<String> jsonResponse = http.send(jsonRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, jsonResponse.statusCode());
            String jsonResult = JSON.readTree(jsonResponse.body()).path("result").asText();
            assertEquals("jsonrpc", jsonResult);

            // Hprose 请求使用真实线协议报文，确认未被 JSON-RPC 分支接收。
            URI hproseUri = URI.create("http://127.0.0.1:" + port + "/hprose");
            String hproseBody = "Cs9\"echo_echo\"a1{s6\"hprose\"}z";
            HttpRequest hproseRequest = HttpRequest.newBuilder(hproseUri) //
                    .timeout(Duration.ofSeconds(5)) //
                    .header("Content-Type", "application/hprose") //
                    .POST(HttpRequest.BodyPublishers.ofString(hproseBody)) //
                    .build();
            HttpResponse<String> hproseResponse = http.send(hproseRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, hproseResponse.statusCode());
            assertEquals("Rs6\"hprose\"z", hproseResponse.body());
        }
    }

    @Test(timeout = 15000)
    public void javaProxyCallsOverHttp1() throws Exception {
        // 显式选择 HTTP/1，验证代理调用在实际网络上完成。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration(1);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器先启动，再使用公开发布与订阅 API 注册普通 Java 接口。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo") //
                    .register();
            MetBindInfo<JsonRpcDemoService> service = client.publisher() //
                    .rsfService(JsonRpcDemoService.class) //
                    .aliasName("jsonrpc", "echo") //
                    .register();

            // 服务代理只使用接口，不接触协议编解码实现。
            JsonRpcDemoService remote = client.getRsfClient(server.getListenAddress("jsonrpc")).getRemote(service);
            assertEquals("hello-jsonrpc", remote.echo("hello-jsonrpc"));
        }
    }

    @Test(timeout = 15000)
    public void javaProxyCallsOverHttp2PriorKnowledge() throws Exception {
        // 显式选择 HTTP/2，验证代理调用在实际网络上完成。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration(2);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器先启动，再使用公开发布与订阅 API 注册普通 Java 接口。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo") //
                    .register();
            MetBindInfo<JsonRpcDemoService> service = client.publisher() //
                    .rsfService(JsonRpcDemoService.class) //
                    .aliasName("jsonrpc", "echo") //
                    .register();

            // 服务代理只使用接口，不接触协议编解码实现。
            JsonRpcDemoService remote = client.getRsfClient(server.getListenAddress("jsonrpc")).getRemote(service);
            assertEquals("hello-jsonrpc", remote.echo("hello-jsonrpc"));
        }
    }

    @Test(timeout = 15000)
    public void genericDtoArgumentsAndResultsKeepTheirElementTypes() throws Exception {
        // 泛型集合直接使用接口签名中的元素类型进行 JSON 映射。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration(1);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器先启动，再使用公开发布与订阅 API 注册普通 Java 接口。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo") //
                    .register();
            MetBindInfo<JsonRpcDemoService> service = client.publisher() //
                    .rsfService(JsonRpcDemoService.class) //
                    .aliasName("jsonrpc", "echo") //
                    .register();

            // 准备一个普通业务对象，并通过集合参数传递。
            JsonRpcPerson person = new JsonRpcPerson();
            person.setName("Meteor");
            person.setAge(11);
            JsonRpcDemoService remote = client.getRsfClient(server.getListenAddress("jsonrpc")).getRemote(service);
            List<JsonRpcPerson> result = remote.people(List.of(person));

            // 返回集合里的元素仍是业务类型，不能退化为 Map。
            assertEquals(1, result.size());
            assertEquals("Meteor", result.get(0).getName());
            assertEquals(11, result.get(0).getAge());
        }
    }

    @Test(timeout = 15000)
    public void proxyNotificationCompletesAfterSubmission() throws Exception {
        // 消息方法只等待提交完成，应用通过通知队列观察实际处理。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration(1);
        JsonRpcDemoServiceImpl implementation = new JsonRpcDemoServiceImpl();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, implementation) //
                    .aliasName("jsonrpc", "echo") //
                    .register();
            MetBindInfo<JsonRpcDemoService> service = client.publisher() //
                    .rsfService(JsonRpcDemoService.class) //
                    .aliasName("jsonrpc", "echo") //
                    .register();

            // @MetMessage 使 Java 调用生成省略 id 的 notification。
            JsonRpcDemoService remote = client.getRsfClient(server.getListenAddress("jsonrpc")).getRemote(service);
            remote.notify("submitted");
            assertEquals("submitted", implementation.getNotifications().poll(5, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 15000)
    public void incomingCallsPassThroughPublishedFilters() throws Exception {
        // 过滤器由用户发布 API 绑定，协议不能绕过 Dispatcher 调用链。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration(1);
        AtomicInteger filtered = new AtomicInteger();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo") //
                    .bindFilter("observe", (request, response, chain) -> {
                        filtered.incrementAndGet();
                        chain.doFilter(request, response);
                    }) //
                    .register();
            MetBindInfo<JsonRpcDemoService> service = client.publisher() //
                    .rsfService(JsonRpcDemoService.class) //
                    .aliasName("jsonrpc", "echo") //
                    .register();

            // 真实远程调用执行服务过滤器一次。
            JsonRpcDemoService remote = client.getRsfClient(server.getListenAddress("jsonrpc")).getRemote(service);
            assertEquals("filtered", remote.echo("filtered"));
            assertEquals(1, filtered.get());
        }
    }

    @Test(timeout = 15000)
    public void concurrentHttp2CallsKeepTheirOwnResults() throws Exception {
        // HTTP/2 在一个连接中并发承载多个普通 RPC。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration(2);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器先启动，再使用公开发布与订阅 API 注册普通 Java 接口。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo") //
                    .register();
            MetBindInfo<JsonRpcDemoService> service = client.publisher() //
                    .rsfService(JsonRpcDemoService.class) //
                    .aliasName("jsonrpc", "echo") //
                    .register();

            // 一次提交所有调用，避免同步等待将场景退化为串行。
            MetClient remote = client.getRsfClient(server.getListenAddress("jsonrpc"));
            List<MetFuture> pending = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                Object[] arguments = { "request-" + i };
                pending.add(remote.asyncInvoke(service, "echo", new Class<?>[] { String.class }, arguments));
            }

            // 每条请求对应自己的 JSON-RPC ID 和结果。
            for (int i = 0; i < pending.size(); i++) {
                assertEquals("request-" + i, pending.get(i).getData(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test(timeout = 15000)
    public void h2cUpgradePreservesTheJsonRpcResponse() throws Exception {
        // JDK 客户端通过 HTTP/1 Upgrade 请求切换到明文 HTTP/2。
        MetConfiguration incoming = this.serverConfiguration();
        try (MetContext server = incoming.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo") //
                    .register();

            // 外部客户端直接发送 JSON-RPC 请求，升级状态由响应版本证明。
            int port = server.getListenAddress("jsonrpc").getPort();
            URI uri = URI.create("http://127.0.0.1:" + port + "/rpc");
            String body = """
                    {"jsonrpc":"2.0","method":"echo.echo","params":["h2c"],"id":1}
                    """;
            HttpRequest request = HttpRequest.newBuilder(uri) //
                    .timeout(Duration.ofSeconds(5)) //
                    .header("Content-Type", "application/json") //
                    .POST(HttpRequest.BodyPublishers.ofString(body)) //
                    .build();
            HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            // 验证 HTTP/2 已协商成功，且业务响应完整。
            assertEquals(HttpClient.Version.HTTP_2, response.version());
            assertEquals(200, response.statusCode());
            assertEquals("h2c", JSON.readTree(response.body()).path("result").asText());
        }
    }

    @Test(timeout = 15000)
    public void requestTimeoutCompletesTheCallerFuture() throws Exception {
        // 用闩锁保持一次真实远程调用在途，控制取消或超时的触发时刻。
        MetConfiguration incoming = this.serverConfiguration();
        incoming.setQueueMinPoolSize(2);
        MetConfiguration outgoing = this.clientConfiguration(2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JsonRpcDemoServiceImpl implementation = new JsonRpcDemoServiceImpl() {
            @Override
            public String echo(String value) {
                if (StringUtils.equals("blocked", value)) {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test release timed out");
                        }
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(failure);
                    }
                }
                return value;
            }
        };
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, implementation) //
                    .aliasName("jsonrpc", "echo") //
                    .register();
            MetBindInfo<JsonRpcDemoService> service = client.publisher() //
                    .rsfService(JsonRpcDemoService.class) //
                    .aliasName("jsonrpc", "echo") //
                    .timeout(600) //
                    .register();
            MetClient remote = client.getRsfClient(server.getListenAddress("jsonrpc"));

            // 只有确认业务已进入后才检查调用方的终止结果。
            try {
                Class<?>[] types = { String.class };
                MetFuture pending = remote.asyncInvoke(service, "echo", types, new Object[] { "blocked" });
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                    pending.getData(3, TimeUnit.SECONDS);
                });
                assertTrue(failure.getCause() instanceof MetTimeoutException);
            } finally {
                release.countDown();
            }
        }
    }

    @Test(timeout = 15000)
    public void cancellingOneHttp2CallDoesNotCancelOtherCalls() throws Exception {
        // 用闩锁保持一次真实远程调用在途，控制取消或超时的触发时刻。
        MetConfiguration incoming = this.serverConfiguration();
        incoming.setQueueMinPoolSize(2);
        MetConfiguration outgoing = this.clientConfiguration(2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JsonRpcDemoServiceImpl implementation = new JsonRpcDemoServiceImpl() {
            @Override
            public String echo(String value) {
                if (StringUtils.equals("blocked", value)) {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test release timed out");
                        }
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(failure);
                    }
                }
                return value;
            }
        };
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(JsonRpcDemoService.class, implementation) //
                    .aliasName("jsonrpc", "echo") //
                    .register();
            MetBindInfo<JsonRpcDemoService> service = client.publisher() //
                    .rsfService(JsonRpcDemoService.class) //
                    .aliasName("jsonrpc", "echo") //
                    .timeout(5000) //
                    .register();
            MetClient remote = client.getRsfClient(server.getListenAddress("jsonrpc"));

            // 只有确认业务已进入后才检查调用方的终止结果。
            try {
                Class<?>[] types = { String.class };
                MetFuture pending = remote.asyncInvoke(service, "echo", types, new Object[] { "blocked" });
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTrue(pending.cancel());
                assertTrue(pending.isCancelled());

                // 取消只影响当前 HTTP/2 stream，其它请求仍能执行。
                MetFuture surviving = remote.asyncInvoke(service, "echo", types, new Object[] { "surviving" });
                assertEquals("surviving", surviving.getData(3, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
        }
    }

    private MetConfiguration serverConfiguration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("jsonrpc");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("jsonrpc").contextPath("/rpc");
        return configuration;
    }

    private MetConfiguration clientConfiguration(int httpVersion) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("jsonrpc");
        configuration.connector("http").option("httpVersion", httpVersion) //
                .protocol("jsonrpc").contextPath("/rpc");
        return configuration;
    }
}

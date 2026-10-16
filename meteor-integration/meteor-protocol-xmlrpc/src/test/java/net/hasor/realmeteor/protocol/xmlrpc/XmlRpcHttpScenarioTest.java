/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.xmlrpc;
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
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.xmlrpc.XmlRpcDemoService;
import net.hasor.example.protocol.xmlrpc.XmlRpcDemoServiceImpl;
import net.hasor.example.protocol.xmlrpc.XmlRpcPerson;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.domain.MetTimeoutException;
import net.hasor.meteor.protocol.xmlrpc.XmlRpcException;
import org.junit.Test;
import static org.junit.Assert.*;

public class XmlRpcHttpScenarioTest {

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
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .register();
            MetBindInfo<XmlRpcDemoService> service = client.publisher() //
                    .rsfService(XmlRpcDemoService.class) //
                    .aliasName("xmlrpc", "demo") //
                    .register();

            // 服务代理只使用接口，不接触协议编解码实现。
            XmlRpcDemoService remote = client.getRsfClient(server.getListenAddress("xmlrpc")).getRemote(service);
            assertEquals("hello-xmlrpc", remote.echo("hello-xmlrpc"));
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
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .register();
            MetBindInfo<XmlRpcDemoService> service = client.publisher() //
                    .rsfService(XmlRpcDemoService.class) //
                    .aliasName("xmlrpc", "demo") //
                    .register();

            // 服务代理只使用接口，不接触协议编解码实现。
            XmlRpcDemoService remote = client.getRsfClient(server.getListenAddress("xmlrpc")).getRemote(service);
            assertEquals("hello-xmlrpc", remote.echo("hello-xmlrpc"));
        }
    }

    @Test(timeout = 15000)
    public void genericDtoArgumentsAndResultsKeepTheirElementTypes() throws Exception {
        // 泛型集合直接使用接口签名中的元素类型进行 XML-RPC struct 映射。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration(1);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器先启动，再使用公开发布与订阅 API 注册普通 Java 接口。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .register();
            MetBindInfo<XmlRpcDemoService> service = client.publisher() //
                    .rsfService(XmlRpcDemoService.class) //
                    .aliasName("xmlrpc", "demo") //
                    .register();

            // 准备一个普通业务对象，并通过集合参数传递。
            XmlRpcPerson person = new XmlRpcPerson();
            person.setName("Meteor");
            person.setAge(11);
            XmlRpcDemoService remote = client.getRsfClient(server.getListenAddress("xmlrpc")).getRemote(service);
            List<XmlRpcPerson> result = remote.people(List.of(person));

            // 返回集合里的元素仍是业务类型，不能退化为 Map。
            assertEquals(1, result.size());
            assertEquals("Meteor", result.get(0).getName());
            assertEquals(11, result.get(0).getAge());
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
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .bindFilter("observe", (request, response, chain) -> {
                        filtered.incrementAndGet();
                        chain.doFilter(request, response);
                    }) //
                    .register();
            MetBindInfo<XmlRpcDemoService> service = client.publisher() //
                    .rsfService(XmlRpcDemoService.class) //
                    .aliasName("xmlrpc", "demo") //
                    .register();

            // 真实远程调用执行服务过滤器一次。
            XmlRpcDemoService remote = client.getRsfClient(server.getListenAddress("xmlrpc")).getRemote(service);
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
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .register();
            MetBindInfo<XmlRpcDemoService> service = client.publisher() //
                    .rsfService(XmlRpcDemoService.class) //
                    .aliasName("xmlrpc", "demo") //
                    .register();

            // 一次提交所有调用，避免同步等待将场景退化为串行。
            MetClient remote = client.getRsfClient(server.getListenAddress("xmlrpc"));
            List<MetFuture> pending = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                Object[] arguments = { "request-" + i };
                pending.add(remote.asyncInvoke(service, "echo", new Class<?>[] { String.class }, arguments));
            }

            // 每条 HTTP/2 stream 对应自己的 XML-RPC 调用结果。
            for (int i = 0; i < pending.size(); i++) {
                assertEquals("request-" + i, pending.get(i).getData(5, TimeUnit.SECONDS));
            }
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
        XmlRpcDemoServiceImpl implementation = new XmlRpcDemoServiceImpl() {
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
                    .rsfService(XmlRpcDemoService.class, implementation) //
                    .aliasName("xmlrpc", "demo") //
                    .register();
            MetBindInfo<XmlRpcDemoService> service = client.publisher() //
                    .rsfService(XmlRpcDemoService.class) //
                    .aliasName("xmlrpc", "demo") //
                    .timeout(600) //
                    .register();
            MetClient remote = client.getRsfClient(server.getListenAddress("xmlrpc"));

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
        XmlRpcDemoServiceImpl implementation = new XmlRpcDemoServiceImpl() {
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
                    .rsfService(XmlRpcDemoService.class, implementation) //
                    .aliasName("xmlrpc", "demo") //
                    .register();
            MetBindInfo<XmlRpcDemoService> service = client.publisher() //
                    .rsfService(XmlRpcDemoService.class) //
                    .aliasName("xmlrpc", "demo") //
                    .timeout(5000) //
                    .register();
            MetClient remote = client.getRsfClient(server.getListenAddress("xmlrpc"));

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

    @Test(timeout = 15000)
    public void xmlRpcAndJsonRpcShareOneHttpPortThroughContextRouting() throws Exception {
        // 两个正式插件在同一端口按上下文挂载，无需额外监听器。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("xmlrpc");
        ConnectorSettings endpoint = configuration.connector("http").bind("127.0.0.1", 0);
        endpoint.protocol("xmlrpc").contextPath("/xmlrpc");
        endpoint.protocol("jsonrpc").contextPath("/jsonrpc");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .aliasName("jsonrpc", "demo") //
                    .protocol("xmlrpc", "jsonrpc") //
                    .register();

            // 两种报文分别交给自己的协议，地址只在路径上不同。
            int port = server.getListenAddress("xmlrpc").getPort();
            assertEquals(port, server.getListenAddress("jsonrpc").getPort());
            HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            URI xmlUri = URI.create("http://127.0.0.1:" + port + "/xmlrpc");
            String xmlBody = """
                    <methodCall><methodName>demo.echo</methodName><params>
                    <param><value><string>xmlrpc</string></value></param>
                    </params></methodCall>
                    """;
            HttpRequest xmlRequest = HttpRequest.newBuilder(xmlUri) //
                    .timeout(Duration.ofSeconds(5)) //
                    .header("Content-Type", "text/xml") //
                    .POST(HttpRequest.BodyPublishers.ofString(xmlBody)) //
                    .build();
            HttpResponse<String> xmlResponse = http.send(xmlRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, xmlResponse.statusCode());
            assertTrue(xmlResponse.body().contains("<string>xmlrpc</string>"));

            // JSON-RPC 使用独立格式，验证相邻挂载的协议隔离。
            URI jsonUri = URI.create("http://127.0.0.1:" + port + "/jsonrpc");
            String jsonBody = """
                    {"jsonrpc":"2.0","method":"demo.echo","params":["jsonrpc"],"id":1}
                    """;
            HttpRequest jsonRequest = HttpRequest.newBuilder(jsonUri) //
                    .timeout(Duration.ofSeconds(5)) //
                    .header("Content-Type", "application/json") //
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody)) //
                    .build();
            HttpResponse<String> jsonResponse = http.send(jsonRequest, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, jsonResponse.statusCode());
            assertTrue(jsonResponse.body().contains("\"result\":\"jsonrpc\""));
        }
    }

    @Test(timeout = 15000)
    public void h2cUpgradePreservesTheXmlRpcResponse() throws Exception {
        // 独立 JDK 客户端通过 HTTP/1 Upgrade 协商明文 HTTP/2。
        MetConfiguration configuration = this.serverConfiguration();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .register();

            // XML-RPC 本身不参与传输升级，响应版本证明协商成功。
            int port = server.getListenAddress("xmlrpc").getPort();
            URI uri = URI.create("http://127.0.0.1:" + port + "/rpc");
            String body = """
                    <methodCall><methodName>demo.echo</methodName><params>
                    <param><value><string>h2c</string></value></param>
                    </params></methodCall>
                    """;
            HttpRequest request = HttpRequest.newBuilder(uri) //
                    .timeout(Duration.ofSeconds(5)) //
                    .header("Content-Type", "text/xml") //
                    .POST(HttpRequest.BodyPublishers.ofString(body)) //
                    .build();
            HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            // HTTP/2 升级保留正常 XML-RPC 结果。
            assertEquals(HttpClient.Version.HTTP_2, response.version());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("<string>h2c</string>"));
        }
    }

    @Test(timeout = 15000)
    public void businessFaultRetainsItsCodeAndMessageAtTheCaller() throws Exception {
        // 应用可以用协议公开的异常类型表达业务 fault。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration(1);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, new XmlRpcDemoServiceImpl()) //
                    .aliasName("xmlrpc", "demo") //
                    .register();
            MetBindInfo<XmlRpcDemoService> service = client.publisher() //
                    .rsfService(XmlRpcDemoService.class) //
                    .aliasName("xmlrpc", "demo") //
                    .register();

            // 标准 fault 经 Caller 返回给调用者，不变成超时或普通字符串。
            MetClient remote = client.getRsfClient(server.getListenAddress("xmlrpc"));
            Class<?>[] parameterTypes = { String.class };
            Object[] arguments = { "business rejected" };
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                remote.syncInvoke(service, "fail", parameterTypes, arguments);
            });
            assertTrue(failure.getCause() instanceof XmlRpcException);
            XmlRpcException fault = (XmlRpcException) failure.getCause();
            assertEquals(42, fault.getCode());
            assertEquals("business rejected", fault.getMessage());
        }
    }

    @Test(timeout = 20000)
    public void closingTheServerFailsPendingCallsWithoutWaitingForTimeout() throws Exception {
        // 阻塞一次业务执行，观察连接关闭对在途调用的终止通知。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration(2).setRequestTimeout(10000);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        XmlRpcDemoServiceImpl implementation = new XmlRpcDemoServiceImpl() {
            @Override
            public String echo(String value) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test release timed out");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
                return value;
            }
        };
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(XmlRpcDemoService.class, implementation) //
                    .aliasName("xmlrpc", "demo") //
                    .register();
            MetBindInfo<XmlRpcDemoService> service = client.publisher() //
                    .rsfService(XmlRpcDemoService.class) //
                    .aliasName("xmlrpc", "demo").timeout(10000) //
                    .register();
            MetClient remote = client.getRsfClient(server.getListenAddress("xmlrpc"));

            // 在业务期限之前关闭服务端，客户端应直接感知通道终止。
            try {
                Class<?>[] parameterTypes = { String.class };
                MetFuture pending = remote.asyncInvoke(service, "echo", parameterTypes, new Object[] { "blocked" });
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                server.close();
                ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                    pending.getData(5, TimeUnit.SECONDS);
                });
                assertNotNull(failure.getCause());
                assertFalse(failure.getCause() instanceof MetTimeoutException);
            } finally {
                release.countDown();
            }
        }
    }

    private MetConfiguration serverConfiguration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("xmlrpc");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("xmlrpc").contextPath("/rpc");
        return configuration;
    }

    private MetConfiguration clientConfiguration(int httpVersion) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("xmlrpc");
        configuration.connector("http").option("httpVersion", httpVersion) //
                .protocol("xmlrpc").contextPath("/rpc");
        return configuration;
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.composition.http;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.example.composition.http.HttpCompositionConfiguration;
import net.hasor.example.composition.http.HttpCompositionService;
import net.hasor.example.composition.http.HttpCompositionServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Timeout;

/** 协议共用监听后，错误、取消和服务选择仍保持隔离。 */
public class HttpProtocolIsolationTest {
    @NxnRpc(name = "jsonrpc", version = "2.0")
    @NxnRpc(name = "soap", version = "1.1")
    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void anAliasDoesNotExposeTheServiceThroughAnExcludedProtocol() throws Exception {
        // 所有协议均已挂载，但服务只选择 JSON-RPC，SOAP 别名不等于开放 SOAP。
        MetConfiguration incoming = HttpCompositionConfiguration.incoming();
        MetConfiguration outgoing = HttpCompositionConfiguration.outgoing(2);
        AtomicInteger calls = new AtomicInteger();
        HttpCompositionService implementation = value -> {
            calls.incrementAndGet();
            return "json:" + value;
        };
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(HttpCompositionService.class, implementation) //
                    .aliasName("jsonrpc", "echo").aliasName("soap", "echo").protocol("jsonrpc").register();
            MetBindInfo<HttpCompositionService> soap = client.publisher().rsfService(HttpCompositionService.class) //
                    .aliasName("soap", "echo").protocol("soap").register();

            // 请求不能借另一个已声明的别名绕过服务的协议可见范围。
            MetClient denied = client.getRsfClient(server.getListenAddress("soap"));
            Class<?>[] types = { String.class };
            Object[] arguments = { "hidden" };
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                denied.asyncInvoke(soap, "echo", types, arguments).getData(5, TimeUnit.SECONDS);
            });
            assertNotNull(failure.getCause());
            assertEquals(0, calls.get());
        }
    }

    @NxnRpc(name = "jsonrpc", version = "2.0")
    @NxnRpc(name = "soap", version = "1.1")
    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void malformedJsonDoesNotInterruptTheSoapRoute() throws Exception {
        // 一个端口同时挂载 JSON-RPC 和 SOAP，畸形报文只能影响被选中的协议。
        MetConfiguration incoming = HttpCompositionConfiguration.incoming();
        MetConfiguration outgoing = HttpCompositionConfiguration.outgoing(1);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(HttpCompositionService.class, new HttpCompositionServiceImpl("soap:")) //
                    .aliasName("soap", "echo").protocol("soap").register();
            MetBindInfo<HttpCompositionService> soap = client.publisher().rsfService(HttpCompositionService.class) //
                    .aliasName("soap", "echo").protocol("soap").register();

            // 独立标准 HTTP 客户端发送错误 JSON，确认协议返回明确解析错误。
            int port = server.getListenAddress("jsonrpc").getPort();
            URI uri = URI.create("http://127.0.0.1:" + port + "/jsonrpc");
            HttpRequest malformed = HttpRequest.newBuilder(uri) //
                    .header("Content-Type", "application/json") //
                    .POST(HttpRequest.BodyPublishers.ofString("{broken")).build();
            HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            // 外层 Future 使用单调时钟限定五秒；宿主墙钟跳变不应提前触发 JDK 请求计时器。
            Future<HttpResponse<String>> exchange = http.sendAsync(malformed, HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> rejected;
            try {
                rejected = exchange.get(5, TimeUnit.SECONDS);
            } finally {
                exchange.cancel(true);
            }
            JsonNode error = new ObjectMapper().readTree(rejected.body()).path("error");
            assertEquals(-32700, error.path("code").asInt());

            // 同一个监听上的 SOAP 服务仍可正常调用，未受到错误报文污染。
            HttpCompositionService remote = client.getRsfClient(server.getListenAddress("soap")).getRemote(soap);
            assertEquals("soap:survives", remote.echo("survives"));
        }
    }

    @NxnRpc(name = "jsonrpc", version = "2.0")
    @NxnRpc(name = "soap", version = "1.1")
    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void aNestedSoapMountWinsOverItsJsonRpcParent() throws Exception {
        // 两个协议的 context 存在父子关系，监听必须按完整路径选择更具体的挂载。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("jsonrpc");
        ConnectorSettings endpoint = incoming.connector("http").bind("127.0.0.1", 0);
        endpoint.protocol("jsonrpc").contextPath("/api");
        endpoint.protocol("soap").contextPath("/api/soap");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("soap");
        outgoing.connector("http").protocol("soap").contextPath("/api/soap");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(HttpCompositionService.class, new HttpCompositionServiceImpl("nested:")) //
                    .aliasName("soap", "echo").protocol("soap").register();
            MetBindInfo<HttpCompositionService> soap = client.publisher().rsfService(HttpCompositionService.class) //
                    .aliasName("soap", "echo").protocol("soap").register();

            // SOAP 的 XML 请求不能落入父路径 JSON 解码器。
            int port = server.getListenAddress("jsonrpc").getPort();
            assertEquals(port, server.getListenAddress("soap").getPort());
            HttpCompositionService remote = client.getRsfClient(server.getListenAddress("soap")).getRemote(soap);
            assertEquals("nested:selected", remote.echo("selected"));
        }
    }

    @NxnRpc(name = "jsonrpc", version = "2.0")
    @NxnRpc(name = "xmlrpc")
    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void cancellingJsonRpcDoesNotCancelAnXmlRpcCallOnTheListener() throws Exception {
        // 被阻塞的 JSON-RPC 调用和 XML-RPC 服务分别拥有自己的协议会话。
        // 为阻塞中的 JSON 和仍需执行的 XML 预留两个业务线程，避免默认单核心线程将后者排队。
        MetConfiguration incoming = HttpCompositionConfiguration.incoming().setQueueMinPoolSize(2);
        MetConfiguration outgoing = HttpCompositionConfiguration.outgoing(2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpCompositionService blocked = value -> {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Blocked service was not released");
                }
                return value;
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
        };
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(HttpCompositionService.class, blocked) //
                    .group("jsonrpc").aliasName("jsonrpc", "echo").protocol("jsonrpc").register();
            server.publisher().rsfService(HttpCompositionService.class, new HttpCompositionServiceImpl("xml:")) //
                    .group("xmlrpc").aliasName("xmlrpc", "echo").protocol("xmlrpc").register();
            MetBindInfo<HttpCompositionService> json = client.publisher().rsfService(HttpCompositionService.class) //
                    .group("jsonrpc").aliasName("jsonrpc", "echo").protocol("jsonrpc").register();
            MetBindInfo<HttpCompositionService> xml = client.publisher().rsfService(HttpCompositionService.class) //
                    .group("xmlrpc").aliasName("xmlrpc", "echo").protocol("xmlrpc").register();
            try {
                // 明确等待业务开始，随后只取消这一条请求。
                MetClient jsonClient = client.getRsfClient(server.getListenAddress("jsonrpc"));
                Class<?>[] types = { String.class };
                Object[] arguments = { "blocked" };
                MetFuture pending = jsonClient.asyncInvoke(json, "echo", types, arguments);
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTrue(pending.cancel());
                assertTrue(pending.isCancelled());

                // XML-RPC 使用相同监听端口，仍独立完成它自己的请求。
                HttpCompositionService survivor = client.getRsfClient(server.getListenAddress("xmlrpc")) //
                        .getRemote(xml);
                assertEquals("xml:survives", survivor.echo("survives"));
            } finally {
                release.countDown();
            }
        }
    }
}

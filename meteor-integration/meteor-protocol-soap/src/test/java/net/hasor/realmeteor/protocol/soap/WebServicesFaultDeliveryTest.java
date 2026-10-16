/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.soap;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.example.protocol.soap.EchoService;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 并发新连接上的即时 Fault 必须全部送达，不能依赖业务执行延迟完成网络初始化。 */
public class WebServicesFaultDeliveryTest {
    @Test(timeout = 30000)
    public void freshConnectionsReceiveSoap11VersionFaults() throws Exception {
        // 从公开入口启动 SOAP 1.1 服务，观察非法报文是否误入业务调用。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("soap").contextPath("/services");
        AtomicInteger invocations = new AtomicInteger();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher().rsfService(EchoService.class, value -> {
                invocations.incrementAndGet();
                return value;
            }).aliasName("soap", "EchoService").protocol("soap").register();

            // 同时建立独立连接，以首个请求触发不经过 Dispatcher 的 VersionMismatch。
            int port = context.bindAddress("soap").getPort();
            URI endpoint = URI.create("http://127.0.0.1:" + port + "/services/EchoService");
            String body = "<soap:Envelope xmlns:soap=\"urn:wrong:soap\"><soap:Body/></soap:Envelope>";
            HttpRequest request = HttpRequest.newBuilder(endpoint) //
                    .header("Content-Type", "text/xml; charset=utf-8") //
                    .header("SOAPAction", "\"urn:hasor:meteor:soap:EchoService/echo\"") //
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            List<Future<HttpResponse<String>>> responses = new ArrayList<>();
            try {
                for (int index = 0; index < 24; index++) {
                    HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1) //
                            .connectTimeout(Duration.ofSeconds(15)).build();
                    responses.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
                }

                // 有界等待每个网络响应并检查完整 Fault，循环只表示同一场景的并发负载。
                for (Future<HttpResponse<String>> pending : responses) {
                    HttpResponse<String> response = pending.get(5, TimeUnit.SECONDS);
                    assertEquals(HttpClient.Version.HTTP_1_1, response.version());
                    assertEquals(500, response.statusCode());
                    assertTrue(response.body(), response.body().contains("soap:VersionMismatch"));
                }
                assertEquals(0, invocations.get());
            } finally {
                for (Future<HttpResponse<String>> pending : responses) {
                    pending.cancel(true);
                }
            }
        }
    }

    @Test(timeout = 30000)
    public void freshConnectionsReceiveSoap12StructureFaults() throws Exception {
        // 从公开入口启动 SOAP 1.2 服务，非法结构必须在业务调用前被拒绝。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("soap").contextPath("/services").option("soapVersion", "1.2");
        AtomicInteger invocations = new AtomicInteger();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher().rsfService(EchoService.class, value -> {
                invocations.incrementAndGet();
                return value;
            }).aliasName("soap", "EchoService").protocol("soap").register();

            // 每个新连接的首个请求都携带 Envelope 上不允许的 encodingStyle。
            int port = context.bindAddress("soap").getPort();
            URI endpoint = URI.create("http://127.0.0.1:" + port + "/services/EchoService");
            String body = """
                    <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
                                   soap:encodingStyle="urn:test:encoding">
                        <soap:Body/>
                    </soap:Envelope>
                    """;
            HttpRequest request = HttpRequest.newBuilder(endpoint) //
                    .header("Content-Type", "application/soap+xml; charset=utf-8") //
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            List<Future<HttpResponse<String>>> responses = new ArrayList<>();
            try {
                for (int index = 0; index < 24; index++) {
                    HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1) //
                            .connectTimeout(Duration.ofSeconds(15)).build();
                    responses.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
                }

                // 所有首请求都收到 Sender Fault，不靠重试或延迟等待服务端进入另一状态。
                for (Future<HttpResponse<String>> pending : responses) {
                    HttpResponse<String> response = pending.get(5, TimeUnit.SECONDS);
                    assertEquals(HttpClient.Version.HTTP_1_1, response.version());
                    assertEquals(400, response.statusCode());
                    assertTrue(response.body(), response.body().contains("soap:Sender"));
                }
                assertEquals(0, invocations.get());
            } finally {
                for (Future<HttpResponse<String>> pending : responses) {
                    pending.cancel(true);
                }
            }
        }
    }
}

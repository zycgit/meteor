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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.hasor.example.protocol.soap.BlockingSignalServiceImpl;
import net.hasor.example.protocol.soap.SignalService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebServicesNotificationTest {
    @Test(timeout = 20000)
    public void oneWayReturnsAcceptedWhileBusinessExecutionContinues() throws Exception {
        // 由 Java 消息注解生成 one-way 契约，业务等待场景显式释放。
        BlockingSignalServiceImpl service = new BlockingSignalServiceImpl();
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("soap") //
                .contextPath("/services");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(SignalService.class, service) //
                    .aliasName("soap", "SignalService") //
                    .protocol("soap") //
                    .register();

            // 原始 SOAP 客户端发送 one-way 请求，HTTP 完成不等待业务方法结束。
            int port = context.bindAddress("soap").getPort();
            URI endpoint = URI.create("http://127.0.0.1:" + port + "/services/SignalService");
            String action = "urn:hasor:meteor:soap:SignalService/signal";
            HttpRequest request = this.soapRequest(endpoint, action, this.signalEnvelope("notice"));
            try {
                HttpResponse<byte[]> response = this.http().send(request, HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(202, response.statusCode());
                assertEquals(0, response.body().length);
                assertTrue("Business method was not entered", service.awaitEntered(5, TimeUnit.SECONDS));
                assertFalse("Business method finished before the caller returned", service.isFinished());
            } finally {
                service.release();
            }

            // 解除阻塞后，业务仍在后台完成并收到原始参数。
            assertTrue("Business method did not finish", service.awaitFinished(5, TimeUnit.SECONDS));
            assertEquals("notice", service.getMessage());
        }
    }

    @Test(timeout = 20000)
    public void meteorClientReadsMessageAnnotationBeforeCallerFilters() throws Exception {
        // 服务端业务进入后保持阻塞，客户端过滤器记录调用模式。
        BlockingSignalServiceImpl service = new BlockingSignalServiceImpl();
        AtomicBoolean observedMessage = new AtomicBoolean();
        MetConfiguration incoming = this.incomingConfiguration();
        MetConfiguration outgoing = this.outgoingConfiguration();
        ExecutorService invocation = Executors.newSingleThreadExecutor();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端从公开 Bootstrap 启动，并分别发布与订阅同一 Java 消息接口。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(SignalService.class, service) //
                    .aliasName("soap", "SignalService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<SignalService> subscribed = client.publisher() //
                    .rsfService(SignalService.class) //
                    .aliasName("soap", "SignalService") //
                    .protocol("soap") //
                    .bindFilter("observe-message", (request, response, chain) -> {
                        observedMessage.set(request.isMessage());
                        chain.doFilter(request, response);
                    }) //
                    .register();

            // Meteor 代理调用在业务仍阻塞时完成，证明客户端只等待远端入队回执。
            MetClient caller = client.getRsfClient(server.bindAddress("soap"));
            SignalService remote = caller.getRemote(subscribed);
            Future<?> call = invocation.submit(() -> remote.signal("notice"));
            try {
                assertTrue("Business method was not entered", service.awaitEntered(5, TimeUnit.SECONDS));
                call.get(2, TimeUnit.SECONDS);
                assertTrue("Caller filter did not see message mode", observedMessage.get());
                assertFalse("Business method finished before acknowledgement", service.isFinished());
            } finally {
                service.release();
            }

            // 回执后解除业务阻塞，确认异步工作最终完成。
            assertTrue("Business method did not finish", service.awaitFinished(5, TimeUnit.SECONDS));
        } finally {
            service.release();
            invocation.shutdownNow();
        }
    }

    private MetConfiguration incomingConfiguration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("soap") //
                .contextPath("/services");
        return configuration;
    }

    private MetConfiguration outgoingConfiguration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .protocol("soap") //
                .contextPath("/services");
        return configuration;
    }

    private HttpClient http() {
        return HttpClient.newBuilder() //
                .connectTimeout(Duration.ofSeconds(15)) //
                .version(HttpClient.Version.HTTP_1_1) //
                .build();
    }

    private HttpRequest soapRequest(URI endpoint, String action, byte[] body) {
        return HttpRequest.newBuilder(endpoint) //
                .timeout(Duration.ofSeconds(15)) //
                .header("Content-Type", "text/xml; charset=utf-8") //
                .header("SOAPAction", "\"" + action + "\"") //
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)) //
                .build();
    }

    private byte[] signalEnvelope(String message) {
        String envelope = """
                <?xml version="1.0" encoding="UTF-8"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                    <soap:Body>
                        <types:signal xmlns:types="urn:hasor:meteor:soap:SignalService">
                            <arg0>%s</arg0>
                        </types:signal>
                    </soap:Body>
                </soap:Envelope>
                """.formatted(message);
        return envelope.getBytes(StandardCharsets.UTF_8);
    }
}

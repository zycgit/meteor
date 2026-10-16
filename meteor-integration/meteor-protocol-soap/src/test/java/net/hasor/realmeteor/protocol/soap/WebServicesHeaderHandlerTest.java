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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.example.protocol.soap.EchoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetResponse;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.protocol.soap.support.SoapHeadersExtension;
import net.hasor.realmeteor.protocol.soap.support.TraceHeaderHandler;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WebServicesHeaderHandlerTest {
    @Test(timeout = 20000)
    public void soap12HandlerProcessesMandatoryHeadersInBothDirections() throws Exception {
        // 两端显式安装 Header 处理器，并承担自定义 trace role。
        MetConfiguration incoming = this.incoming("1.2");
        incoming.addExtension(new SoapHeadersExtension(new TraceHeaderHandler()));
        MetConfiguration outgoing = this.outgoing("1.2");
        outgoing.addExtension(new SoapHeadersExtension(new TraceHeaderHandler()));
        AtomicReference<String> requestTrace = new AtomicReference<>();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(EchoService.class, message -> message) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .bindFilter("trace", (request, response, chain) -> {
                        requestTrace.set(request.getOption("requestTrace"));
                        chain.doFilter(request, response);
                    }) //
                    .register();
            MetBindInfo<EchoService> service = client.publisher() //
                    .rsfService(EchoService.class) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 请求和响应各有一个 mustUnderstand=true 的 Trace block，均经过真实 HTTP。
            MetResponse response = client.getRsfClient(server.bindAddress("soap")) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "hello" }) //
                    .get(5, TimeUnit.SECONDS);
            assertEquals("hello", response.getData());
            assertEquals("client trace", requestTrace.get());
            assertEquals("server trace", response.getOption("responseTrace"));
        }
    }

    @Test(timeout = 20000)
    public void soap11HandlerProcessesActorHeadersInBothDirections() throws Exception {
        // SOAP 1.1 同样通过正式扩展提供处理器，使用 actor 和数字布尔值。
        MetConfiguration incoming = this.incoming("1.1");
        incoming.addExtension(new SoapHeadersExtension(new TraceHeaderHandler()));
        MetConfiguration outgoing = this.outgoing("1.1");
        outgoing.addExtension(new SoapHeadersExtension(new TraceHeaderHandler()));
        AtomicReference<String> requestTrace = new AtomicReference<>();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(EchoService.class, message -> message) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .bindFilter("trace", (request, response, chain) -> {
                        requestTrace.set(request.getOption("requestTrace"));
                        chain.doFilter(request, response);
                    }) //
                    .register();
            MetBindInfo<EchoService> service = client.publisher() //
                    .rsfService(EchoService.class) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 接收方声明相应 actor，理解必选 Header 后继续调用业务。
            MetResponse response = client.getRsfClient(server.bindAddress("soap")) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "hello" }) //
                    .get(5, TimeUnit.SECONDS);
            assertEquals("hello", response.getData());
            assertEquals("client trace", requestTrace.get());
            assertEquals("server trace", response.getOption("responseTrace"));
        }
    }

    @Test(timeout = 20000)
    public void handlerCannotSilentlyAcceptAnUnknownMandatoryHeader() throws Exception {
        // 已安装处理器只理解 Trace，不能因存在处理器就跳过其他必选头。
        MetConfiguration configuration = this.incoming("1.2");
        configuration.addExtension(new SoapHeadersExtension(new TraceHeaderHandler()));
        AtomicInteger invocations = new AtomicInteger();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, message -> {
                        invocations.incrementAndGet();
                        return message;
                    }) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 未知头声明必选，同时正文缺少参数；必须先报告 MustUnderstand。
            String envelope = """
                    <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:t="urn:unknown">
                        <s:Header><t:Unknown s:mustUnderstand="true"/></s:Header>
                        <s:Body><m:echo xmlns:m="urn:hasor:meteor:soap:EchoService"/></s:Body>
                    </s:Envelope>
                    """;
            int port = context.bindAddress("soap").getPort();
            URI endpoint = URI.create("http://127.0.0.1:" + port + "/services/EchoService");
            String contentType = "application/soap+xml; action=\"urn:hasor:meteor:soap:EchoService/echo\"";
            HttpRequest request = HttpRequest.newBuilder(endpoint) //
                    .timeout(Duration.ofSeconds(15)) //
                    .header("Content-Type", contentType) //
                    .POST(HttpRequest.BodyPublishers.ofString(envelope)) //
                    .build();
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(500, response.statusCode());
            assertTrue(response.body(), response.body().contains("soap:MustUnderstand"));
            assertEquals(0, invocations.get());
        }
    }

    private MetConfiguration incoming(String version) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("soap") //
                .option("soapVersion", version) //
                .contextPath("/services");
        return configuration;
    }

    private MetConfiguration outgoing(String version) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .protocol("soap") //
                .option("soapVersion", version) //
                .contextPath("/services");
        return configuration;
    }
}

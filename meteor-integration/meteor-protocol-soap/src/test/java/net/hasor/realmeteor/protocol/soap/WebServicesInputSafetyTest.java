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
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.example.protocol.soap.EchoService;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.protocol.soap.support.SoapResponseUtils;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WebServicesInputSafetyTest {
    private static final String SOAP_ACTOR_NEXT = "http://schemas.xmlsoap.org/soap/actor/next";

    @Test
    public void mandatoryHeaderForDefaultActorIsRejectedBeforeInvocation() throws Exception {
        // 启动公开 SOAP 端点并记录业务方法是否被调用。
        MetConfiguration configuration = this.configuration();
        AtomicInteger invocations = new AtomicInteger();
        try (MetContext context = configuration.buildContext()) {
            // 启动端点并从普通 Java 接口生成 SOAP 契约。
            context.start();
            EchoService service = message -> {
                invocations.incrementAndGet();
                return message;
            };
            context.publisher() //
                    .rsfService(EchoService.class, service) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 缺省 actor 的未知必选 Header 面向最终接收者。
            String header = "<test:Required soap:mustUnderstand=\"1\"/>";
            URI endpoint = this.endpoint(context);
            byte[] envelope = this.echoEnvelope(header, "required");
            HttpRequest request = this.request(endpoint, envelope);
            HttpClient http = this.httpClient();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            // 协议返回 MustUnderstand，业务方法没有开始执行。
            assertEquals(500, response.statusCode());
            assertTrue(response.body(), response.body().contains("soap:MustUnderstand"));
            assertEquals(0, invocations.get());
        }
    }

    @Test
    public void mandatoryHeaderForNextActorIsRejectedBeforeInvocation() throws Exception {
        // 启动公开 SOAP 端点并记录业务方法是否被调用。
        MetConfiguration configuration = this.configuration();
        AtomicInteger invocations = new AtomicInteger();
        try (MetContext context = configuration.buildContext()) {
            // 启动端点并从普通 Java 接口生成 SOAP 契约。
            context.start();
            EchoService service = message -> {
                invocations.incrementAndGet();
                return message;
            };
            context.publisher() //
                    .rsfService(EchoService.class, service) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // SOAP next actor 明确要求当前节点理解 Header。
            String actor = " soap:actor=\"" + SOAP_ACTOR_NEXT + "\"";
            String header = "<test:Required soap:mustUnderstand=\"1\"" + actor + "/>";
            URI endpoint = this.endpoint(context);
            byte[] envelope = this.echoEnvelope(header, "required");
            HttpRequest request = this.request(endpoint, envelope);
            HttpClient http = this.httpClient();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            // 当前节点拒绝未知必选 Header，业务方法没有开始执行。
            assertEquals(500, response.statusCode());
            assertTrue(response.body(), response.body().contains("soap:MustUnderstand"));
            assertEquals(0, invocations.get());
        }
    }

    @Test
    public void mandatoryHeaderForAnotherActorIsIgnored() throws Exception {
        // 启动公开 SOAP 端点并记录业务方法调用次数。
        MetConfiguration configuration = this.configuration();
        AtomicInteger invocations = new AtomicInteger();
        try (MetContext context = configuration.buildContext()) {
            // 启动端点并从普通 Java 接口生成 SOAP 契约。
            context.start();
            EchoService service = message -> {
                invocations.incrementAndGet();
                return message;
            };
            context.publisher() //
                    .rsfService(EchoService.class, service) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 发给其他 actor 的 Header 不要求当前节点处理。
            String actor = " soap:actor=\"urn:meteor:test:another-node\"";
            String header = "<test:Required soap:mustUnderstand=\"1\"" + actor + "/>";
            URI endpoint = this.endpoint(context);
            byte[] envelope = this.echoEnvelope(header, "forwarded");
            HttpRequest request = this.request(endpoint, envelope);
            HttpClient http = this.httpClient();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            // 请求正常进入业务服务。
            assertEquals(200, response.statusCode());
            assertTrue(response.body(), response.body().contains("forwarded"));
            assertEquals(1, invocations.get());
        }
    }

    @Test
    public void optionalHeaderForDefaultActorIsIgnored() throws Exception {
        // 发布可观察调用次数的服务，验证可选 Header 不会阻止普通请求。
        MetConfiguration configuration = this.configuration();
        AtomicInteger invocations = new AtomicInteger();
        try (MetContext context = configuration.buildContext()) {
            // 启动端点并从普通 Java 接口生成 SOAP 契约。
            context.start();
            EchoService service = message -> {
                invocations.incrementAndGet();
                return message;
            };
            context.publisher() //
                    .rsfService(EchoService.class, service) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 缺省 actor 的未知 Header 显式声明为非必选。
            String header = "<test:Optional soap:mustUnderstand=\"0\"/>";
            URI endpoint = this.endpoint(context);
            byte[] envelope = this.echoEnvelope(header, "optional");
            HttpRequest request = this.request(endpoint, envelope);
            HttpClient http = this.httpClient();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            // Header 被忽略，业务方法恰好执行一次并返回完整结果。
            assertEquals(200, response.statusCode());
            assertEquals("optional", SoapResponseUtils.returnText(response.body()));
            assertEquals(1, invocations.get());
        }
    }

    @Test
    public void documentTypeIsRejectedBeforeInvocation() throws Exception {
        // 发布可观察调用次数的服务，检查实体声明不能进入业务反序列化。
        MetConfiguration configuration = this.configuration();
        AtomicInteger invocations = new AtomicInteger();
        try (MetContext context = configuration.buildContext()) {
            // 启动端点并从普通 Java 接口生成 SOAP 契约。
            context.start();
            EchoService service = message -> {
                invocations.incrementAndGet();
                return message;
            };
            context.publisher() //
                    .rsfService(EchoService.class, service) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 在普通回显请求中嵌入 DOCTYPE 和内部实体引用。
            URI endpoint = this.endpoint(context);
            HttpRequest request = this.request(endpoint, this.documentTypeEnvelope());
            HttpClient http = this.httpClient();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            // XML 解析阶段返回 SOAP Client Fault，实体值没有交给业务方法。
            assertEquals(500, response.statusCode());
            String responseBody = response.body();
            assertTrue(responseBody, responseBody.contains("soap:Client"));
            assertTrue(responseBody, responseBody.contains("DOCTYPE"));
            assertEquals(0, invocations.get());
        }
    }

    @Test
    public void deeplyNestedXmlReturnsClientFaultWithoutInvocation() throws Exception {
        // 发布可观察调用次数的服务，保留 SOAP DOM 的默认深度限制。
        MetConfiguration configuration = this.configuration();
        AtomicInteger invocations = new AtomicInteger();
        try (MetContext context = configuration.buildContext()) {
            // 启动端点并从普通 Java 接口生成 SOAP 契约。
            context.start();
            EchoService service = message -> {
                invocations.incrementAndGet();
                return message;
            };
            context.publisher() //
                    .rsfService(EchoService.class, service) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 发送远超限制的嵌套正文，验证网络通道仍能返回协议错误。
            URI endpoint = this.endpoint(context);
            HttpRequest request = this.request(endpoint, this.deepEnvelope(160));
            HttpClient http = this.httpClient();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            // 结构在 Transformer/Jackson 前被拒绝，并返回 SOAP Client Fault。
            assertEquals(500, response.statusCode());
            assertTrue(response.body(), response.body().contains("soap:Client"));
            assertTrue(response.body(), response.body().contains("XML nesting depth"));
            assertEquals(0, invocations.get());
        }
    }

    @Test
    public void excessiveXmlNodesReturnClientFaultWithoutInvocation() throws Exception {
        // 发布可观察调用次数的服务，保留 SOAP DOM 的默认节点数量限制。
        MetConfiguration configuration = this.configuration();
        AtomicInteger invocations = new AtomicInteger();
        try (MetContext context = configuration.buildContext()) {
            // 启动端点并从普通 Java 接口生成 SOAP 契约。
            context.start();
            EchoService service = message -> {
                invocations.incrementAndGet();
                return message;
            };
            context.publisher() //
                    .rsfService(EchoService.class, service) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 发送大量同级元素，避免用深度限制替代节点总量限制。
            URI endpoint = this.endpoint(context);
            HttpRequest request = this.request(endpoint, this.wideEnvelope(100_100));
            HttpClient http = this.httpClient();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            // 节点总量在业务反序列化前被拒绝。
            assertEquals(500, response.statusCode());
            assertTrue(response.body(), response.body().contains("soap:Client"));
            assertTrue(response.body(), response.body().contains("too many XML nodes"));
            assertEquals(0, invocations.get());
        }
    }

    private MetConfiguration configuration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("soap") //
                .contextPath("/services");
        return configuration;
    }

    private URI endpoint(MetContext context) {
        int port = context.bindAddress("soap").getPort();
        return URI.create("http://127.0.0.1:" + port + "/services/EchoService");
    }

    private HttpRequest request(URI endpoint, byte[] body) {
        return HttpRequest.newBuilder(endpoint) //
                .timeout(Duration.ofSeconds(15)) //
                .header("Content-Type", "text/xml; charset=utf-8") //
                .header("SOAPAction", "\"urn:hasor:meteor:soap:EchoService/echo\"") //
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)) //
                .build();
    }

    private HttpClient httpClient() {
        return HttpClient.newBuilder() //
                .connectTimeout(Duration.ofSeconds(15)) //
                .version(HttpClient.Version.HTTP_1_1) //
                .build();
    }

    private byte[] echoEnvelope(String header, String message) {
        String envelope = """
                <?xml version="1.0" encoding="UTF-8"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                               xmlns:test="urn:meteor:test:header">
                    <soap:Header>%s</soap:Header>
                    <soap:Body>
                        <tns:echo xmlns:tns="urn:hasor:meteor:soap:EchoService">
                            <arg0>%s</arg0>
                        </tns:echo>
                    </soap:Body>
                </soap:Envelope>
                """.formatted(header, message);
        return envelope.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] deepEnvelope(int depth) {
        // 生成同一请求内的多层嵌套负载，触发 DOM 深度限制。
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < depth; index++) {
            value.append("<level>");
        }
        value.append("deep");
        for (int index = 0; index < depth; index++) {
            value.append("</level>");
        }
        return this.bodyEnvelope(value.toString());
    }

    private byte[] wideEnvelope(int count) {
        // 生成同一请求内的大量平级节点，独立触发 DOM 总量限制。
        StringBuilder value = new StringBuilder("wide");
        for (int index = 0; index < count; index++) {
            value.append("<extra/>");
        }
        return this.bodyEnvelope(value.toString());
    }

    private byte[] documentTypeEnvelope() {
        String envelope = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE soap:Envelope [<!ENTITY supplied "expanded entity">]>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                    <soap:Body>
                        <tns:echo xmlns:tns="urn:hasor:meteor:soap:EchoService">
                            <arg0>&supplied;</arg0>
                        </tns:echo>
                    </soap:Body>
                </soap:Envelope>
                """;
        return envelope.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] bodyEnvelope(String messageContent) {
        String envelope = """
                <?xml version="1.0" encoding="UTF-8"?>
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                    <soap:Body>
                        <tns:echo xmlns:tns="urn:hasor:meteor:soap:EchoService">
                            <arg0>%s</arg0>
                        </tns:echo>
                    </soap:Body>
                </soap:Envelope>
                """.formatted(messageContent);
        return envelope.getBytes(StandardCharsets.UTF_8);
    }
}

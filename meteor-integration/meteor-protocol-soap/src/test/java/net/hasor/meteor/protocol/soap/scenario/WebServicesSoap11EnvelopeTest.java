/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.scenario;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import javax.xml.parsers.DocumentBuilderFactory;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.soap.support.EchoService;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import static org.junit.Assert.*;

public class WebServicesSoap11EnvelopeTest {
    private static final String NAMESPACE    = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String ACTION       = "urn:hasor:meteor:soap:EchoService/echo";
    private static final String CONTENT_TYPE = "text/xml; charset=utf-8";
    private static final String OPERATION    = """
            <m:echo xmlns:m="urn:hasor:meteor:soap:EchoService"><arg0>hello</arg0></m:echo>
            """;

    @Test(timeout = 20000)
    public void wrongEnvelopeNamespaceReturnsVersionMismatch() throws Exception {
        // 从公开入口启动默认 SOAP 1.1 服务并观察业务调用。
        AtomicInteger invocations = new AtomicInteger();
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, value -> {
                        invocations.incrementAndGet();
                        return value;
                    }) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // Envelope 命名空间错误时返回标准 VersionMismatch。
            String envelope = this.envelope("", OPERATION).replace(NAMESPACE, "urn:wrong:soap");
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .timeout(Duration.ofSeconds(15)) //
                    .header("Content-Type", CONTENT_TYPE) //
                    .header("SOAPAction", "\"" + ACTION + "\"") //
                    .POST(HttpRequest.BodyPublishers.ofString(envelope)) //
                    .build();
            HttpResponse<String> response = this.http().send(request, HttpResponse.BodyHandlers.ofString());

            // Fault 使用 1.1 的非限定 faultcode/faultstring，且未进入业务方法。
            assertEquals(500, response.statusCode());
            assertEquals(0, invocations.get());
            Document fault = this.document(response.body());
            Element code = (Element) fault.getElementsByTagName("faultcode").item(0);
            assertNotNull(code);
            assertEquals("soap:VersionMismatch", code.getTextContent());
            assertEquals(NAMESPACE, code.lookupNamespaceURI("soap"));
            assertEquals(1, fault.getElementsByTagName("faultstring").getLength());
            assertEquals(0, fault.getElementsByTagName("detail").getLength());
        }
    }

    @Test(timeout = 20000)
    public void mandatoryHeaderFaultDoesNotContainBodyDetail() throws Exception {
        // 从公开入口启动默认 SOAP 1.1 服务并观察业务调用。
        AtomicInteger invocations = new AtomicInteger();
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, value -> {
                        invocations.incrementAndGet();
                        return value;
                    }) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 必选 Header 错误不能伪造 Body 处理的 detail。
            String envelope = this.envelope("<t:Required soap:mustUnderstand=\"1\"/>", OPERATION);
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .timeout(Duration.ofSeconds(15)) //
                    .header("Content-Type", CONTENT_TYPE) //
                    .header("SOAPAction", "\"" + ACTION + "\"") //
                    .POST(HttpRequest.BodyPublishers.ofString(envelope)) //
                    .build();
            HttpResponse<String> response = this.http().send(request, HttpResponse.BodyHandlers.ofString());

            // Fault 使用 1.1 的非限定 faultcode/faultstring，且未进入业务方法。
            assertEquals(500, response.statusCode());
            assertEquals(0, invocations.get());
            Document fault = this.document(response.body());
            Element code = (Element) fault.getElementsByTagName("faultcode").item(0);
            assertNotNull(code);
            assertEquals("soap:MustUnderstand", code.getTextContent());
            assertEquals(NAMESPACE, code.lookupNamespaceURI("soap"));
            assertEquals(1, fault.getElementsByTagName("faultstring").getLength());
            assertEquals(0, fault.getElementsByTagName("detail").getLength());
        }
    }

    @Test(timeout = 20000)
    public void textBooleanIsInvalidForSoap11MustUnderstand() throws Exception {
        // 从公开入口启动默认 SOAP 1.1 服务并观察业务调用。
        AtomicInteger invocations = new AtomicInteger();
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, value -> {
                        invocations.incrementAndGet();
                        return value;
                    }) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // SOAP 1.1 的 mustUnderstand 只允许 0 或 1。
            String envelope = this.envelope("<t:Required soap:mustUnderstand=\"true\"/>", OPERATION);
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .timeout(Duration.ofSeconds(15)) //
                    .header("Content-Type", CONTENT_TYPE) //
                    .header("SOAPAction", "\"" + ACTION + "\"") //
                    .POST(HttpRequest.BodyPublishers.ofString(envelope)) //
                    .build();
            HttpResponse<String> response = this.http().send(request, HttpResponse.BodyHandlers.ofString());

            // Fault 使用 1.1 的非限定 faultcode/faultstring，且未进入业务方法。
            assertEquals(500, response.statusCode());
            assertEquals(0, invocations.get());
            Document fault = this.document(response.body());
            Element code = (Element) fault.getElementsByTagName("faultcode").item(0);
            assertNotNull(code);
            assertEquals("soap:Client", code.getTextContent());
            assertEquals(NAMESPACE, code.lookupNamespaceURI("soap"));
            assertEquals(1, fault.getElementsByTagName("faultstring").getLength());
            assertEquals(0, fault.getElementsByTagName("detail").getLength());
        }
    }

    @Test(timeout = 20000)
    public void duplicateBodyIsRejectedBeforeInvocation() throws Exception {
        // 从公开入口启动默认 SOAP 1.1 服务并观察业务调用。
        AtomicInteger invocations = new AtomicInteger();
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, value -> {
                        invocations.incrementAndGet();
                        return value;
                    }) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // SOAP 1.1 同样拒绝重复 Body。
            String envelope = this.envelope("", OPERATION).replace("</soap:Envelope>", "<soap:Body/> </soap:Envelope>");
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .timeout(Duration.ofSeconds(15)) //
                    .header("Content-Type", CONTENT_TYPE) //
                    .header("SOAPAction", "\"" + ACTION + "\"") //
                    .POST(HttpRequest.BodyPublishers.ofString(envelope)) //
                    .build();
            HttpResponse<String> response = this.http().send(request, HttpResponse.BodyHandlers.ofString());

            // Fault 使用 1.1 的非限定 faultcode/faultstring，且未进入业务方法。
            assertEquals(500, response.statusCode());
            assertEquals(0, invocations.get());
            Document fault = this.document(response.body());
            Element code = (Element) fault.getElementsByTagName("faultcode").item(0);
            assertNotNull(code);
            assertEquals("soap:Client", code.getTextContent());
            assertEquals(NAMESPACE, code.lookupNamespaceURI("soap"));
            assertEquals(1, fault.getElementsByTagName("faultstring").getLength());
            assertEquals(0, fault.getElementsByTagName("detail").getLength());
        }
    }

    @Test(timeout = 20000)
    public void processingInstructionIsRejectedBeforeInvocation() throws Exception {
        // 从公开入口启动默认 SOAP 1.1 服务并观察业务调用。
        AtomicInteger invocations = new AtomicInteger();
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, value -> {
                        invocations.incrementAndGet();
                        return value;
                    }) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // SOAP 1.1 不允许消息包含 processing instruction。
            String envelope = "<?test value?>" + this.envelope("", OPERATION);
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .timeout(Duration.ofSeconds(15)) //
                    .header("Content-Type", CONTENT_TYPE) //
                    .header("SOAPAction", "\"" + ACTION + "\"") //
                    .POST(HttpRequest.BodyPublishers.ofString(envelope)) //
                    .build();
            HttpResponse<String> response = this.http().send(request, HttpResponse.BodyHandlers.ofString());

            // Fault 使用 1.1 的非限定 faultcode/faultstring，且未进入业务方法。
            assertEquals(500, response.statusCode());
            assertEquals(0, invocations.get());
            Document fault = this.document(response.body());
            Element code = (Element) fault.getElementsByTagName("faultcode").item(0);
            assertNotNull(code);
            assertEquals("soap:Client", code.getTextContent());
            assertEquals(NAMESPACE, code.lookupNamespaceURI("soap"));
            assertEquals(1, fault.getElementsByTagName("faultstring").getLength());
            assertEquals(0, fault.getElementsByTagName("detail").getLength());
        }
    }

    @Test(timeout = 20000)
    public void qualifiedEnvelopeExtensionAfterBodyRemainsValid() throws Exception {
        // SOAP 1.1 支持 Body 之后的命名空间扩展，发布可观察的业务服务。
        AtomicInteger invocations = new AtomicInteger();
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, value -> {
                        invocations.incrementAndGet();
                        return value;
                    }) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 扩展是 Envelope 的独立子元素，不增加或替代业务 Body。
            String envelope = this.envelope("", OPERATION);
            envelope = envelope.replace("</soap:Envelope>", "<t:Audit>trace</t:Audit></soap:Envelope>");
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .timeout(Duration.ofSeconds(15)) //
                    .header("Content-Type", CONTENT_TYPE) //
                    .header("SOAPAction", "\"" + ACTION + "\"") //
                    .POST(HttpRequest.BodyPublishers.ofString(envelope)) //
                    .build();
            HttpResponse<String> response = this.http().send(request, HttpResponse.BodyHandlers.ofString());

            // 合法扩展没有妨碍正常 SOAP 1.1 调用。
            assertEquals(200, response.statusCode());
            assertEquals(1, invocations.get());
            assertTrue(response.body(), response.body().contains("hello"));
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

    private HttpClient http() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)) //
                .version(HttpClient.Version.HTTP_1_1).build();
    }

    private String envelope(String header, String body) {
        String headers = header.isEmpty() ? "" : "<soap:Header>" + header + "</soap:Header>";
        String opening = "<soap:Envelope xmlns:soap=\"" + NAMESPACE + "\" xmlns:t=\"urn:test:header\">";
        String envelopeBody = "<soap:Body>" + body + "</soap:Body>";
        return opening + headers + envelopeBody + "</soap:Envelope>";
    }

    private Document document(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}

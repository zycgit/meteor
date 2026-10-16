/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.soap;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import net.hasor.example.protocol.soap.EchoService;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class WebServicesSoap12StructuralAttributesTest {
    private static final String NAMESPACE    = "http://www.w3.org/2003/05/soap-envelope";
    private static final String ACTION       = "urn:hasor:meteor:soap:EchoService/echo";
    private static final String CONTENT_TYPE = "application/soap+xml; charset=utf-8; action=\"" + ACTION + "\"";
    private static final String OPERATION    = """
            <m:echo xmlns:m="urn:hasor:meteor:soap:EchoService"><arg0>hello</arg0></m:echo>
            """;

    @Test(timeout = 20000)
    public void unqualifiedEnvelopeAttributeIsRejected() throws Exception {
        // 从公开入口启动 SOAP 1.2 服务，记录是否进入业务方法。
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

            // Envelope 扩展属性必须具有命名空间。
            String envelope = this.envelope("", OPERATION);
            envelope = envelope.replace("<soap:Envelope ", "<soap:Envelope invalid=\"value\" ");
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .timeout(Duration.ofSeconds(15)) //
                    .header("Content-Type", CONTENT_TYPE) //
                    .POST(HttpRequest.BodyPublishers.ofString(envelope)) //
                    .build();
            HttpResponse<String> response = this.http().send(request, HttpResponse.BodyHandlers.ofString());

            // 验证线上状态和业务隔离，Fault 使用 SOAP 1.2 的结构。
            assertEquals(400, response.statusCode());
            assertEquals(0, invocations.get());
            Document fault = this.document(response.body());
            Element code = (Element) fault.getElementsByTagNameNS(NAMESPACE, "Value").item(0);
            assertNotNull(code);
            assertEquals("soap:Sender", code.getTextContent());
            assertEquals(NAMESPACE, code.lookupNamespaceURI("soap"));
            Element reason = (Element) fault.getElementsByTagNameNS(NAMESPACE, "Text").item(0);
            assertNotNull(reason);
            assertEquals("en", reason.getAttributeNS(XMLConstants.XML_NS_URI, "lang"));
        }
    }

    @Test(timeout = 20000)
    public void encodingStyleOnEnvelopeIsRejected() throws Exception {
        // 从公开入口启动 SOAP 1.2 服务，记录是否进入业务方法。
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

            // SOAP 1.2 encodingStyle 不能放在 Envelope 上。
            String envelope = this.envelope("", OPERATION);
            envelope = envelope.replace("<soap:Envelope ", "<soap:Envelope soap:encodingStyle=\"urn:test:encoding\" ");
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .timeout(Duration.ofSeconds(15)) //
                    .header("Content-Type", CONTENT_TYPE) //
                    .POST(HttpRequest.BodyPublishers.ofString(envelope)) //
                    .build();
            HttpResponse<String> response = this.http().send(request, HttpResponse.BodyHandlers.ofString());

            // 验证线上状态和业务隔离，Fault 使用 SOAP 1.2 的结构。
            assertEquals(400, response.statusCode());
            assertEquals(0, invocations.get());
            Document fault = this.document(response.body());
            Element code = (Element) fault.getElementsByTagNameNS(NAMESPACE, "Value").item(0);
            assertNotNull(code);
            assertEquals("soap:Sender", code.getTextContent());
            assertEquals(NAMESPACE, code.lookupNamespaceURI("soap"));
            Element reason = (Element) fault.getElementsByTagNameNS(NAMESPACE, "Text").item(0);
            assertNotNull(reason);
            assertEquals("en", reason.getAttributeNS(XMLConstants.XML_NS_URI, "lang"));
        }
    }

    @Test(timeout = 20000)
    public void encodingStyleOnBodyIsRejected() throws Exception {
        // 从公开入口启动 SOAP 1.2 服务，记录是否进入业务方法。
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

            // SOAP 1.2 encodingStyle 应属于 Body 子项，不能放在 Body 本身。
            String envelope = this.envelope("", OPERATION);
            envelope = envelope.replace("<soap:Body>", "<soap:Body soap:encodingStyle=\"urn:test:encoding\">");
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .timeout(Duration.ofSeconds(15)) //
                    .header("Content-Type", CONTENT_TYPE) //
                    .POST(HttpRequest.BodyPublishers.ofString(envelope)) //
                    .build();
            HttpResponse<String> response = this.http().send(request, HttpResponse.BodyHandlers.ofString());

            // 验证线上状态和业务隔离，Fault 使用 SOAP 1.2 的结构。
            assertEquals(400, response.statusCode());
            assertEquals(0, invocations.get());
            Document fault = this.document(response.body());
            Element code = (Element) fault.getElementsByTagNameNS(NAMESPACE, "Value").item(0);
            assertNotNull(code);
            assertEquals("soap:Sender", code.getTextContent());
            assertEquals(NAMESPACE, code.lookupNamespaceURI("soap"));
            Element reason = (Element) fault.getElementsByTagNameNS(NAMESPACE, "Text").item(0);
            assertNotNull(reason);
            assertEquals("en", reason.getAttributeNS(XMLConstants.XML_NS_URI, "lang"));
        }
    }

    private MetConfiguration configuration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("soap") //
                .option("soapVersion", "1.2") //
                .contextPath("/services");
        return configuration;
    }

    private URI endpoint(MetContext context) {
        int port = context.getListenAddress("soap").getPort();
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

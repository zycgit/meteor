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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import net.hasor.example.protocol.soap.EchoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import static org.junit.Assert.*;

public class WebServicesSoap12HttpBindingTest {
    private static final String NAMESPACE    = "http://www.w3.org/2003/05/soap-envelope";
    private static final String ACTION       = "urn:hasor:meteor:soap:EchoService/echo";
    private static final String CONTENT_TYPE = "application/soap+xml; charset=utf-8; action=\"" + ACTION + "\"";
    private static final String OPERATION    = """
            <m:echo xmlns:m="urn:hasor:meteor:soap:EchoService"><arg0>hello</arg0></m:echo>
            """;

    @Test(timeout = 20000)
    public void mediaActionSelectsSoap12Operation() throws Exception {
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

            // Content-Type action 承载 SOAP 1.2 操作，不发送 SOAPAction。
            String envelope = this.envelope("", OPERATION);
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .header("Content-Type", CONTENT_TYPE) //
                    .POST(HttpRequest.BodyPublishers.ofString(envelope)) //
                    .build();
            HttpResponse<String> response = this.send(request, HttpResponse.BodyHandlers.ofString());

            // 验证线上状态和业务隔离，Fault 使用 SOAP 1.2 的结构。
            assertEquals(200, response.statusCode());
            assertEquals(1, invocations.get());
            assertTrue(response.body(), response.body().contains("hello"));
            assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/soap+xml"));
        }
    }

    @Test(timeout = 20000)
    public void mismatchedActionFailsBeforeInvocation() throws Exception {
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

            // 媒体类型中的 action 与 Body 操作不匹配。
            String envelope = this.envelope("", OPERATION);
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .header("Content-Type", "application/soap+xml; action=\"urn:other\"") //
                    .POST(HttpRequest.BodyPublishers.ofString(envelope)) //
                    .build();
            HttpResponse<String> response = this.send(request, HttpResponse.BodyHandlers.ofString());

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
    public void missingRequiredActionFailsBeforeInvocation() throws Exception {
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

            // 生成 WSDL 声明 action 必选，缺失时不能仅凭 Body 调用。
            String envelope = this.envelope("", OPERATION);
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .header("Content-Type", "application/soap+xml") //
                    .POST(HttpRequest.BodyPublishers.ofString(envelope)) //
                    .build();
            HttpResponse<String> response = this.send(request, HttpResponse.BodyHandlers.ofString());

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
    public void duplicateActionParameterIsRejected() throws Exception {
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

            // 同名 action 参数重复形成歧义媒体类型。
            String envelope = this.envelope("", OPERATION);
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .header("Content-Type", CONTENT_TYPE + "; action=\"urn:other\"") //
                    .POST(HttpRequest.BodyPublishers.ofString(envelope)) //
                    .build();
            HttpResponse<String> response = this.send(request, HttpResponse.BodyHandlers.ofString());

            // 验证线上状态和业务隔离，Fault 使用 SOAP 1.2 的结构。
            assertEquals(415, response.statusCode());
            assertEquals(0, invocations.get());
        }
    }

    @Test(timeout = 20000)
    public void soap11MediaTypeIsRejectedBySoap12Endpoint() throws Exception {
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

            // SOAP 1.2 端点拒绝 SOAP 1.1 媒体类型。
            String envelope = this.envelope("", OPERATION);
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .header("Content-Type", "text/xml") //
                    .POST(HttpRequest.BodyPublishers.ofString(envelope)) //
                    .build();
            HttpResponse<String> response = this.send(request, HttpResponse.BodyHandlers.ofString());

            // 验证线上状态和业务隔离，Fault 使用 SOAP 1.2 的结构。
            assertEquals(415, response.statusCode());
            assertEquals(0, invocations.get());
        }
    }

    @Test(timeout = 20000)
    public void wrongEnvelopeVersionReturnsVersionMismatch() throws Exception {
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

            // 媒体类型正确但 Envelope 使用错误版本。
            String envelope = this.envelope("", OPERATION).replace(NAMESPACE, "urn:unsupported:soap");
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .header("Content-Type", CONTENT_TYPE) //
                    .POST(HttpRequest.BodyPublishers.ofString(envelope)) //
                    .build();
            HttpResponse<String> response = this.send(request, HttpResponse.BodyHandlers.ofString());

            // 验证线上状态和业务隔离，Fault 使用 SOAP 1.2 的结构。
            assertEquals(500, response.statusCode());
            assertEquals(0, invocations.get());
            Document fault = this.document(response.body());
            Element code = (Element) fault.getElementsByTagNameNS(NAMESPACE, "Value").item(0);
            assertNotNull(code);
            assertEquals("soap:VersionMismatch", code.getTextContent());
            assertEquals(NAMESPACE, code.lookupNamespaceURI("soap"));
            Element reason = (Element) fault.getElementsByTagNameNS(NAMESPACE, "Text").item(0);
            assertNotNull(reason);
            assertEquals("en", reason.getAttributeNS(XMLConstants.XML_NS_URI, "lang"));
            assertEquals(0, fault.getElementsByTagNameNS(NAMESPACE, "Detail").getLength());
            assertEquals(1, fault.getElementsByTagNameNS(NAMESPACE, "SupportedEnvelope").getLength());
        }
    }

    @Test(timeout = 20000)
    public void mandatoryUltimateReceiverHeaderReturnsMustUnderstand() throws Exception {
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

            // 未声明 role 的必选头发给最终接收方。
            String envelope = this.envelope("<t:Required soap:mustUnderstand=\"true\"/>", OPERATION);
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .header("Content-Type", CONTENT_TYPE) //
                    .POST(HttpRequest.BodyPublishers.ofString(envelope)) //
                    .build();
            HttpResponse<String> response = this.send(request, HttpResponse.BodyHandlers.ofString());

            // 验证线上状态和业务隔离，Fault 使用 SOAP 1.2 的结构。
            assertEquals(500, response.statusCode());
            assertEquals(0, invocations.get());
            Document fault = this.document(response.body());
            Element code = (Element) fault.getElementsByTagNameNS(NAMESPACE, "Value").item(0);
            assertNotNull(code);
            assertEquals("soap:MustUnderstand", code.getTextContent());
            assertEquals(NAMESPACE, code.lookupNamespaceURI("soap"));
            Element reason = (Element) fault.getElementsByTagNameNS(NAMESPACE, "Text").item(0);
            assertNotNull(reason);
            assertEquals("en", reason.getAttributeNS(XMLConstants.XML_NS_URI, "lang"));
            assertEquals(0, fault.getElementsByTagNameNS(NAMESPACE, "Detail").getLength());
            Element unknown = (Element) fault.getElementsByTagNameNS(NAMESPACE, "NotUnderstood").item(0);
            assertEquals("unknown:Required", unknown.getAttribute("qname"));
            assertEquals("urn:test:header", unknown.lookupNamespaceURI("unknown"));
        }
    }

    @Test(timeout = 20000)
    public void mandatoryNextRoleHeaderReturnsMustUnderstand() throws Exception {
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

            // next 角色也由最终接收方承担。
            String role = NAMESPACE + "/role/next";
            String header = "<t:Required soap:mustUnderstand=\"1\" soap:role=\"" + role + "\"/>";
            String envelope = this.envelope(header, OPERATION);
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .header("Content-Type", CONTENT_TYPE) //
                    .POST(HttpRequest.BodyPublishers.ofString(envelope)) //
                    .build();
            HttpResponse<String> response = this.send(request, HttpResponse.BodyHandlers.ofString());

            // 验证线上状态和业务隔离，Fault 使用 SOAP 1.2 的结构。
            assertEquals(500, response.statusCode());
            assertEquals(0, invocations.get());
            Document fault = this.document(response.body());
            Element code = (Element) fault.getElementsByTagNameNS(NAMESPACE, "Value").item(0);
            assertNotNull(code);
            assertEquals("soap:MustUnderstand", code.getTextContent());
            assertEquals(NAMESPACE, code.lookupNamespaceURI("soap"));
            Element reason = (Element) fault.getElementsByTagNameNS(NAMESPACE, "Text").item(0);
            assertNotNull(reason);
            assertEquals("en", reason.getAttributeNS(XMLConstants.XML_NS_URI, "lang"));
            assertEquals(0, fault.getElementsByTagNameNS(NAMESPACE, "Detail").getLength());
            Element unknown = (Element) fault.getElementsByTagNameNS(NAMESPACE, "NotUnderstood").item(0);
            assertEquals("unknown:Required", unknown.getAttribute("qname"));
            assertEquals("urn:test:header", unknown.lookupNamespaceURI("unknown"));
        }
    }

    @Test(timeout = 20000)
    public void explicitUltimateReceiverRoleReturnsMustUnderstand() throws Exception {
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

            // 显式最终接收方角色要求处理必选头。
            String role = NAMESPACE + "/role/ultimateReceiver";
            String header = "<t:Required soap:mustUnderstand=\"true\" soap:role=\"" + role + "\"/>";
            String envelope = this.envelope(header, OPERATION);
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .header("Content-Type", CONTENT_TYPE) //
                    .POST(HttpRequest.BodyPublishers.ofString(envelope)) //
                    .build();
            HttpResponse<String> response = this.send(request, HttpResponse.BodyHandlers.ofString());

            // 验证线上状态和业务隔离，Fault 使用 SOAP 1.2 的结构。
            assertEquals(500, response.statusCode());
            assertEquals(0, invocations.get());
            Document fault = this.document(response.body());
            Element code = (Element) fault.getElementsByTagNameNS(NAMESPACE, "Value").item(0);
            assertNotNull(code);
            assertEquals("soap:MustUnderstand", code.getTextContent());
            assertEquals(NAMESPACE, code.lookupNamespaceURI("soap"));
            Element reason = (Element) fault.getElementsByTagNameNS(NAMESPACE, "Text").item(0);
            assertNotNull(reason);
            assertEquals("en", reason.getAttributeNS(XMLConstants.XML_NS_URI, "lang"));
            assertEquals(0, fault.getElementsByTagNameNS(NAMESPACE, "Detail").getLength());
            Element unknown = (Element) fault.getElementsByTagNameNS(NAMESPACE, "NotUnderstood").item(0);
            assertEquals("unknown:Required", unknown.getAttribute("qname"));
            assertEquals("urn:test:header", unknown.lookupNamespaceURI("unknown"));
        }
    }

    @Test(timeout = 20000)
    public void noneRoleIsNotProcessedByUltimateReceiver() throws Exception {
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

            // none 角色不由任何 SOAP 节点处理，relay 不会改变目标角色。
            String role = NAMESPACE + "/role/none";
            String header = "<t:Required soap:mustUnderstand=\"true\" soap:relay=\"true\" soap:role=\"" + role + "\"/>";
            String envelope = this.envelope(header, OPERATION);
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .header("Content-Type", CONTENT_TYPE) //
                    .POST(HttpRequest.BodyPublishers.ofString(envelope)) //
                    .build();
            HttpResponse<String> response = this.send(request, HttpResponse.BodyHandlers.ofString());

            // 验证线上状态和业务隔离，Fault 使用 SOAP 1.2 的结构。
            assertEquals(200, response.statusCode());
            assertEquals(1, invocations.get());
            assertTrue(response.body(), response.body().contains("hello"));
            assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/soap+xml"));
        }
    }

    @Test(timeout = 20000)
    public void foreignRoleIsNotProcessedByUltimateReceiver() throws Exception {
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

            // 其他角色的必选头不属于当前最终接收方。
            String header = "<t:Required soap:mustUnderstand=\"true\" soap:role=\"urn:another:role\"/>";
            String envelope = this.envelope(header, OPERATION);
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .header("Content-Type", CONTENT_TYPE) //
                    .POST(HttpRequest.BodyPublishers.ofString(envelope)) //
                    .build();
            HttpResponse<String> response = this.send(request, HttpResponse.BodyHandlers.ofString());

            // 验证线上状态和业务隔离，Fault 使用 SOAP 1.2 的结构。
            assertEquals(200, response.statusCode());
            assertEquals(1, invocations.get());
            assertTrue(response.body(), response.body().contains("hello"));
            assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("application/soap+xml"));
        }
    }

    @Test(timeout = 20000)
    public void uppercaseMustUnderstandIsNotAnXmlBoolean() throws Exception {
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

            // 大写 TRUE 不是 XML Schema boolean 词法。
            String envelope = this.envelope("<t:Required soap:mustUnderstand=\"TRUE\"/>", OPERATION);
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .header("Content-Type", CONTENT_TYPE) //
                    .POST(HttpRequest.BodyPublishers.ofString(envelope)) //
                    .build();
            HttpResponse<String> response = this.send(request, HttpResponse.BodyHandlers.ofString());

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
    public void invalidRelayIsRejected() throws Exception {
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

            // relay 也必须符合 SOAP 1.2 boolean 词法。
            String envelope = this.envelope("<t:Optional soap:relay=\"maybe\"/>", OPERATION);
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .header("Content-Type", CONTENT_TYPE) //
                    .POST(HttpRequest.BodyPublishers.ofString(envelope)) //
                    .build();
            HttpResponse<String> response = this.send(request, HttpResponse.BodyHandlers.ofString());

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
    public void unqualifiedHeaderBlockIsRejected() throws Exception {
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

            // SOAP Header block 必须使用非空命名空间。
            String envelope = this.envelope("<Required/>", OPERATION);
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .header("Content-Type", CONTENT_TYPE) //
                    .POST(HttpRequest.BodyPublishers.ofString(envelope)) //
                    .build();
            HttpResponse<String> response = this.send(request, HttpResponse.BodyHandlers.ofString());

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
    public void duplicateBodyIsRejected() throws Exception {
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

            // 第二个 Body 不能被解码器静默忽略。
            String envelope = this.envelope("", OPERATION).replace("</soap:Envelope>", "<soap:Body/> </soap:Envelope>");
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .header("Content-Type", CONTENT_TYPE) //
                    .POST(HttpRequest.BodyPublishers.ofString(envelope)) //
                    .build();
            HttpResponse<String> response = this.send(request, HttpResponse.BodyHandlers.ofString());

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
    public void duplicateHeaderIsRejected() throws Exception {
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

            // 重复 Header 不能导致只验证第一组必选头。
            String envelope = this.envelope("<t:Optional/>", OPERATION);
            envelope = envelope.replace("<soap:Body>", "<soap:Header/><soap:Body>");
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .header("Content-Type", CONTENT_TYPE) //
                    .POST(HttpRequest.BodyPublishers.ofString(envelope)) //
                    .build();
            HttpResponse<String> response = this.send(request, HttpResponse.BodyHandlers.ofString());

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
    public void headerAfterBodyIsRejected() throws Exception {
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

            // Header 必须位于 Body 前。
            String envelope = this.envelope("", OPERATION);
            envelope = envelope.replace("</soap:Envelope>", "<soap:Header/> </soap:Envelope>");
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .header("Content-Type", CONTENT_TYPE) //
                    .POST(HttpRequest.BodyPublishers.ofString(envelope)) //
                    .build();
            HttpResponse<String> response = this.send(request, HttpResponse.BodyHandlers.ofString());

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
    public void multipleOperationsViolateDocumentLiteralContract() throws Exception {
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

            // 插件 document/literal 请求只允许一个 operation wrapper。
            String envelope = this.envelope("", OPERATION + OPERATION);
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .header("Content-Type", CONTENT_TYPE) //
                    .POST(HttpRequest.BodyPublishers.ofString(envelope)) //
                    .build();
            HttpResponse<String> response = this.send(request, HttpResponse.BodyHandlers.ofString());

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
    public void processingInstructionIsRejected() throws Exception {
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

            // SOAP 消息不能携带 processing instruction。
            String envelope = "<?test value?>" + this.envelope("", OPERATION);
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .header("Content-Type", CONTENT_TYPE) //
                    .POST(HttpRequest.BodyPublishers.ofString(envelope)) //
                    .build();
            HttpResponse<String> response = this.send(request, HttpResponse.BodyHandlers.ofString());

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
    public void meteorSoap12ClientCallsMeteorServer() throws Exception {
        // 客户端和服务端都通过正式协议配置明确选择 SOAP 1.2。
        MetConfiguration incoming = this.configuration();
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("soap");
        outgoing.connector("http").protocol("soap").option("soapVersion", "1.2").contextPath("/services");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(EchoService.class, value -> value) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<EchoService> service = client.publisher() //
                    .rsfService(EchoService.class) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 独立上下文之间通过绑定端口执行真实 SOAP HTTP 请求。
            EchoService remote = client.getRsfClient(server.getListenAddress("soap")).getRemote(service);
            assertEquals("双向 SOAP 1.2", remote.echo("双向 SOAP 1.2"));
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
        return HttpClient.newHttpClient();
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws Exception {
        Future<HttpResponse<T>> pending = this.http().sendAsync(request, handler);
        try {
            return pending.get(5, TimeUnit.SECONDS);
        } finally {
            pending.cancel(true);
        }
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

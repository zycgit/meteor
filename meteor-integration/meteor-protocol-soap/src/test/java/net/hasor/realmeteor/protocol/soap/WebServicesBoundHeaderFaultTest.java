/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.soap;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.hasor.example.protocol.soap.EchoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.MetResponse;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.soap.SoapFault;
import net.hasor.meteor.protocol.soap.definition.SoapWsdlResource;
import net.hasor.realmeteor.protocol.soap.support.BoundHeaderHandler;
import net.hasor.realmeteor.protocol.soap.support.SoapHeadersExtension;
import org.junit.Test;
import org.w3c.dom.Element;
import static org.junit.Assert.*;

public class WebServicesBoundHeaderFaultTest {
    private static final String NAMESPACE = "urn:meteor:test:bound-header-fault";
    private static final String SOAP_12   = "http://www.w3.org/2003/05/soap-envelope";

    @Test(timeout = 20000)
    public void declaredIntegerHeadersCrossHttpInBothDirections() throws Exception {
        // 两端绑定同一 WSDL，并通过正式扩展处理声明为整数的必选 Header。
        MetConfiguration incoming = this.incoming();
        incoming.addExtension(new SoapHeadersExtension(new BoundHeaderHandler()));
        MetConfiguration outgoing = this.outgoing();
        outgoing.addExtension(new SoapHeadersExtension(new BoundHeaderHandler()));
        AtomicReference<String> token = new AtomicReference<>();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(EchoService.class, message -> message) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .bindResource(this.resource()) //
                    .bindFilter("token", (request, response, chain) -> {
                        token.set(request.getOption("token"));
                        chain.doFilter(request, response);
                    }) //
                    .register();
            MetBindInfo<EchoService> service = client.publisher() //
                    .rsfService(EchoService.class) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .bindResource(this.resource()) //
                    .register();

            // SOAP mustUnderstand 属性不能破坏应用层 xsd:int 的有效性。
            MetResponse response = client.getRsfClient(server.bindAddress("soap")) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "hello" }) //
                    .get(5, TimeUnit.SECONDS);
            assertEquals("hello", response.getData());
            assertEquals("17", token.get());
            assertEquals("29", response.getOption("receipt"));
        }
    }

    @Test(timeout = 20000)
    public void declaredHeadersDoNotRequireTheirPresence() throws Exception {
        // WSDL 声明 Header 结构，两端均不安装生成 Header 的处理器。
        MetConfiguration incoming = this.incoming();
        MetConfiguration outgoing = this.outgoing();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(EchoService.class, message -> message) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .bindResource(this.resource()) //
                    .register();
            MetBindInfo<EchoService> service = client.publisher() //
                    .rsfService(EchoService.class) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .bindResource(this.resource()) //
                    .register();

            // 缺少声明的 token 和 receipt 不等于违反 mustUnderstand。
            Object value = client.getRsfClient(server.bindAddress("soap")) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "no headers" }) //
                    .getData(5, TimeUnit.SECONDS);
            assertEquals("no headers", value);
        }
    }

    @Test(timeout = 20000)
    public void invalidDeclaredInputHeaderIsRejectedBeforeBusinessInvocation() throws Exception {
        // 服务绑定 Header Schema，业务计数用于观察校验发生在调用之前。
        MetConfiguration configuration = this.incoming();
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
                    .bindResource(this.resource()) //
                    .register();

            // 对端绕过本地出站校验，把非整数文本放进声明为 xsd:int 的 token。
            String envelope = this.envelope("<b:token>invalid-header-number</b:token>", //
                    "<b:echo><message>hello</message></b:echo>");
            int port = context.bindAddress("soap").getPort();
            URI endpoint = URI.create("http://127.0.0.1:" + port + "/services/EchoService");
            String contentType = "application/soap+xml; action=\"" + NAMESPACE + ":echo\"";
            HttpRequest request = HttpRequest.newBuilder(endpoint) //
                    .timeout(Duration.ofSeconds(15)) //
                    .header("Content-Type", contentType) //
                    .POST(HttpRequest.BodyPublishers.ofString(envelope)) //
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient() //
                    .send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(400, response.statusCode());
            assertTrue(response.body(), response.body().contains("soap:Sender"));
            assertTrue(response.body(), response.body().contains("invalid-header-number"));
            assertEquals(0, invocations.get());
        }
    }

    @Test(timeout = 20000)
    public void declaredHeaderWithUnknownEncodingIsRejectedBeforeBusinessInvocation() throws Exception {
        // 服务绑定 literal Header，业务计数区分协议拒绝与执行后报错。
        MetConfiguration configuration = this.incoming();
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
                    .bindResource(this.resource()) //
                    .register();

            // Header 元素允许携带 encodingStyle，但接收方不能忽略未知的编码规则。
            String header = "<b:token s:encodingStyle='urn:unknown'>17</b:token>";
            String envelope = this.envelope(header, "<b:echo><message>hello</message></b:echo>");
            int port = context.bindAddress("soap").getPort();
            URI endpoint = URI.create("http://127.0.0.1:" + port + "/services/EchoService");
            String contentType = "application/soap+xml; action=\"" + NAMESPACE + ":echo\"";
            HttpRequest request = HttpRequest.newBuilder(endpoint) //
                    .timeout(Duration.ofSeconds(15)) //
                    .header("Content-Type", contentType) //
                    .POST(HttpRequest.BodyPublishers.ofString(envelope)) //
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient() //
                    .send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(500, response.statusCode());
            assertTrue(response.body(), response.body().contains("soap:DataEncodingUnknown"));
            assertEquals(0, invocations.get());
        }
    }

    @Test(timeout = 20000)
    public void invalidDeclaredOutputHeaderIsRejectedByTheClient() throws Exception {
        // 独立 HTTP 对端返回合法正文和违反 receipt 整数类型的响应 Header。
        String envelope = this.envelope("<b:receipt>invalid-header-number</b:receipt>", //
                "<b:echoResponse><return>hello</return></b:echoResponse>");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/EchoService", exchange -> this.write(exchange, 200, envelope));
        server.start();
        try {
            MetConfiguration configuration = this.outgoing();
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<EchoService> service = context.publisher() //
                        .rsfService(EchoService.class) //
                        .aliasName("soap", "EchoService") //
                        .protocol("soap") //
                        .bindResource(this.resource()) //
                        .register();

                // 客户端公开调用必须报告 Schema 错误，不能仅忽略未安装处理器的 Header。
                int port = server.getAddress().getPort();
                InterAddress address = new InterAddress("soap", "127.0.0.1", port, "default");
                MetFuture invocation = context.getRsfClient(address) //
                        .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "hello" });
                ExecutionException failure = assertThrows(ExecutionException.class, //
                        () -> invocation.getData(5, TimeUnit.SECONDS));
                Throwable cause = failure.getCause();
                assertFalse(cause instanceof SoapFault);
                assertTrue(cause.toString(), cause.getMessage().contains("SOAP Header binding validation failed"));
                assertTrue(cause.toString(), cause.getMessage().contains("invalid-header-number"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test(timeout = 20000)
    public void declaredFaultDetailPreservesItsSoapFaultAcrossHttp() throws Exception {
        // 声明的 Problem Fault 使用整数 detail，业务明确抛出完整 SoapFault。
        Element detail = this.element("<Detail xmlns:b='" + NAMESPACE + "'><b:problem>42</b:problem></Detail>");
        SoapFault declared = SoapFault.create(new QName(SOAP_12, "Sender"), "declared problem").detail(detail);
        MetConfiguration incoming = this.incoming();
        MetConfiguration outgoing = this.outgoing();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(EchoService.class, message -> {
                        throw declared;
                    }) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .bindResource(this.resource()) //
                    .register();
            MetBindInfo<EchoService> service = client.publisher() //
                    .rsfService(EchoService.class) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .bindResource(this.resource()) //
                    .register();

            // 声明 Fault 的 Schema 校验不应降级错误类型或丢失 Detail。
            MetFuture invocation = client.getRsfClient(server.bindAddress("soap")) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "bad" });
            ExecutionException failure = assertThrows(ExecutionException.class, //
                    () -> invocation.getData(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof SoapFault);
            SoapFault fault = (SoapFault) failure.getCause();
            assertEquals(new QName(SOAP_12, "Sender"), fault.getCode());
            assertEquals("declared problem", fault.getReasons().get(0).text());
            Element problem = (Element) fault.getDetail().getElementsByTagNameNS(NAMESPACE, "problem").item(0);
            assertNotNull(problem);
            assertEquals("42", problem.getTextContent());
        }
    }

    @Test(timeout = 20000)
    public void invalidDeclaredFaultDetailIsRejectedByTheClient() throws Exception {
        // 独立对端返回结构合法的 Fault，但声明的 problem 不符合 xsd:int。
        String fault = this.fault("<b:problem>invalid-fault-number</b:problem>");
        String envelope = this.envelope("", fault);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/EchoService", exchange -> this.write(exchange, 500, envelope));
        server.start();
        try {
            MetConfiguration configuration = this.outgoing();
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<EchoService> service = context.publisher() //
                        .rsfService(EchoService.class) //
                        .aliasName("soap", "EchoService") //
                        .protocol("soap") //
                        .bindResource(this.resource()) //
                        .register();

                // 非法声明 Detail 必须作为协议解码失败，不能交付成有效的业务 Fault。
                int port = server.getAddress().getPort();
                InterAddress address = new InterAddress("soap", "127.0.0.1", port, "default");
                MetFuture invocation = context.getRsfClient(address) //
                        .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "bad" });
                ExecutionException failure = assertThrows(ExecutionException.class, //
                        () -> invocation.getData(5, TimeUnit.SECONDS));
                Throwable cause = failure.getCause();
                assertFalse(cause instanceof SoapFault);
                assertTrue(cause.toString(), cause.getMessage().contains("SOAP Fault binding validation failed"));
                assertTrue(cause.toString(), cause.getMessage().contains("invalid-fault-number"));
            }
        } finally {
            server.stop(0);
        }
    }

    @Test(timeout = 20000)
    public void invalidDeclaredHeaderFaultIsRejectedByTheClient() throws Exception {
        // token 的 headerfault 声明适用于响应 Header，不应在收到 Fault 时被忽略。
        String header = "<b:tokenFault>invalid-header-fault-number</b:tokenFault>";
        String envelope = this.envelope(header, this.fault(""));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/services/EchoService", exchange -> this.write(exchange, 500, envelope));
        server.start();
        try {
            MetConfiguration configuration = this.outgoing();
            try (MetContext context = configuration.buildContext()) {
                context.start();
                MetBindInfo<EchoService> service = context.publisher() //
                        .rsfService(EchoService.class) //
                        .aliasName("soap", "EchoService") //
                        .protocol("soap") //
                        .bindResource(this.resource()) //
                        .register();

                // Header Fault 的类型错误在处理标准 SOAP Fault 之前报告。
                int port = server.getAddress().getPort();
                InterAddress address = new InterAddress("soap", "127.0.0.1", port, "default");
                MetFuture invocation = context.getRsfClient(address) //
                        .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "bad" });
                ExecutionException failure = assertThrows(ExecutionException.class, //
                        () -> invocation.getData(5, TimeUnit.SECONDS));
                Throwable cause = failure.getCause();
                assertFalse(cause instanceof SoapFault);
                assertTrue(cause.toString(), cause.getMessage().contains("SOAP Header binding validation failed"));
                assertTrue(cause.toString(), cause.getMessage().contains("invalid-header-fault-number"));
            }
        } finally {
            server.stop(0);
        }
    }

    private MetConfiguration incoming() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("soap") //
                .option("soapVersion", "1.2") //
                .contextPath("/services");
        return configuration;
    }

    private MetConfiguration outgoing() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .protocol("soap") //
                .option("soapVersion", "1.2") //
                .contextPath("/services");
        return configuration;
    }

    private SoapWsdlResource resource() throws Exception {
        URL wsdl = this.getClass().getResource("/wsdl/bound-header-fault.wsdl");
        assertNotNull("Missing bound Header/Fault WSDL", wsdl);
        return new SoapWsdlResource(wsdl);
    }

    private String envelope(String header, String body) {
        return """
                <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                        xmlns:b="urn:meteor:test:bound-header-fault">
                    <s:Header>%s</s:Header>
                    <s:Body>%s</s:Body>
                </s:Envelope>
                """.formatted(header, body);
    }

    private String fault(String detail) {
        return """
                <s:Fault>
                    <s:Code><s:Value>s:Receiver</s:Value></s:Code>
                    <s:Reason><s:Text xml:lang="en">external declared failure</s:Text></s:Reason>
                    <s:Detail>%s</s:Detail>
                </s:Fault>
                """.formatted(detail);
    }

    private void write(HttpExchange exchange, int status, String envelope) {
        try (exchange) {
            // 对端完整消费请求，再发送预先准备的 SOAP 1.2 响应。
            exchange.getRequestBody().readAllBytes();
            byte[] body = envelope.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/soap+xml; charset=utf-8");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to return bound Header/Fault response", failure);
        }
    }

    private Element element(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes)).getDocumentElement();
    }
}

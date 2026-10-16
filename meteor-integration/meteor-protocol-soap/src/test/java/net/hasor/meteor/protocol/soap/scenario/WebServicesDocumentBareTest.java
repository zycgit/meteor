/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.scenario;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.xml.namespace.QName;
import jakarta.xml.soap.*;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.soap.scenario.example.DocumentBareService;
import net.hasor.meteor.protocol.soap.scenario.example.DocumentBareServiceImpl;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WebServicesDocumentBareTest {
    private static final String NAMESPACE = "urn:hasor:meteor:soap:DocumentBareService";

    @Test(timeout = 20000)
    public void soap11BareRequestCarriesSeparateParameterElements() throws Exception {
        // 选择 SOAP 1.1 document/literal bare，并发布普通 Java 服务。
        MetConfiguration configuration = this.configuration("1.1");
        DocumentBareServiceImpl service = new DocumentBareServiceImpl();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(DocumentBareService.class, service) //
                    .aliasName("soap", "DocumentBareService") //
                    .protocol("soap") //
                    .register();

            // bare 参数各自作为 Body 的直接子元素，不增加操作包装。
            MessageFactory factory = MessageFactory.newInstance(SOAPConstants.SOAP_1_1_PROTOCOL);
            SOAPMessage requestBody = factory.createMessage();
            SOAPBody body = requestBody.getSOAPBody();
            body.addBodyElement(new QName(NAMESPACE, "joinArg0", "service")).addTextNode("left & right");
            body.addBodyElement(new QName(NAMESPACE, "joinArg1", "service")).addTextNode("中文");
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .timeout(Duration.ofSeconds(15)) //
                    .header("Content-Type", "text/xml; charset=utf-8") //
                    .header("SOAPAction", "\"" + NAMESPACE + "/join\"") //
                    .POST(HttpRequest.BodyPublishers.ofByteArray(this.bytes(requestBody))) //
                    .build();
            HttpResponse<byte[]> response = this.http().send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());

            // 返回值本身就是唯一的 Body 元素，QName 与生成的 bare 契约一致。
            SOAPMessage result = this.parse(factory, response);
            List<SOAPElement> elements = this.elements(result.getSOAPBody());
            assertEquals(1, elements.size());
            SOAPElement value = elements.get(0);
            assertEquals(new QName(NAMESPACE, "joinResult"), value.getElementQName());
            assertEquals("left & right / 中文", value.getTextContent());
        }
    }

    @Test(timeout = 20000)
    public void soap12BareRequestCarriesSeparateParameterElements() throws Exception {
        // 选择 SOAP 1.2 document/literal bare，并发布普通 Java 服务。
        MetConfiguration configuration = this.configuration("1.2");
        DocumentBareServiceImpl service = new DocumentBareServiceImpl();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(DocumentBareService.class, service) //
                    .aliasName("soap", "DocumentBareService") //
                    .protocol("soap") //
                    .register();

            // bare 参数各自作为 Body 的直接子元素，不增加操作包装。
            MessageFactory factory = MessageFactory.newInstance(SOAPConstants.SOAP_1_2_PROTOCOL);
            SOAPMessage requestBody = factory.createMessage();
            SOAPBody body = requestBody.getSOAPBody();
            body.addBodyElement(new QName(NAMESPACE, "joinArg0", "service")).addTextNode("left & right");
            body.addBodyElement(new QName(NAMESPACE, "joinArg1", "service")).addTextNode("中文");
            String contentType = "application/soap+xml; charset=utf-8; action=\"" + NAMESPACE + "/join\"";
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .timeout(Duration.ofSeconds(15)) //
                    .header("Content-Type", contentType) //
                    .POST(HttpRequest.BodyPublishers.ofByteArray(this.bytes(requestBody))) //
                    .build();
            HttpResponse<byte[]> response = this.http().send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());

            // 返回值本身就是唯一的 Body 元素，QName 与生成的 bare 契约一致。
            SOAPMessage result = this.parse(factory, response);
            List<SOAPElement> elements = this.elements(result.getSOAPBody());
            assertEquals(1, elements.size());
            SOAPElement value = elements.get(0);
            assertEquals(new QName(NAMESPACE, "joinResult"), value.getElementQName());
            assertEquals("left & right / 中文", value.getTextContent());
        }
    }

    @Test(timeout = 20000)
    public void soap11BareNoArgumentRequestUsesAnEmptyBody() throws Exception {
        // 选择 SOAP 1.1 document/literal bare，并发布普通 Java 服务。
        MetConfiguration configuration = this.configuration("1.1");
        DocumentBareServiceImpl service = new DocumentBareServiceImpl();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(DocumentBareService.class, service) //
                    .aliasName("soap", "DocumentBareService") //
                    .protocol("soap") //
                    .register();

            // 无参数消息没有 Body 子元素，由 action 选择 status 操作。
            MessageFactory factory = MessageFactory.newInstance(SOAPConstants.SOAP_1_1_PROTOCOL);
            SOAPMessage requestBody = factory.createMessage();
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .timeout(Duration.ofSeconds(15)) //
                    .header("Content-Type", "text/xml; charset=utf-8") //
                    .header("SOAPAction", "\"" + NAMESPACE + "/status\"") //
                    .POST(HttpRequest.BodyPublishers.ofByteArray(this.bytes(requestBody))) //
                    .build();
            HttpResponse<byte[]> response = this.http().send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());

            // 返回值本身就是唯一的 Body 元素，QName 与生成的 bare 契约一致。
            SOAPMessage result = this.parse(factory, response);
            List<SOAPElement> elements = this.elements(result.getSOAPBody());
            assertEquals(1, elements.size());
            SOAPElement value = elements.get(0);
            assertEquals(new QName(NAMESPACE, "statusResult"), value.getElementQName());
            assertEquals("ready", value.getTextContent());
        }
    }

    @Test(timeout = 20000)
    public void soap12BareNoArgumentRequestUsesAnEmptyBody() throws Exception {
        // 选择 SOAP 1.2 document/literal bare，并发布普通 Java 服务。
        MetConfiguration configuration = this.configuration("1.2");
        DocumentBareServiceImpl service = new DocumentBareServiceImpl();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(DocumentBareService.class, service) //
                    .aliasName("soap", "DocumentBareService") //
                    .protocol("soap") //
                    .register();

            // 无参数消息没有 Body 子元素，由 action 选择 status 操作。
            MessageFactory factory = MessageFactory.newInstance(SOAPConstants.SOAP_1_2_PROTOCOL);
            SOAPMessage requestBody = factory.createMessage();
            String contentType = "application/soap+xml; charset=utf-8; action=\"" + NAMESPACE + "/status\"";
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .timeout(Duration.ofSeconds(15)) //
                    .header("Content-Type", contentType) //
                    .POST(HttpRequest.BodyPublishers.ofByteArray(this.bytes(requestBody))) //
                    .build();
            HttpResponse<byte[]> response = this.http().send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());

            // 返回值本身就是唯一的 Body 元素，QName 与生成的 bare 契约一致。
            SOAPMessage result = this.parse(factory, response);
            List<SOAPElement> elements = this.elements(result.getSOAPBody());
            assertEquals(1, elements.size());
            SOAPElement value = elements.get(0);
            assertEquals(new QName(NAMESPACE, "statusResult"), value.getElementQName());
            assertEquals("ready", value.getTextContent());
        }
    }

    @Test(timeout = 20000)
    public void soap11BareVoidResponseUsesAnEmptyBody() throws Exception {
        // 选择 SOAP 1.1 document/literal bare，并发布普通 Java 服务。
        MetConfiguration configuration = this.configuration("1.1");
        DocumentBareServiceImpl service = new DocumentBareServiceImpl();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(DocumentBareService.class, service) //
                    .aliasName("soap", "DocumentBareService") //
                    .protocol("soap") //
                    .register();

            // accept 是普通双向 void 方法，请求携带唯一的 bare 参数元素。
            MessageFactory factory = MessageFactory.newInstance(SOAPConstants.SOAP_1_1_PROTOCOL);
            SOAPMessage requestBody = factory.createMessage();
            SOAPBody body = requestBody.getSOAPBody();
            body.addBodyElement(new QName(NAMESPACE, "acceptArg0", "service")).addTextNode("accepted");
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .timeout(Duration.ofSeconds(15)) //
                    .header("Content-Type", "text/xml; charset=utf-8") //
                    .header("SOAPAction", "\"" + NAMESPACE + "/accept\"") //
                    .POST(HttpRequest.BodyPublishers.ofByteArray(this.bytes(requestBody))) //
                    .build();
            HttpResponse<byte[]> response = this.http().send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());

            // 双向 void 仍返回完整 SOAP Envelope，Body 中没有结果或虚构包装。
            SOAPMessage result = this.parse(factory, response);
            assertTrue(this.elements(result.getSOAPBody()).isEmpty());
            assertEquals("accepted", service.getReceived());
        }
    }

    @Test(timeout = 20000)
    public void soap12BareVoidResponseUsesAnEmptyBody() throws Exception {
        // 选择 SOAP 1.2 document/literal bare，并发布普通 Java 服务。
        MetConfiguration configuration = this.configuration("1.2");
        DocumentBareServiceImpl service = new DocumentBareServiceImpl();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(DocumentBareService.class, service) //
                    .aliasName("soap", "DocumentBareService") //
                    .protocol("soap") //
                    .register();

            // accept 是普通双向 void 方法，请求携带唯一的 bare 参数元素。
            MessageFactory factory = MessageFactory.newInstance(SOAPConstants.SOAP_1_2_PROTOCOL);
            SOAPMessage requestBody = factory.createMessage();
            SOAPBody body = requestBody.getSOAPBody();
            body.addBodyElement(new QName(NAMESPACE, "acceptArg0", "service")).addTextNode("accepted");
            String contentType = "application/soap+xml; charset=utf-8; action=\"" + NAMESPACE + "/accept\"";
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(context)) //
                    .timeout(Duration.ofSeconds(15)) //
                    .header("Content-Type", contentType) //
                    .POST(HttpRequest.BodyPublishers.ofByteArray(this.bytes(requestBody))) //
                    .build();
            HttpResponse<byte[]> response = this.http().send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());

            // 双向 void 仍返回完整 SOAP Envelope，Body 中没有结果或虚构包装。
            SOAPMessage result = this.parse(factory, response);
            assertTrue(this.elements(result.getSOAPBody()).isEmpty());
            assertEquals("accepted", service.getReceived());
        }
    }

    private MetConfiguration configuration(String version) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("soap") //
                .contextPath("/services") //
                .option("soapVersion", version) //
                .option("soapStyle", "document") //
                .option("soapUse", "literal") //
                .option("soapParameterStyle", "bare");
        return configuration;
    }

    private URI endpoint(MetContext context) {
        int port = context.bindAddress("soap").getPort();
        return URI.create("http://127.0.0.1:" + port + "/services/DocumentBareService");
    }

    private HttpClient http() {
        return HttpClient.newBuilder() //
                .connectTimeout(Duration.ofSeconds(15)) //
                .version(HttpClient.Version.HTTP_1_1) //
                .build();
    }

    private byte[] bytes(SOAPMessage message) throws Exception {
        message.saveChanges();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        message.writeTo(output);
        return output.toByteArray();
    }

    private SOAPMessage parse(MessageFactory factory, HttpResponse<byte[]> response) throws Exception {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        MimeHeaders headers = new MimeHeaders();
        headers.setHeader("Content-Type", contentType);
        return factory.createMessage(headers, new ByteArrayInputStream(response.body()));
    }

    private List<SOAPElement> elements(SOAPBody body) {
        List<SOAPElement> result = new ArrayList<>();
        Iterator<Node> children = body.getChildElements();
        while (children.hasNext()) {
            Node node = children.next();
            if (node instanceof SOAPElement element) {
                result.add(element);
            }
        }
        return result;
    }
}

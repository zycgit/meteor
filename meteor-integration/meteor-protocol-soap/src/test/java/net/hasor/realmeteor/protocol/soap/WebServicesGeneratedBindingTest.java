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
import java.time.Duration;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import net.hasor.example.protocol.soap.EchoService;
import net.hasor.example.protocol.soap.EchoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import static org.junit.Assert.assertEquals;

public class WebServicesGeneratedBindingTest {
    @Test(timeout = 20000)
    public void soap11DocumentLiteralBareSupportsGeneratedRoundTrip() throws Exception {
        // 两端明确选择 SOAP 1.1 的 document/literal/bare 生成契约。
        MetConfiguration incoming = this.incomingConfiguration("1.1", "document", "literal", "bare");
        MetConfiguration outgoing = this.outgoingConfiguration("1.1", "document", "literal", "bare");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 通过公开入口启动两端，发布与订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<EchoService> subscription = client.publisher() //
                    .rsfService(EchoService.class) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 在线 WSDL 必须公开所选绑定，不能静默使用默认 document/wrapped 契约。
            int port = server.bindAddress("soap").getPort();
            URI wsdl = URI.create("http://127.0.0.1:" + port + "/services/EchoService?wsdl");
            HttpRequest request = HttpRequest.newBuilder(wsdl).timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<byte[]> response = this.http().send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            Document definition = this.parse(response.body());
            String bindingNamespace = "http://schemas.xmlsoap.org/wsdl/soap/";
            NodeList bindings = definition.getElementsByTagNameNS(bindingNamespace, "binding");
            assertEquals(1, bindings.getLength());
            Element binding = (Element) bindings.item(0);
            assertEquals("document", binding.getAttribute("style"));
            NodeList bodies = definition.getElementsByTagNameNS(bindingNamespace, "body");
            assertEquals(2, bodies.getLength());
            Element inputBody = (Element) bodies.item(0);
            Element outputBody = (Element) bodies.item(1);
            assertEquals("literal", inputBody.getAttribute("use"));
            assertEquals("literal", outputBody.getAttribute("use"));
            NodeList messageParts = definition.getElementsByTagNameNS("http://schemas.xmlsoap.org/wsdl/", "part");
            assertEquals(2, messageParts.getLength());
            Element inputPart = (Element) messageParts.item(0);
            Element outputPart = (Element) messageParts.item(1);
            String namespace = "urn:hasor:meteor:soap:EchoService";
            assertEquals(new QName(namespace, "echoArg0"), this.qualifiedName(inputPart, "element"));
            assertEquals(new QName(namespace, "echoResult"), this.qualifiedName(outputPart, "element"));

            // 客户端根据独立生成的同一契约，经真实 HTTP 监听完成业务调用。
            EchoService remote = client.getRsfClient(server.bindAddress("soap")).getRemote(subscription);
            assertEquals("document literal SOAP 1.1", remote.echo("document literal SOAP 1.1"));
        }
    }

    @Test(timeout = 20000)
    public void soap12DocumentLiteralBareSupportsGeneratedRoundTrip() throws Exception {
        // 两端明确选择 SOAP 1.2 的 document/literal/bare 生成契约。
        MetConfiguration incoming = this.incomingConfiguration("1.2", "document", "literal", "bare");
        MetConfiguration outgoing = this.outgoingConfiguration("1.2", "document", "literal", "bare");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 通过公开入口启动两端，发布与订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<EchoService> subscription = client.publisher() //
                    .rsfService(EchoService.class) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 在线 WSDL 必须公开所选绑定，不能静默使用默认 document/wrapped 契约。
            int port = server.bindAddress("soap").getPort();
            URI wsdl = URI.create("http://127.0.0.1:" + port + "/services/EchoService?wsdl");
            HttpRequest request = HttpRequest.newBuilder(wsdl).timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<byte[]> response = this.http().send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            Document definition = this.parse(response.body());
            String bindingNamespace = "http://schemas.xmlsoap.org/wsdl/soap12/";
            NodeList bindings = definition.getElementsByTagNameNS(bindingNamespace, "binding");
            assertEquals(1, bindings.getLength());
            Element binding = (Element) bindings.item(0);
            assertEquals("document", binding.getAttribute("style"));
            NodeList bodies = definition.getElementsByTagNameNS(bindingNamespace, "body");
            assertEquals(2, bodies.getLength());
            Element inputBody = (Element) bodies.item(0);
            Element outputBody = (Element) bodies.item(1);
            assertEquals("literal", inputBody.getAttribute("use"));
            assertEquals("literal", outputBody.getAttribute("use"));
            NodeList messageParts = definition.getElementsByTagNameNS("http://schemas.xmlsoap.org/wsdl/", "part");
            assertEquals(2, messageParts.getLength());
            Element inputPart = (Element) messageParts.item(0);
            Element outputPart = (Element) messageParts.item(1);
            String namespace = "urn:hasor:meteor:soap:EchoService";
            assertEquals(new QName(namespace, "echoArg0"), this.qualifiedName(inputPart, "element"));
            assertEquals(new QName(namespace, "echoResult"), this.qualifiedName(outputPart, "element"));

            // 客户端根据独立生成的同一契约，经真实 HTTP 监听完成业务调用。
            EchoService remote = client.getRsfClient(server.bindAddress("soap")).getRemote(subscription);
            assertEquals("document literal SOAP 1.2", remote.echo("document literal SOAP 1.2"));
        }
    }

    @Test(timeout = 20000)
    public void soap11RpcLiteralSupportsGeneratedRoundTrip() throws Exception {
        // 两端明确选择 SOAP 1.1 的 rpc/literal/wrapped 生成契约。
        MetConfiguration incoming = this.incomingConfiguration("1.1", "rpc", "literal", "wrapped");
        MetConfiguration outgoing = this.outgoingConfiguration("1.1", "rpc", "literal", "wrapped");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 通过公开入口启动两端，发布与订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<EchoService> subscription = client.publisher() //
                    .rsfService(EchoService.class) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 在线 WSDL 必须公开所选绑定，不能静默使用默认 document/wrapped 契约。
            int port = server.bindAddress("soap").getPort();
            URI wsdl = URI.create("http://127.0.0.1:" + port + "/services/EchoService?wsdl");
            HttpRequest request = HttpRequest.newBuilder(wsdl).timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<byte[]> response = this.http().send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            Document definition = this.parse(response.body());
            String bindingNamespace = "http://schemas.xmlsoap.org/wsdl/soap/";
            NodeList bindings = definition.getElementsByTagNameNS(bindingNamespace, "binding");
            assertEquals(1, bindings.getLength());
            Element binding = (Element) bindings.item(0);
            assertEquals("rpc", binding.getAttribute("style"));
            NodeList bodies = definition.getElementsByTagNameNS(bindingNamespace, "body");
            assertEquals(2, bodies.getLength());
            Element inputBody = (Element) bodies.item(0);
            Element outputBody = (Element) bodies.item(1);
            assertEquals("literal", inputBody.getAttribute("use"));
            assertEquals("literal", outputBody.getAttribute("use"));
            assertEquals("urn:hasor:meteor:soap:EchoService", inputBody.getAttribute("namespace"));
            assertEquals("urn:hasor:meteor:soap:EchoService", outputBody.getAttribute("namespace"));
            NodeList messageParts = definition.getElementsByTagNameNS("http://schemas.xmlsoap.org/wsdl/", "part");
            assertEquals(2, messageParts.getLength());
            Element inputPart = (Element) messageParts.item(0);
            Element outputPart = (Element) messageParts.item(1);
            assertEquals("arg0", inputPart.getAttribute("name"));
            assertEquals("return", outputPart.getAttribute("name"));
            QName stringType = new QName(XMLConstants.W3C_XML_SCHEMA_NS_URI, "string");
            assertEquals(stringType, this.qualifiedName(inputPart, "type"));
            assertEquals(stringType, this.qualifiedName(outputPart, "type"));

            // 客户端根据独立生成的同一契约，经真实 HTTP 监听完成业务调用。
            EchoService remote = client.getRsfClient(server.bindAddress("soap")).getRemote(subscription);
            assertEquals("rpc literal SOAP 1.1", remote.echo("rpc literal SOAP 1.1"));
        }
    }

    @Test(timeout = 20000)
    public void soap12RpcLiteralSupportsGeneratedRoundTrip() throws Exception {
        // 两端明确选择 SOAP 1.2 的 rpc/literal/wrapped 生成契约。
        MetConfiguration incoming = this.incomingConfiguration("1.2", "rpc", "literal", "wrapped");
        MetConfiguration outgoing = this.outgoingConfiguration("1.2", "rpc", "literal", "wrapped");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 通过公开入口启动两端，发布与订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<EchoService> subscription = client.publisher() //
                    .rsfService(EchoService.class) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 在线 WSDL 必须公开所选绑定，不能静默使用默认 document/wrapped 契约。
            int port = server.bindAddress("soap").getPort();
            URI wsdl = URI.create("http://127.0.0.1:" + port + "/services/EchoService?wsdl");
            HttpRequest request = HttpRequest.newBuilder(wsdl).timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<byte[]> response = this.http().send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            Document definition = this.parse(response.body());
            String bindingNamespace = "http://schemas.xmlsoap.org/wsdl/soap12/";
            NodeList bindings = definition.getElementsByTagNameNS(bindingNamespace, "binding");
            assertEquals(1, bindings.getLength());
            Element binding = (Element) bindings.item(0);
            assertEquals("rpc", binding.getAttribute("style"));
            NodeList bodies = definition.getElementsByTagNameNS(bindingNamespace, "body");
            assertEquals(2, bodies.getLength());
            Element inputBody = (Element) bodies.item(0);
            Element outputBody = (Element) bodies.item(1);
            assertEquals("literal", inputBody.getAttribute("use"));
            assertEquals("literal", outputBody.getAttribute("use"));
            assertEquals("urn:hasor:meteor:soap:EchoService", inputBody.getAttribute("namespace"));
            assertEquals("urn:hasor:meteor:soap:EchoService", outputBody.getAttribute("namespace"));
            NodeList messageParts = definition.getElementsByTagNameNS("http://schemas.xmlsoap.org/wsdl/", "part");
            assertEquals(2, messageParts.getLength());
            Element inputPart = (Element) messageParts.item(0);
            Element outputPart = (Element) messageParts.item(1);
            assertEquals("arg0", inputPart.getAttribute("name"));
            assertEquals("return", outputPart.getAttribute("name"));
            QName stringType = new QName(XMLConstants.W3C_XML_SCHEMA_NS_URI, "string");
            assertEquals(stringType, this.qualifiedName(inputPart, "type"));
            assertEquals(stringType, this.qualifiedName(outputPart, "type"));

            // 客户端根据独立生成的同一契约，经真实 HTTP 监听完成业务调用。
            EchoService remote = client.getRsfClient(server.bindAddress("soap")).getRemote(subscription);
            assertEquals("rpc literal SOAP 1.2", remote.echo("rpc literal SOAP 1.2"));
        }
    }

    @Test(timeout = 20000)
    public void soap11RpcEncodedSupportsGeneratedRoundTrip() throws Exception {
        // 两端明确选择 SOAP 1.1 的 rpc/encoded/wrapped 生成契约。
        MetConfiguration incoming = this.incomingConfiguration("1.1", "rpc", "encoded", "wrapped");
        MetConfiguration outgoing = this.outgoingConfiguration("1.1", "rpc", "encoded", "wrapped");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 通过公开入口启动两端，发布与订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<EchoService> subscription = client.publisher() //
                    .rsfService(EchoService.class) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 在线 WSDL 必须公开所选绑定，不能静默使用默认 document/wrapped 契约。
            int port = server.bindAddress("soap").getPort();
            URI wsdl = URI.create("http://127.0.0.1:" + port + "/services/EchoService?wsdl");
            HttpRequest request = HttpRequest.newBuilder(wsdl).timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<byte[]> response = this.http().send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            Document definition = this.parse(response.body());
            String bindingNamespace = "http://schemas.xmlsoap.org/wsdl/soap/";
            NodeList bindings = definition.getElementsByTagNameNS(bindingNamespace, "binding");
            assertEquals(1, bindings.getLength());
            Element binding = (Element) bindings.item(0);
            assertEquals("rpc", binding.getAttribute("style"));
            NodeList bodies = definition.getElementsByTagNameNS(bindingNamespace, "body");
            assertEquals(2, bodies.getLength());
            Element inputBody = (Element) bodies.item(0);
            Element outputBody = (Element) bodies.item(1);
            assertEquals("encoded", inputBody.getAttribute("use"));
            assertEquals("encoded", outputBody.getAttribute("use"));
            assertEquals("urn:hasor:meteor:soap:EchoService", inputBody.getAttribute("namespace"));
            assertEquals("urn:hasor:meteor:soap:EchoService", outputBody.getAttribute("namespace"));
            NodeList messageParts = definition.getElementsByTagNameNS("http://schemas.xmlsoap.org/wsdl/", "part");
            assertEquals(2, messageParts.getLength());
            Element inputPart = (Element) messageParts.item(0);
            Element outputPart = (Element) messageParts.item(1);
            assertEquals("arg0", inputPart.getAttribute("name"));
            assertEquals("return", outputPart.getAttribute("name"));
            QName stringType = new QName(XMLConstants.W3C_XML_SCHEMA_NS_URI, "string");
            assertEquals(stringType, this.qualifiedName(inputPart, "type"));
            assertEquals(stringType, this.qualifiedName(outputPart, "type"));
            String encoding = "http://schemas.xmlsoap.org/soap/encoding/";
            assertEquals(encoding, inputBody.getAttribute("encodingStyle"));
            assertEquals(encoding, outputBody.getAttribute("encodingStyle"));

            // 客户端根据独立生成的同一契约，经真实 HTTP 监听完成业务调用。
            EchoService remote = client.getRsfClient(server.bindAddress("soap")).getRemote(subscription);
            assertEquals("rpc encoded SOAP 1.1", remote.echo("rpc encoded SOAP 1.1"));
        }
    }

    @Test(timeout = 20000)
    public void soap12RpcEncodedSupportsGeneratedRoundTrip() throws Exception {
        // 两端明确选择 SOAP 1.2 的 rpc/encoded/wrapped 生成契约。
        MetConfiguration incoming = this.incomingConfiguration("1.2", "rpc", "encoded", "wrapped");
        MetConfiguration outgoing = this.outgoingConfiguration("1.2", "rpc", "encoded", "wrapped");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 通过公开入口启动两端，发布与订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<EchoService> subscription = client.publisher() //
                    .rsfService(EchoService.class) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 在线 WSDL 必须公开所选绑定，不能静默使用默认 document/wrapped 契约。
            int port = server.bindAddress("soap").getPort();
            URI wsdl = URI.create("http://127.0.0.1:" + port + "/services/EchoService?wsdl");
            HttpRequest request = HttpRequest.newBuilder(wsdl).timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<byte[]> response = this.http().send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            Document definition = this.parse(response.body());
            String bindingNamespace = "http://schemas.xmlsoap.org/wsdl/soap12/";
            NodeList bindings = definition.getElementsByTagNameNS(bindingNamespace, "binding");
            assertEquals(1, bindings.getLength());
            Element binding = (Element) bindings.item(0);
            assertEquals("rpc", binding.getAttribute("style"));
            NodeList bodies = definition.getElementsByTagNameNS(bindingNamespace, "body");
            assertEquals(2, bodies.getLength());
            Element inputBody = (Element) bodies.item(0);
            Element outputBody = (Element) bodies.item(1);
            assertEquals("encoded", inputBody.getAttribute("use"));
            assertEquals("encoded", outputBody.getAttribute("use"));
            assertEquals("urn:hasor:meteor:soap:EchoService", inputBody.getAttribute("namespace"));
            assertEquals("urn:hasor:meteor:soap:EchoService", outputBody.getAttribute("namespace"));
            NodeList messageParts = definition.getElementsByTagNameNS("http://schemas.xmlsoap.org/wsdl/", "part");
            assertEquals(2, messageParts.getLength());
            Element inputPart = (Element) messageParts.item(0);
            Element outputPart = (Element) messageParts.item(1);
            assertEquals("arg0", inputPart.getAttribute("name"));
            assertEquals("return", outputPart.getAttribute("name"));
            QName stringType = new QName(XMLConstants.W3C_XML_SCHEMA_NS_URI, "string");
            assertEquals(stringType, this.qualifiedName(inputPart, "type"));
            assertEquals(stringType, this.qualifiedName(outputPart, "type"));
            String encoding = "http://www.w3.org/2003/05/soap-encoding";
            assertEquals(encoding, inputBody.getAttribute("encodingStyle"));
            assertEquals(encoding, outputBody.getAttribute("encodingStyle"));

            // 客户端根据独立生成的同一契约，经真实 HTTP 监听完成业务调用。
            EchoService remote = client.getRsfClient(server.bindAddress("soap")).getRemote(subscription);
            assertEquals("rpc encoded SOAP 1.2", remote.echo("rpc encoded SOAP 1.2"));
        }
    }

    private MetConfiguration incomingConfiguration(String version, String style, String use, String parameterStyle) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("soap") //
                .contextPath("/services") //
                .option("soapVersion", version) //
                .option("soapStyle", style) //
                .option("soapUse", use) //
                .option("soapParameterStyle", parameterStyle);
        return configuration;
    }

    private MetConfiguration outgoingConfiguration(String version, String style, String use, String parameterStyle) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .protocol("soap") //
                .contextPath("/services") //
                .option("soapVersion", version) //
                .option("soapStyle", style) //
                .option("soapUse", use) //
                .option("soapParameterStyle", parameterStyle);
        return configuration;
    }

    private HttpClient http() {
        return HttpClient.newBuilder() //
                .connectTimeout(Duration.ofSeconds(15)) //
                .version(HttpClient.Version.HTTP_1_1) //
                .build();
    }

    private Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private QName qualifiedName(Element element, String attributeName) {
        String qualifiedName = element.getAttribute(attributeName);
        int separator = qualifiedName.indexOf(':');
        String prefix = separator < 0 ? null : qualifiedName.substring(0, separator);
        String namespace = element.lookupNamespaceURI(prefix);
        return new QName(namespace, qualifiedName.substring(separator + 1));
    }
}

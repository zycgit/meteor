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
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import jakarta.xml.soap.MessageFactory;
import jakarta.xml.soap.SOAPConstants;
import jakarta.xml.soap.SOAPElement;
import jakarta.xml.soap.SOAPMessage;
import jakarta.xml.ws.BindingProvider;
import jakarta.xml.ws.Dispatch;
import jakarta.xml.ws.Service;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.soap.*;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import static org.junit.Assert.*;

public class WebServicesGeneratedDefinitionTest {
    private static final String NAMESPACE_PREFIX = "urn:hasor:meteor:soap:";
    private static final String WSDL_NAMESPACE   = "http://schemas.xmlsoap.org/wsdl/";
    private static final String SOAP_BINDING     = "http://schemas.xmlsoap.org/wsdl/soap/";
    private static final String XSD_NAMESPACE    = XMLConstants.W3C_XML_SCHEMA_NS_URI;
    private static final String XSI_NAMESPACE    = XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI;

    @Test(timeout = 20000)
    public void wsdlIsGeneratedFromThePublishedJavaInterface() throws Exception {
        // 普通 Java 接口只声明 SOAP 别名，无需创建或绑定 WSDL 资源。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 从真实 HTTP 服务端点读取自动生成的 WSDL。
            URI endpoint = this.endpoint(context, "/services/EchoService");
            URI wsdlAddress = URI.create(endpoint + "?wsdl");
            HttpRequest request = this.get(wsdlAddress);
            HttpResponse<byte[]> response = this.http().send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            String contentType = response.headers().firstValue("content-type").orElse("");
            assertTrue(contentType, StringUtils.startsWithIgnoreCase(contentType, "text/xml"));

            // 契约暴露 Java 方法和内嵌类型定义，调用地址指向当前监听端点。
            Document wsdl = this.document(response.body());
            String namespace = NAMESPACE_PREFIX + "EchoService";
            assertEquals(namespace, wsdl.getDocumentElement().getAttribute("targetNamespace"));
            Element service = (Element) wsdl.getElementsByTagNameNS(WSDL_NAMESPACE, "service").item(0);
            assertEquals("EchoService", service.getAttribute("name"));
            Element port = (Element) service.getElementsByTagNameNS(WSDL_NAMESPACE, "port").item(0);
            assertEquals("EchoServicePort", port.getAttribute("name"));
            Element address = (Element) port.getElementsByTagNameNS(SOAP_BINDING, "address").item(0);
            assertEquals(endpoint.toString(), address.getAttribute("location"));
            Element types = (Element) wsdl.getElementsByTagNameNS(WSDL_NAMESPACE, "types").item(0);
            assertEquals(1, types.getElementsByTagNameNS(XSD_NAMESPACE, "schema").getLength());
            Element schema = (Element) types.getElementsByTagNameNS(XSD_NAMESPACE, "schema").item(0);
            assertEquals(namespace, schema.getAttribute("targetNamespace"));
            assertEquals(0, wsdl.getElementsByTagNameNS(XSD_NAMESPACE, "import").getLength());
            assertEquals(4, schema.getElementsByTagNameNS(XSD_NAMESPACE, "element").getLength());
            Element operation = (Element) wsdl.getElementsByTagNameNS(SOAP_BINDING, "operation").item(0);
            assertEquals(namespace + "/echo", operation.getAttribute("soapAction"));
        }
    }

    @Test(timeout = 20000)
    public void generatedXsdIsPublishedAtTheServiceEndpoint() throws Exception {
        // 从公开入口发布无手写资源的 SOAP 服务。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 独立 XSD 地址继续提供自动生成的类型定义。
            URI xsdAddress = this.endpoint(context, "/services/EchoService?xsd=types.xsd");
            HttpRequest request = this.get(xsdAddress);
            HttpResponse<byte[]> response = this.http().send(request, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, response.statusCode());
            String contentType = response.headers().firstValue("content-type").orElse("");
            assertTrue(contentType, StringUtils.startsWithIgnoreCase(contentType, "application/xml"));

            // 标准 XSD 实现能编译该定义，输入和输出包装对应 Java 方法。
            assertNotNull(this.schema(response.body()));
            Document xsd = this.document(response.body());
            assertEquals(NAMESPACE_PREFIX + "EchoService", xsd.getDocumentElement().getAttribute("targetNamespace"));
            NodeList elements = xsd.getElementsByTagNameNS(XSD_NAMESPACE, "element");
            boolean requestWrapper = false;
            boolean responseWrapper = false;
            boolean requestParameter = false;
            boolean responseResult = false;
            for (int index = 0; index < elements.getLength(); index++) {
                Element element = (Element) elements.item(index);
                requestWrapper |= StringUtils.equals("echo", element.getAttribute("name"));
                responseWrapper |= StringUtils.equals("echoResponse", element.getAttribute("name"));
                requestParameter |= StringUtils.equals("arg0", element.getAttribute("name"));
                responseResult |= StringUtils.equals("return", element.getAttribute("name"));
            }
            assertTrue("Generated schema must declare the request wrapper", requestWrapper);
            assertTrue("Generated schema must declare the response wrapper", responseWrapper);
            assertTrue("Generated schema must declare the Java parameter", requestParameter);
            assertTrue("Generated schema must declare the Java return value", responseResult);
        }
    }

    @Test(timeout = 20000)
    public void metroClientCallsTheServiceUsingGeneratedWsdl() throws Exception {
        // Meteor 服务只发布 Java 接口，让 SOAP 扩展提供标准契约。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .register();

            // 标准 Metro 客户端从网络加载带内嵌 XSD 的 WSDL，并调用自动生成的 operation。
            String namespace = NAMESPACE_PREFIX + "EchoService";
            URL wsdl = this.endpoint(context, "/services/EchoService?wsdl").toURL();
            Dispatch<SOAPMessage> dispatch = this.dispatch(wsdl, "EchoService", "echo");
            SOAPMessage request = this.message(namespace, "echo");
            SOAPElement operation = (SOAPElement) request.getSOAPBody().getFirstChild();
            operation.addChildElement(new QName("arg0")).addTextNode("hello generated SOAP");
            request.saveChanges();
            SOAPMessage response = dispatch.invoke(request);

            // 返回值和包装名符合发布的契约。
            NodeList wrappers = response.getSOAPBody().getElementsByTagNameNS(namespace, "echoResponse");
            assertEquals(1, wrappers.getLength());
            NodeList results = response.getSOAPBody().getElementsByTagNameNS("", "return");
            assertEquals(1, results.getLength());
            assertEquals("hello generated SOAP", results.item(0).getTextContent());
        }
    }

    @Test(timeout = 20000)
    public void pojoCollectionAndNullFieldsConformToTheGeneratedXsd() throws Exception {
        // 发布无 XML 注解的 POJO 接口，记录经过 SOAP 解码后的业务对象。
        ProfileServiceImpl service = new ProfileServiceImpl();
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(ProfileService.class, service) //
                    .aliasName("soap", "ProfileService") //
                    .protocol("soap") //
                    .register();

            // 请求包含重复集合元素和可空字符串、数值，先用线上生成的 XSD 校验正文。
            String namespace = NAMESPACE_PREFIX + "ProfileService";
            URI endpoint = this.endpoint(context, "/services/ProfileService");
            URI xsdAddress = URI.create(endpoint + "?xsd=types.xsd");
            HttpResponse<byte[]> xsd = this.http().send(this.get(xsdAddress), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, xsd.statusCode());
            Schema schema = this.schema(xsd.body());
            SOAPMessage request = this.message(namespace, "roundTrip");
            SOAPElement operation = (SOAPElement) request.getSOAPBody().getFirstChild();
            SOAPElement argument = operation.addChildElement(new QName("arg0"));
            argument.addChildElement(new QName("name")).addTextNode("Meteor");
            argument.addChildElement(new QName("tags")).addTextNode("java");
            argument.addChildElement(new QName("tags")).addTextNode("soap");
            this.nil(argument.addChildElement(new QName("comment")));
            this.nil(argument.addChildElement(new QName("rank")));
            request.saveChanges();
            schema.newValidator().validate(new DOMSource(operation));

            // Metro 发送真实请求，Meteor 将普通 Java 对象编码为 SOAP 响应。
            URL wsdl = URI.create(endpoint + "?wsdl").toURL();
            Dispatch<SOAPMessage> dispatch = this.dispatch(wsdl, "ProfileService", "roundTrip");
            SOAPMessage response = dispatch.invoke(request);
            Profile received = service.getReceived();
            assertNotNull(received);
            assertEquals("Meteor", received.getName());
            assertEquals(List.of("java", "soap"), received.getTags());
            assertNull(received.getComment());
            assertNull(received.getRank());

            // 校验实际响应正文，集合仍有两个值，空数值保留 xsi:nil 语义。
            NodeList wrappers = response.getSOAPBody().getElementsByTagNameNS(namespace, "roundTripResponse");
            assertEquals(1, wrappers.getLength());
            schema.newValidator().validate(new DOMSource(wrappers.item(0)));
            NodeList tags = response.getSOAPBody().getElementsByTagNameNS("", "tags");
            assertEquals(2, tags.getLength());
            assertEquals("java", tags.item(0).getTextContent());
            assertEquals("soap", tags.item(1).getTextContent());
            Element rank = (Element) response.getSOAPBody().getElementsByTagNameNS("", "rank").item(0);
            assertNotNull(rank);
            assertEquals("true", rank.getAttributeNS(XSI_NAMESPACE, "nil"));
        }
    }

    @Test(timeout = 20000)
    public void emptyCollectionParameterAndResultRemainEmptyOverSoap() throws Exception {
        // 两端仅声明同一个 List 参数和返回值的 Java 契约。
        AtomicReference<List<String>> received = new AtomicReference<>();
        CollectionService service = values -> {
            received.set(values);
            return values;
        };
        MetConfiguration incoming = this.configuration();
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("soap");
        outgoing.connector("http") //
                .protocol("soap") //
                .contextPath("/services");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(CollectionService.class, service) //
                    .aliasName("soap", "CollectionService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<CollectionService> subscribed = client.publisher() //
                    .rsfService(CollectionService.class) //
                    .aliasName("soap", "CollectionService") //
                    .protocol("soap") //
                    .register();

            // 真实 HTTP 往返中的空 XML 集合必须仍是空集合，不能变成 null。
            CollectionService remote = client.getRsfClient(server.bindAddress("soap")).getRemote(subscribed);
            List<String> result = remote.echo(List.of());
            List<String> receivedValues = received.get();
            assertNotNull(receivedValues);
            assertTrue(receivedValues.isEmpty());
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
    }

    @Test(timeout = 20000)
    public void messageAnnotationGeneratesCallableOneWayWsdl() throws Exception {
        // Java 方法上的 MetMessage 注解决定生成的契约为 one-way。
        SignalServiceImpl signal = new SignalServiceImpl();
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(SignalService.class, signal) //
                    .aliasName("soap", "SignalService") //
                    .protocol("soap") //
                    .register();

            // 线上 WSDL 的 portType 和 binding 均只有 input，没有 output。
            URI wsdlAddress = this.endpoint(context, "/services/SignalService?wsdl");
            HttpRequest wsdlRequest = this.get(wsdlAddress);
            HttpResponse<byte[]> definition = this.http().send(wsdlRequest, HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, definition.statusCode());
            Document wsdl = this.document(definition.body());
            assertEquals(2, wsdl.getElementsByTagNameNS(WSDL_NAMESPACE, "input").getLength());
            assertEquals(0, wsdl.getElementsByTagNameNS(WSDL_NAMESPACE, "output").getLength());

            // Metro 按 one-way 契约调用，后台业务能收到发送的消息。
            String namespace = NAMESPACE_PREFIX + "SignalService";
            Dispatch<SOAPMessage> dispatch = this.dispatch(wsdlAddress.toURL(), "SignalService", "signal");
            SOAPMessage request = this.message(namespace, "signal");
            SOAPElement operation = (SOAPElement) request.getSOAPBody().getFirstChild();
            operation.addChildElement(new QName("arg0")).addTextNode("generated notice");
            request.saveChanges();
            dispatch.invokeOneWay(request);
            assertTrue("Generated one-way service was not invoked", signal.awaitInvocation(5, TimeUnit.SECONDS));
            assertEquals("generated notice", signal.getMessage());
        }
    }

    @Test(timeout = 20000)
    public void generatedDefinitionsUseTheExactServiceMount() throws Exception {
        // 显式 exact 挂载取代默认前缀，定义页使用同一业务端点。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(EchoService.class, new EchoServiceImpl()) //
                    .aliasName("soap", "EchoService") //
                    .protocol("soap") //
                    .contextPathByExact("soap", "/api/echo") //
                    .register();

            // 从精确挂载位置读取生成定义，并确认默认挂载不会泄漏服务。
            URI endpoint = this.endpoint(context, "/api/echo");
            HttpClient http = this.http();
            URI wsdlAddress = URI.create(endpoint + "?wsdl");
            HttpResponse<byte[]> wsdl = http.send(this.get(wsdlAddress), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, wsdl.statusCode());
            Document document = this.document(wsdl.body());
            Element address = (Element) document.getElementsByTagNameNS(SOAP_BINDING, "address").item(0);
            assertEquals(endpoint.toString(), address.getAttribute("location"));
            Element schema = (Element) document.getElementsByTagNameNS(XSD_NAMESPACE, "schema").item(0);
            assertEquals(NAMESPACE_PREFIX + "EchoService", schema.getAttribute("targetNamespace"));
            assertEquals(0, document.getElementsByTagNameNS(XSD_NAMESPACE, "import").getLength());

            // 独立类型定义也从精确端点读取，并可被标准 XSD 实现编译。
            URI xsdAddress = URI.create(endpoint + "?xsd=types.xsd");
            HttpResponse<byte[]> xsd = http.send(this.get(xsdAddress), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, xsd.statusCode());
            assertNotNull(this.schema(xsd.body()));

            // 默认服务路径不可再获取该精确挂载的契约。
            URI defaultAddress = this.endpoint(context, "/services/EchoService?wsdl");
            HttpResponse<byte[]> hidden = http.send(this.get(defaultAddress), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(404, hidden.statusCode());

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

    private URI endpoint(MetContext context, String path) {
        int port = context.bindAddress("soap").getPort();
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpClient http() {
        return HttpClient.newBuilder() //
                .connectTimeout(Duration.ofSeconds(15)) //
                .version(HttpClient.Version.HTTP_1_1) //
                .build();
    }

    private HttpRequest get(URI address) {
        return HttpRequest.newBuilder(address) //
                .timeout(Duration.ofSeconds(15)) //
                .GET() //
                .build();
    }

    private Document document(byte[] body) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
    }

    private Schema schema(byte[] body) throws Exception {
        SchemaFactory factory = SchemaFactory.newInstance(XSD_NAMESPACE);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newSchema(new StreamSource(new ByteArrayInputStream(body)));
    }

    private Dispatch<SOAPMessage> dispatch(URL wsdl, String alias, String operation) {
        String namespace = NAMESPACE_PREFIX + alias;
        Service service = Service.create(wsdl, new QName(namespace, alias));
        QName port = new QName(namespace, alias + "Port");
        Dispatch<SOAPMessage> dispatch = service.createDispatch(port, SOAPMessage.class, Service.Mode.MESSAGE);
        Map<String, Object> requestContext = dispatch.getRequestContext();
        requestContext.put(BindingProvider.SOAPACTION_USE_PROPERTY, Boolean.TRUE);
        requestContext.put(BindingProvider.SOAPACTION_URI_PROPERTY, namespace + "/" + operation);
        return dispatch;
    }

    private SOAPMessage message(String namespace, String operation) throws Exception {
        MessageFactory factory = MessageFactory.newInstance(SOAPConstants.SOAP_1_1_PROTOCOL);
        SOAPMessage request = factory.createMessage();
        request.getSOAPBody().addBodyElement(new QName(namespace, operation, "types"));
        return request;
    }

    private void nil(SOAPElement element) throws Exception {
        element.addNamespaceDeclaration("xsi", XSI_NAMESPACE);
        element.addAttribute(new QName(XSI_NAMESPACE, "nil", "xsi"), "true");
    }
}

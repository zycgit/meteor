/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.soap;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Source;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.soap.MetXmlMapService;
import net.hasor.example.protocol.soap.MetXmlMapValue;
import net.hasor.example.protocol.soap.MetXmlOrder;
import net.hasor.example.protocol.soap.MetXmlOrderService;
import net.hasor.example.protocol.soap.MetXmlWrappedUserService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.soap.definition.SoapWsdlResource;
import org.junit.Test;
import org.w3c.dom.*;
import org.w3c.dom.bootstrap.DOMImplementationRegistry;
import org.w3c.dom.ls.DOMImplementationLS;
import org.w3c.dom.ls.LSInput;
import static org.junit.Assert.*;

public class MetXmlContractScenarioTest {
    private static final String SERVICE_NAMESPACE = MetXmlOrderService.NAMESPACE;

    @Test(timeout = 30000)
    public void dynamicMapNamesAndSharedAttributeWrapperRoundTripThroughThePublishedSchema() throws Exception {
        // 正式发布普通接口，动态 Map 名称与静态属性共用实际父元素和 settings 包装。
        MetConfiguration configuration = this.configuration("1.1");
        AtomicReference<MetXmlMapValue> decoded = new AtomicReference<>();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(MetXmlMapService.class, request -> {
                        decoded.set(request);

                        // 元素 Object Map 保留基础类型，属性 Map 仍使用 XML 属性文本。
                        MetXmlMapValue result = new MetXmlMapValue();
                        result.id = request.id;
                        result.settings.version = request.settings.version;
                        result.attributes = new LinkedHashMap<>(request.attributes);
                        result.settings.values = new LinkedHashMap<>(request.settings.values);
                        result.settings.values.put("timeout", 30);
                        result.elements = new LinkedHashMap<>(request.elements);
                        result.elements.put("priority", 7);
                        result.elements.put("active", true);
                        return result;
                    }) //
                    .aliasName("soap", "MetXmlMapService") //
                    .protocol("soap") //
                    .register();
            int port = context.getListenAddress("soap").getPort();
            URI endpoint = URI.create("http://127.0.0.1:" + port + "/services/MetXmlMapService");
            HttpRequest wsdlRequest = HttpRequest.newBuilder(URI.create(endpoint + "?wsdl")).GET().build();
            HttpResponse<byte[]> wsdlResponse = this.send(wsdlRequest);
            assertEquals(200, wsdlResponse.statusCode());
            Document wsdl = this.parse(wsdlResponse.body());
            NodeList schemas = wsdl.getElementsByTagNameNS(XMLConstants.W3C_XML_SCHEMA_NS_URI, "schema");
            Schema schema = this.schema(schemas, wsdlRequest.uri());

            // 标准 JAXP 校验器先验证实际 WSDL 的通配声明，再走真实 HTTP 请求解码。
            String namespace = MetXmlMapService.NAMESPACE;
            String envelope = """
                    <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                   xmlns:xs="http://www.w3.org/2001/XMLSchema">
                        <soap:Body>
                            <m:echo xmlns:m="%s">
                                <request id="123" extra="trace">
                                    <settings version="1.0" timeout="30" mode="fast"/>
                                    <elements>
                                        <priority xsi:type="xs:int">7</priority>
                                        <active xsi:type="xs:boolean">true</active>
                                    </elements>
                                </request>
                            </m:echo>
                        </soap:Body>
                    </soap:Envelope>
                    """.formatted(namespace);
            byte[] inputBytes = envelope.getBytes(StandardCharsets.UTF_8);
            Document input = this.parse(inputBytes);
            Node operation = input.getElementsByTagNameNS(namespace, "echo").item(0);
            schema.newValidator().validate(new DOMSource(operation));
            HttpRequest invocation = HttpRequest.newBuilder(endpoint) //
                    .header("Content-Type", "text/xml; charset=utf-8") //
                    .header("SOAPAction", "\"" + namespace + "/echo\"") //
                    .POST(HttpRequest.BodyPublishers.ofByteArray(inputBytes)) //
                    .build();
            HttpResponse<byte[]> response = this.send(invocation);
            assertEquals(200, response.statusCode());

            // 元素按标准 xsi:type 保留数字和布尔，属性 Map 为文本，静态属性不落入动态 Map。
            MetXmlMapValue received = decoded.get();
            assertNotNull(received);
            assertEquals("123", received.id);
            assertEquals("1.0", received.settings.version);
            assertEquals(Map.of("extra", "trace"), received.attributes);
            assertEquals(Map.of("timeout", "30", "mode", "fast"), received.settings.values);
            assertEquals(Map.of("priority", 7, "active", true), received.elements);
            assertEquals(String.class, received.settings.values.get("timeout").getClass());
            assertEquals(Integer.class, received.elements.get("priority").getClass());
            assertEquals(Boolean.class, received.elements.get("active").getClass());

            // 响应仍符合发布的 Schema，settings 按首个成员 order 排在 elements 前。
            Document output = this.parse(response.body());
            Node resultWrapper = output.getElementsByTagNameNS(namespace, "echoResponse").item(0);
            assertNotNull(resultWrapper);
            schema.newValidator().validate(new DOMSource(resultWrapper));
            Element result = (Element) output.getElementsByTagName("result").item(0);
            assertNotNull(result);
            assertEquals("123", result.getAttribute("id"));
            assertEquals("trace", result.getAttribute("extra"));
            assertFalse(result.hasAttribute("version"));
            assertFalse(result.hasAttribute("timeout"));
            List<QName> expectedChildren = new ArrayList<>();
            // settings 共组的首个 order 为 5，elements 的 order 为 10。
            expectedChildren.add(new QName("", "settings"));
            expectedChildren.add(new QName("", "elements"));
            assertEquals(expectedChildren, this.elementNames(result));
            Element settings = (Element) result.getElementsByTagName("settings").item(0);
            assertEquals("1.0", settings.getAttribute("version"));
            assertEquals("30", settings.getAttribute("timeout"));
            assertEquals("fast", settings.getAttribute("mode"));
            assertFalse(settings.hasAttribute("id"));
            assertFalse(settings.hasAttribute("extra"));
            Element elements = (Element) result.getElementsByTagName("elements").item(0);
            assertSame(result, settings.getParentNode());
            assertSame(result, elements.getParentNode());
            assertEquals(2, this.elementNames(elements).size());
            assertEquals("7", elements.getElementsByTagName("priority").item(0).getTextContent());
            assertEquals("true", elements.getElementsByTagName("active").item(0).getTextContent());
        }
    }

    @Test(timeout = 30000)
    public void singleObjectWrapperRoundTripsThroughThePublishedSchema() throws Exception {
        // 正式发布普通 Java 接口，单个 User 通过 wrap 增加 users 外层元素。
        MetConfiguration configuration = this.configuration("1.1");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(MetXmlWrappedUserService.class, value -> value) //
                    .aliasName("soap", "MetXmlWrappedUserService") //
                    .protocol("soap") //
                    .register();
            int port = context.getListenAddress("soap").getPort();
            URI endpoint = URI.create("http://127.0.0.1:" + port + "/services/MetXmlWrappedUserService");
            HttpRequest wsdlRequest = HttpRequest.newBuilder(URI.create(endpoint + "?wsdl")).GET().build();
            HttpResponse<byte[]> wsdlResponse = this.send(wsdlRequest);
            assertEquals(200, wsdlResponse.statusCode());
            Document wsdl = this.parse(wsdlResponse.body());
            NodeList schemas = wsdl.getElementsByTagNameNS(XMLConstants.W3C_XML_SCHEMA_NS_URI, "schema");
            Schema schema = this.schema(schemas, wsdlRequest.uri());

            // 先用实际发布的 XSD 校验请求，再通过 HTTP 调用服务端的真实解码链。
            String namespace = MetXmlWrappedUserService.NAMESPACE;
            String envelope = """
                    <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                        <soap:Body>
                            <m:echo xmlns:m="%s">
                                <request><users><user id="123"/></users></request>
                            </m:echo>
                        </soap:Body>
                    </soap:Envelope>
                    """.formatted(namespace);
            byte[] inputBytes = envelope.getBytes(StandardCharsets.UTF_8);
            Document input = this.parse(inputBytes);
            Node operation = input.getElementsByTagNameNS(namespace, "echo").item(0);
            schema.newValidator().validate(new DOMSource(operation));
            HttpRequest invocation = HttpRequest.newBuilder(endpoint) //
                    .header("Content-Type", "text/xml; charset=utf-8") //
                    .header("SOAPAction", "\"" + namespace + "/echo\"") //
                    .POST(HttpRequest.BodyPublishers.ofByteArray(inputBytes)) //
                    .build();
            HttpResponse<byte[]> response = this.send(invocation);
            assertEquals(200, response.statusCode());

            // 响应再次满足同一份契约，id 留在内层 user，而非自动包装的 users 上。
            Document output = this.parse(response.body());
            Node resultWrapper = output.getElementsByTagNameNS(namespace, "echoResponse").item(0);
            assertNotNull(resultWrapper);
            schema.newValidator().validate(new DOMSource(resultWrapper));
            Element result = (Element) output.getElementsByTagName("result").item(0);
            assertNotNull(result);
            Element users = (Element) result.getElementsByTagName("users").item(0);
            assertNotNull(users);
            assertSame(result, users.getParentNode());
            assertFalse(users.hasAttribute("id"));
            assertEquals(1, users.getElementsByTagName("user").getLength());
            Element user = (Element) users.getElementsByTagName("user").item(0);
            assertSame(users, user.getParentNode());
            assertEquals("123", user.getAttribute("id"));
        }
    }

    @Test(timeout = 30000)
    public void metroTypedClientConsumesTheSoap11GeneratedWsdl() throws Exception {
        // Meteor 只发布普通 Java 接口和自己的 XML 注解，不发布 JAXB 生成类型。
        MetConfiguration configuration = this.configuration("1.1");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(MetXmlOrderService.class, value -> value) //
                    .aliasName("soap", "MetXmlOrderService") //
                    .protocol("soap") //
                    .register();

            // Metro 从线上 WSDL 组装标准 typed 客户端，用其自己的 JAXB DTO 编解码。
            URL wsdl = this.endpoint(context, "?wsdl").toURL();
            QName serviceName = new QName(SERVICE_NAMESPACE, "MetXmlOrderService");
            QName portName = new QName(SERVICE_NAMESPACE, "MetXmlOrderServicePort");
            Map<String, Object> invocation = SoapPeerProcess.dispatch(wsdl, serviceName, portName, null);
            invocation.put("operation", "typed-order");
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("id", "metro-42");
            input.put("language", "zh-CN");
            input.put("buyer", "Alice");
            input.put("delivery", "Bob");
            input.put("lines", List.of("one", "two"));
            input.put("state", "ready-to-send");
            input.put("memo", "typed Metro");
            invocation.put("order", input);
            Map<String, Object> result = SoapPeerProcess.metro(invocation);

            // 属性、同名不同命名空间元素和集合均由标准客户端正确还原。
            assertEquals("metro-42", result.get("id"));
            assertEquals("zh-CN", result.get("language"));
            assertEquals("Alice", result.get("buyer"));
            assertEquals("Bob", result.get("delivery"));
            assertEquals(List.of("one", "two"), result.get("lines"));
            assertEquals("ready-to-send", result.get("state"));
            assertEquals("typed Metro", result.get("memo"));
        }
    }

    @Test(timeout = 30000)
    public void metroTypedClientConsumesTheSoap12GeneratedWsdl() throws Exception {
        // Meteor 只发布普通 Java 接口和自己的 XML 注解，不发布 JAXB 生成类型。
        MetConfiguration configuration = this.configuration("1.2");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(MetXmlOrderService.class, value -> value) //
                    .aliasName("soap", "MetXmlOrderService") //
                    .protocol("soap") //
                    .register();

            // Metro 从线上 WSDL 组装标准 typed 客户端，用其自己的 JAXB DTO 编解码。
            URL wsdl = this.endpoint(context, "?wsdl").toURL();
            QName serviceName = new QName(SERVICE_NAMESPACE, "MetXmlOrderService");
            QName portName = new QName(SERVICE_NAMESPACE, "MetXmlOrderServicePort");
            Map<String, Object> invocation = SoapPeerProcess.dispatch(wsdl, serviceName, portName, null);
            invocation.put("operation", "typed-order");
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("id", "metro-42");
            input.put("language", "zh-CN");
            input.put("buyer", "Alice");
            input.put("delivery", "Bob");
            input.put("lines", List.of("one", "two"));
            input.put("state", "ready-to-send");
            input.put("memo", "typed Metro");
            invocation.put("order", input);
            Map<String, Object> result = SoapPeerProcess.metro(invocation);

            // 属性、同名不同命名空间元素和集合均由标准客户端正确还原。
            assertEquals("metro-42", result.get("id"));
            assertEquals("zh-CN", result.get("language"));
            assertEquals("Alice", result.get("buyer"));
            assertEquals("Bob", result.get("delivery"));
            assertEquals(List.of("one", "two"), result.get("lines"));
            assertEquals("ready-to-send", result.get("state"));
            assertEquals("typed Metro", result.get("memo"));
        }
    }

    @Test(timeout = 30000)
    public void generatedSchemasValidateTheAnnotatedWireNamesAndOrder() throws Exception {
        // 注解定义类型、字段及属性各自的命名空间，WSDL 公开完整的多 schema 契约。
        MetConfiguration configuration = this.configuration("1.1");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(MetXmlOrderService.class, value -> value) //
                    .aliasName("soap", "MetXmlOrderService") //
                    .protocol("soap") //
                    .register();
            HttpRequest wsdlRequest = HttpRequest.newBuilder(this.endpoint(context, "?wsdl")).GET().build();
            HttpResponse<byte[]> wsdlResponse = this.send(wsdlRequest);
            assertEquals(200, wsdlResponse.statusCode());
            Document wsdl = this.parse(wsdlResponse.body());
            NodeList schemas = wsdl.getElementsByTagNameNS(XMLConstants.W3C_XML_SCHEMA_NS_URI, "schema");
            assertTrue("Each mapped namespace must have its own schema", schemas.getLength() >= 5);
            Schema schema = this.schema(schemas, wsdlRequest.uri());

            // 请求使用参数注解指定的 order 名称，并在实际调用前通过标准 JAXP 校验。
            byte[] envelope = this.envelope("meta:id=\"wire-42\"", "buyer", "Alice");
            Document input = this.parse(envelope);
            Node request = input.getElementsByTagNameNS(SERVICE_NAMESPACE, "echo").item(0);
            schema.newValidator().validate(new DOMSource(request));
            HttpRequest invocation = this.invocation(context, envelope);
            HttpResponse<byte[]> response = this.send(invocation);
            assertEquals(200, response.statusCode());

            // 真实返回正文同时满足发布的 XSD、方法返回名和成员 order 指定的字段次序。
            Document output = this.parse(response.body());
            Node wrapper = output.getElementsByTagNameNS(SERVICE_NAMESPACE, "echoResponse").item(0);
            assertNotNull(wrapper);
            schema.newValidator().validate(new DOMSource(wrapper));
            Element result = (Element) output.getElementsByTagNameNS(SERVICE_NAMESPACE, "result").item(0);
            assertNotNull(result);
            assertEquals("wire-42", result.getAttributeNS(MetXmlOrder.ATTRIBUTES, "id"));
            List<QName> expected = List.of( //
                    new QName(MetXmlOrder.BUYER, "name"), //
                    new QName(MetXmlOrder.DELIVERY, "name"), //
                    new QName(MetXmlOrder.TYPES, "lines"), //
                    new QName(MetXmlOrder.TYPES, "status"), //
                    new QName("", "note"));
            assertEquals(expected, this.elementNames(result));
            Node state = output.getElementsByTagNameNS(MetXmlOrder.TYPES, "status").item(0);
            assertEquals("ready-to-send", state.getTextContent());
        }
    }

    @Test(timeout = 30000)
    public void publishedXsdImportsResolveThroughTheServiceEndpoint() throws Exception {
        // 类型和属性使用多个命名空间，独立 XSD 页面必须公开可访问的 import 地址。
        MetConfiguration configuration = this.configuration("1.1");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(MetXmlOrderService.class, value -> value) //
                    .aliasName("soap", "MetXmlOrderService") //
                    .protocol("soap") //
                    .register();

            // 标准 JAXP 客户端直接从主 XSD 地址加载依赖，由本场景服务端提供 HTTP 资源。
            URL xsd = this.endpoint(context, "?xsd=types.xsd").toURL();
            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "http");
            Schema schema = factory.newSchema(xsd);
            assertNotNull(schema);
        }
    }

    @Test(timeout = 30000)
    public void missingRequiredAttributeIsRejectedBeforeTheJavaServiceRuns() throws Exception {
        // 属性声明为 required，业务计数可观察 schema 校验是否发生在执行之前。
        AtomicInteger invoked = new AtomicInteger();
        MetConfiguration configuration = this.configuration("1.1");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(MetXmlOrderService.class, value -> {
                        invoked.incrementAndGet();
                        return value;
                    }) //
                    .aliasName("soap", "MetXmlOrderService") //
                    .protocol("soap") //
                    .register();

            // 请求遗漏 id 属性，其他必填字段与元素顺序均符合生成契约。
            byte[] envelope = this.envelope("", "buyer", "Alice");
            HttpResponse<byte[]> response = this.send(this.invocation(context, envelope));
            assertEquals(500, response.statusCode());
            String fault = new String(response.body(), StandardCharsets.UTF_8);
            assertTrue(fault, fault.contains("SOAP Body does not match its XML Schema"));
            assertEquals(0, invoked.get());
        }
    }

    @Test(timeout = 30000)
    public void wrongDtoFieldNamespaceIsRejectedBeforeTheJavaServiceRuns() throws Exception {
        // DTO 中两个 name 字段属于不同命名空间，不能仅按 localName 互相替代。
        AtomicInteger invoked = new AtomicInteger();
        MetConfiguration configuration = this.configuration("1.1");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher() //
                    .rsfService(MetXmlOrderService.class, value -> {
                        invoked.incrementAndGet();
                        return value;
                    }) //
                    .aliasName("soap", "MetXmlOrderService") //
                    .protocol("soap") //
                    .register();

            // 用 delivery 命名空间冒充 buyer，验证错误在 Java DTO 构造之前被拒绝。
            byte[] envelope = this.envelope("meta:id=\"wrong-namespace\"", "delivery", "Alice");
            HttpResponse<byte[]> response = this.send(this.invocation(context, envelope));
            assertEquals(500, response.statusCode());
            String fault = new String(response.body(), StandardCharsets.UTF_8);
            assertTrue(fault, fault.contains("SOAP Body does not match its XML Schema"));
            assertEquals(0, invoked.get());
        }
    }

    @Test(timeout = 30000)
    public void meteorClientCanImportThePublishedMultiNamespaceContract() throws Exception {
        // 先启动两个容器，再发布带自定义命名空间及 xml:lang 的普通 Java 服务。
        MetConfiguration incoming = this.configuration("1.1");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("soap");
        outgoing.connector("http").protocol("soap").contextPath("/services");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(MetXmlOrderService.class, value -> value) //
                    .aliasName("soap", "MetXmlOrderService") //
                    .protocol("soap") //
                    .register();

            // 从真正的 WSDL 地址载入契约，并跟随同源查询式 XSD 引用。
            SoapWsdlResource resource = new SoapWsdlResource(this.endpoint(server, "?wsdl").toURL());
            MetBindInfo<MetXmlOrderService> subscription = client.publisher() //
                    .rsfService(MetXmlOrderService.class) //
                    .aliasName("soap", "MetXmlOrderService") //
                    .protocol("soap") //
                    .bindResource("soap", resource) //
                    .register();
            MetXmlOrderService remote = client.getRsfClient(server.getListenAddress("soap")) //
                    .getRemote(subscription);
            MetXmlOrder input = new MetXmlOrder();
            input.setId("online-wsdl");
            input.setLanguage("zh-CN");
            input.setBuyer("Alice");
            input.setDelivery("Bob");
            input.setLines(List.of("loaded contract"));
            MetXmlOrder result = remote.echo(input);

            // XML 标准属性与业务元素均通过导入契约往返，不依赖自动生成客户端类型。
            assertEquals("online-wsdl", result.getId());
            assertEquals("zh-CN", result.getLanguage());
            assertEquals("Alice", result.getBuyer());
            assertEquals("Bob", result.getDelivery());
            assertEquals(List.of("loaded contract"), result.getLines());
        }
    }

    private MetConfiguration configuration(String version) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        configuration.connector("http") //
                .bind("127.0.0.1", 0) //
                .protocol("soap") //
                .contextPath("/services") //
                .option("soapVersion", version);
        return configuration;
    }

    private URI endpoint(MetContext context, String suffix) {
        int port = context.getListenAddress("soap").getPort();
        return URI.create("http://127.0.0.1:" + port + "/services/MetXmlOrderService" + suffix);
    }

    private HttpRequest invocation(MetContext context, byte[] envelope) {
        return HttpRequest.newBuilder(this.endpoint(context, "")) //
                .header("Content-Type", "text/xml; charset=utf-8") //
                .header("SOAPAction", "\"" + SERVICE_NAMESPACE + "/echo\"") //
                .POST(HttpRequest.BodyPublishers.ofByteArray(envelope)) //
                .build();
    }

    private HttpResponse<byte[]> send(HttpRequest request) throws Exception {
        // HTTP Future.get 使用有界等待，避免将宿主墙钟跳变作为协议超时。
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(request, //
                HttpResponse.BodyHandlers.ofByteArray());
        try {
            return pending.get(10, TimeUnit.SECONDS);
        } finally {
            pending.cancel(true);
        }
    }

    private Document parse(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private Schema schema(NodeList schemas, URI wsdlAddress) throws Exception {
        // 使用 WSDL 内嵌的全部 schema，标准校验器不依赖 Meteor 的映射或内部解析器。
        Source[] sources = new Source[schemas.getLength()];
        Map<String, String> documents = new LinkedHashMap<>();
        Map<String, String> identifiers = new LinkedHashMap<>();
        for (int index = 0; index < sources.length; index++) {
            Element schema = (Element) schemas.item(index);
            Document document = this.schemaDocument(schema);
            String systemId = "file:/met-xml/schema-" + index + ".xsd";
            sources[index] = new DOMSource(document, systemId);
            String namespace = schema.getAttribute("targetNamespace");
            TransformerFactory transformer = TransformerFactory.newInstance();
            transformer.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            transformer.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            transformer.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            StringWriter serialized = new StringWriter();
            transformer.newTransformer().transform(new DOMSource(document), new StreamResult(serialized));
            documents.put(namespace, serialized.toString());
            identifiers.put(namespace, systemId);
        }

        // 预先取得 WSDL 显式引用的标准 schema，编译阶段仍完全使用离线解析。
        for (int index = 0; index < schemas.getLength(); index++) {
            Element schema = (Element) schemas.item(index);
            NodeList imports = schema.getElementsByTagNameNS(XMLConstants.W3C_XML_SCHEMA_NS_URI, "import");
            for (int importedIndex = 0; importedIndex < imports.getLength(); importedIndex++) {
                Element imported = (Element) imports.item(importedIndex);
                String namespace = imported.getAttribute("namespace");
                String location = imported.getAttribute("schemaLocation");
                if (documents.containsKey(namespace) || location.isEmpty()) {
                    continue;
                }
                URI address = new URL(wsdlAddress.toURL(), location).toURI();
                HttpRequest request = HttpRequest.newBuilder(address).GET().build();
                HttpResponse<byte[]> response = this.send(request);
                assertEquals("Imported XSD must be published", 200, response.statusCode());
                documents.put(namespace, new String(response.body(), StandardCharsets.UTF_8));
                identifiers.put(namespace, address.toString());
            }
        }

        // WSDL 的内嵌 import 没有网络地址，由 JAXP 标准离线解析器按命名空间提供文档。
        DOMImplementationRegistry registry = DOMImplementationRegistry.newInstance();
        DOMImplementationLS implementation = (DOMImplementationLS) registry.getDOMImplementation("LS");
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setResourceResolver((type, namespace, publicId, systemId, baseUri) -> {
            String document = documents.get(namespace);
            if (document == null) {
                return null;
            }
            LSInput input = implementation.createLSInput();
            input.setPublicId(publicId);
            input.setSystemId(identifiers.get(namespace));
            input.setBaseURI(baseUri);
            input.setStringData(document);
            return input;
        });
        return factory.newSchema(sources);
    }

    private Document schemaDocument(Element schema) throws Exception {
        // 拷贝一个 schema 时保留 WSDL 祖先声明的前缀，防止属性值中的 QName 丢失命名空间。
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().newDocument();
        Element root = (Element) document.importNode(schema, true);
        document.appendChild(root);
        for (Node ancestor = schema; ancestor instanceof Element; ancestor = ancestor.getParentNode()) {
            NamedNodeMap attributes = ancestor.getAttributes();
            for (int index = 0; index < attributes.getLength(); index++) {
                Node attribute = attributes.item(index);
                String namespace = attribute.getNamespaceURI();
                if (StringUtils.equals(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, namespace)) {
                    boolean declared = root.hasAttributeNS(namespace, attribute.getLocalName());
                    if (!declared) {
                        root.setAttributeNS(namespace, attribute.getNodeName(), attribute.getNodeValue());
                    }
                }
            }
        }
        return document;
    }

    private List<QName> elementNames(Element parent) {
        List<QName> names = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element) {
                String namespace = element.getNamespaceURI();
                if (namespace == null) {
                    namespace = "";
                }
                names.add(new QName(namespace, element.getLocalName()));
            }
        }
        return names;
    }

    private byte[] envelope(String attributes, String buyerPrefix, String buyerValue) {
        String envelope = """
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                               xmlns:service="urn:hasor:meteor:soap:MetXmlOrderService"
                               xmlns:types="urn:meteor:xml:types"
                               xmlns:buyer="urn:meteor:xml:buyer"
                               xmlns:delivery="urn:meteor:xml:delivery"
                               xmlns:meta="urn:meteor:xml:attributes"
                               xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <soap:Body>
                        <service:echo>
                            <service:order %s>
                                <%s:name>%s</%s:name>
                                <delivery:name>Bob</delivery:name>
                                <types:lines><types:line>one</types:line><types:line>two</types:line></types:lines>
                                <types:status>ready-to-send</types:status>
                                <note xsi:nil="true"/>
                            </service:order>
                        </service:echo>
                    </soap:Body>
                </soap:Envelope>
                """;
        return envelope.formatted(attributes, buyerPrefix, buyerValue, buyerPrefix).getBytes(StandardCharsets.UTF_8);
    }
}

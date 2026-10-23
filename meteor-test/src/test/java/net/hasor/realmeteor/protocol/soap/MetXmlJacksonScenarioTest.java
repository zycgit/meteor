/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.soap;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.soap.*;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import static org.junit.Assert.*;

public class MetXmlJacksonScenarioTest {
    @Test(timeout = 30000)
    public void objectPropertyUsesJsonDeserializeTargetInBothDirections() throws Exception {
        // Object 属性通过 Jackson 的 as 指定读取类型，无需 Meteor 类型注册。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(MetXmlJacksonAsService.class, request -> {
                        MetXmlJacksonUser user = (MetXmlJacksonUser) request.value;
                        MetXmlJacksonAsValue response = new MetXmlJacksonAsValue();
                        response.value = this.user(user.name + "-server", user.age + 1);
                        return response;
                    }) //
                    .aliasName("soap", "MetXmlJacksonAsService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<MetXmlJacksonAsService> subscription = client.publisher() //
                    .rsfService(MetXmlJacksonAsService.class) //
                    .aliasName("soap", "MetXmlJacksonAsService") //
                    .protocol("soap") //
                    .register();
            MetXmlJacksonAsService remote = client.getRsfClient(server.getListenAddress("soap")) //
                    .getRemote(subscription);

            // 服务端先将 Object 强转为 User 并处理，客户端按同一属性注解恢复实际 DTO。
            MetXmlJacksonAsValue input = new MetXmlJacksonAsValue();
            input.value = this.user("Alice", 30);
            MetXmlJacksonAsValue response = remote.echo(input);
            assertTrue(response.value instanceof MetXmlJacksonUser);
            MetXmlJacksonUser result = (MetXmlJacksonUser) response.value;
            assertEquals("Alice-server", result.name);
            assertEquals(31, result.age);
        }
    }

    @Test(timeout = 30000)
    public void objectPropertyUsesNativeJacksonSubtypeInformationInBothDirections() throws Exception {
        // Jackson 的 kind 判别属性选择声明的子类型，不把 Java 类名加入 SOAP 类型协议。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(MetXmlJacksonPolymorphicService.class, request -> {
                        MetXmlJacksonUser user = (MetXmlJacksonUser) request.value;
                        MetXmlJacksonPolymorphicValue response = new MetXmlJacksonPolymorphicValue();
                        response.value = this.user(user.name + "-server", user.age + 1);
                        return response;
                    }) //
                    .aliasName("soap", "MetXmlJacksonPolymorphicService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<MetXmlJacksonPolymorphicService> subscription = client.publisher() //
                    .rsfService(MetXmlJacksonPolymorphicService.class) //
                    .aliasName("soap", "MetXmlJacksonPolymorphicService") //
                    .protocol("soap") //
                    .register();
            MetXmlJacksonPolymorphicService remote = client.getRsfClient(server.getListenAddress("soap")) //
                    .getRemote(subscription);

            // 请求和响应的 Object 均由 Jackson 还原成 User，服务实际读取并修改业务字段。
            MetXmlJacksonPolymorphicValue input = new MetXmlJacksonPolymorphicValue();
            input.value = this.user("Alice", 30);
            MetXmlJacksonPolymorphicValue response = remote.echo(input);
            assertTrue(response.value instanceof MetXmlJacksonUser);
            MetXmlJacksonUser result = (MetXmlJacksonUser) response.value;
            assertEquals("Alice-server", result.name);
            assertEquals(31, result.age);
        }
    }

    @Test(timeout = 30000)
    public void nativeSubtypeDiscriminatorUsesTheDeclaredKindOnTheWire() throws Exception {
        // 原生 Jackson PROPERTY 判别符在 XML 中使用 kind 属性，不声明 Java 类型 QName。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            String alias = "MetXmlJacksonPolymorphicService";
            server.publisher() //
                    .rsfService(MetXmlJacksonPolymorphicService.class, request -> {
                        MetXmlJacksonUser user = (MetXmlJacksonUser) request.value;
                        MetXmlJacksonPolymorphicValue response = new MetXmlJacksonPolymorphicValue();
                        response.value = this.user(user.name + "-server", user.age + 1);
                        return response;
                    }) //
                    .aliasName("soap", alias) //
                    .protocol("soap") //
                    .register();
            Schema schema = this.schema(this.endpoint(server, alias, "?xsd=types.xsd"));
            String namespace = "urn:hasor:meteor:soap:" + alias;
            String payload = "<request><payload kind=\"user\"><name>Alice</name><age>30</age></payload></request>";
            byte[] input = this.envelope(namespace, payload);
            Node operation = this.parse(input).getElementsByTagNameNS(namespace, "echo").item(0);
            schema.newValidator().validate(new DOMSource(operation));

            // 外部客户端的业务判别信息足以还原 User，响应保持同一判别约定和开放 XSD。
            HttpResponse<byte[]> response = this.send(this.invocation(server, alias, namespace, input));
            assertEquals(200, response.statusCode());
            Document output = this.parse(response.body());
            Node wrapper = output.getElementsByTagNameNS(namespace, "echoResponse").item(0);
            assertNotNull(wrapper);
            schema.newValidator().validate(new DOMSource(wrapper));
            Element result = (Element) output.getElementsByTagName("payload").item(0);
            assertNotNull(result);
            assertEquals("user", result.getAttribute("kind"));
            assertFalse(result.hasAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "type"));
            assertEquals("Alice-server", result.getElementsByTagName("name").item(0).getTextContent());
            assertEquals("31", result.getElementsByTagName("age").item(0).getTextContent());
        }
    }

    @Test(timeout = 30000)
    public void configuredModuleSerializersAndDeserializersRunAtBothEndpoints() throws Exception {
        // 每个上下文提供独立 XmlMapper 和用户 Module，计数只观察实际读写器执行。
        AtomicInteger serverWrites = new AtomicInteger();
        AtomicInteger serverReads = new AtomicInteger();
        AtomicInteger clientWrites = new AtomicInteger();
        AtomicInteger clientReads = new AtomicInteger();
        XmlMapper serverMapper = this.tokenMapper(serverWrites, serverReads);
        XmlMapper clientMapper = this.tokenMapper(clientWrites, clientReads);
        MetConfiguration incoming = this.configuration(true);
        incoming.addExtension(context -> new MetXmlJacksonExtension(serverMapper));
        MetConfiguration outgoing = this.configuration(false);
        outgoing.addExtension(context -> new MetXmlJacksonExtension(clientMapper));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(MetXmlJacksonTokenService.class, request -> {
                        return new MetXmlJacksonToken(request.value() + "-server");
                    }) //
                    .aliasName("soap", "MetXmlJacksonTokenService") //
                    .protocol("soap") //
                    .register();

            // 自定义序列化形状预先声明为 anyType，不根据首个 Token 的内容推导契约。
            URI wsdlAddress = this.endpoint(server, "MetXmlJacksonTokenService", "?wsdl");
            byte[] initialWsdl = this.download(wsdlAddress);
            QName anyType = new QName(XMLConstants.W3C_XML_SCHEMA_NS_URI, "anyType");
            assertEquals(anyType, this.schemaElementType(this.parse(initialWsdl), "request"));
            MetBindInfo<MetXmlJacksonTokenService> subscription = client.publisher() //
                    .rsfService(MetXmlJacksonTokenService.class) //
                    .aliasName("soap", "MetXmlJacksonTokenService") //
                    .protocol("soap") //
                    .register();
            MetXmlJacksonTokenService remote = client.getRsfClient(server.getListenAddress("soap")) //
                    .getRemote(subscription);

            // 读写器约定 wireValue 字段，无法用默认 bean 映射冒充；服务端仍直接处理 Token。
            MetXmlJacksonToken response = remote.echo(new MetXmlJacksonToken("alpha"));
            assertEquals("alpha-server", response.value());
            assertTrue("Client serializer must write the request", clientWrites.get() > 0);
            assertTrue("Server deserializer must read the request", serverReads.get() > 0);
            assertTrue("Server serializer must write the response", serverWrites.get() > 0);
            assertTrue("Client deserializer must read the response", clientReads.get() > 0);
            assertArrayEquals(initialWsdl, this.download(wsdlAddress));
        }
    }

    @Test(timeout = 30000)
    public void immutableDtoUsesJsonCreatorInBothDirections() throws Exception {
        // DTO 没有无参构造器或 setter，构造参数由原生 JsonCreator/JsonProperty 绑定。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(MetXmlJacksonImmutableService.class, request -> {
                        return new MetXmlJacksonImmutableUser(request.name() + "-server", request.age() + 1);
                    }) //
                    .aliasName("soap", "MetXmlJacksonImmutableService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<MetXmlJacksonImmutableService> subscription = client.publisher() //
                    .rsfService(MetXmlJacksonImmutableService.class) //
                    .aliasName("soap", "MetXmlJacksonImmutableService") //
                    .protocol("soap") //
                    .register();
            MetXmlJacksonImmutableService remote = client.getRsfClient(server.getListenAddress("soap")) //
                    .getRemote(subscription);

            // 两端均需成功调用构造器，服务实际读取不可变对象并生成新的返回值。
            MetXmlJacksonImmutableUser result = remote.echo(new MetXmlJacksonImmutableUser("Alice", 30));
            assertEquals("Alice-server", result.name());
            assertEquals(31, result.age());
        }
    }

    @Test(timeout = 30000)
    public void nativePropertyNamesAndNullInclusionAppearOnTheWire() throws Exception {
        // JsonProperty 决定 XML 名称，JsonInclude 决定空值是否实际写到报文中。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            String alias = "MetXmlJacksonNamedService";
            server.publisher() //
                    .rsfService(MetXmlJacksonNamedService.class, request -> {
                        MetXmlJacksonNamedValue response = new MetXmlJacksonNamedValue();
                        response.name = request.name + "-server";
                        return response;
                    }) //
                    .aliasName("soap", alias) //
                    .protocol("soap") //
                    .register();
            Schema schema = this.schema(this.endpoint(server, alias, "?xsd=types.xsd"));
            String namespace = "urn:hasor:meteor:soap:" + alias;
            String payload = "<request><display_name>Alice</display_name></request>";
            byte[] input = this.envelope(namespace, payload);
            Node operation = this.parse(input).getElementsByTagNameNS(namespace, "echo").item(0);
            schema.newValidator().validate(new DOMSource(operation));

            // 原始 HTTP 双向验证字段名，并区分 NON_NULL 省略与 ALWAYS 保留的空值元素。
            HttpResponse<byte[]> response = this.send(this.invocation(server, alias, namespace, input));
            assertEquals(200, response.statusCode());
            Document output = this.parse(response.body());
            Node wrapper = output.getElementsByTagNameNS(namespace, "echoResponse").item(0);
            assertNotNull(wrapper);
            schema.newValidator().validate(new DOMSource(wrapper));
            Element result = (Element) output.getElementsByTagName("result").item(0);
            assertNotNull(result);
            assertEquals("Alice-server", result.getElementsByTagName("display_name").item(0).getTextContent());
            assertEquals(0, result.getElementsByTagName("name").getLength());
            assertEquals(0, result.getElementsByTagName("memo").getLength());
            assertEquals(1, result.getElementsByTagName("retained").getLength());
        }
    }

    @Test(timeout = 30000)
    public void customSerdePreservesSoapNamespacesAndNestedAttributeGroup() throws Exception {
        // 局部 Token 使用自定义读写器，其余 DTO 保留 SOAP QName、order 与属性包装语义。
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        XmlMapper mapper = this.tokenMapper(writes, reads);
        MetConfiguration configuration = this.configuration(true);
        configuration.addExtension(context -> new MetXmlJacksonExtension(mapper));
        try (MetContext server = configuration.buildContext()) {
            server.start();
            String alias = "MetXmlJacksonMixedService";
            server.publisher() //
                    .rsfService(MetXmlJacksonMixedService.class, request -> {
                        MetXmlJacksonMixedValue response = new MetXmlJacksonMixedValue();
                        response.settings.version = request.settings.version + "-server";
                        response.left = request.left + "-server";
                        response.right = request.right;
                        response.details.token = new MetXmlJacksonToken(request.details.token.value() + "-server");
                        return response;
                    }) //
                    .aliasName("soap", alias) //
                    .protocol("soap") //
                    .register();
            Schema schema = this.schema(this.endpoint(server, alias, "?xsd=types.xsd"));
            String namespace = "urn:hasor:meteor:soap:" + alias;
            String payload = """
                    <request xmlns:l="urn:meteor:jackson:left" xmlns:r="urn:meteor:jackson:right"
                             xmlns:p="urn:meteor:jackson:payload">
                        <settings version="1.0"/>
                        <l:name>left</l:name><r:name>right</r:name>
                        <p:details><p:payload><wireValue>token:alpha</wireValue></p:payload></p:details>
                    </request>
                    """;
            byte[] input = this.envelope(namespace, payload);
            Node operation = this.parse(input).getElementsByTagNameNS(namespace, "echo").item(0);
            schema.newValidator().validate(new DOMSource(operation));

            // 服务按自定义 Token 解码并修改数据，响应仍符合发布的 SOAP 契约。
            HttpResponse<byte[]> response = this.send(this.invocation(server, alias, namespace, input));
            assertEquals(200, response.statusCode());
            Document output = this.parse(response.body());
            Node wrapper = output.getElementsByTagNameNS(namespace, "echoResponse").item(0);
            assertNotNull(wrapper);
            schema.newValidator().validate(new DOMSource(wrapper));
            Element result = (Element) output.getElementsByTagName("result").item(0);
            assertNotNull(result);
            List<QName> expected = new ArrayList<>();
            // settings、两个同名元素和 details 的先后对应 Jackson 属性顺序。
            expected.add(new QName("", "settings"));
            expected.add(new QName("urn:meteor:jackson:left", "name"));
            expected.add(new QName("urn:meteor:jackson:right", "name"));
            expected.add(new QName("urn:meteor:jackson:payload", "details"));
            assertEquals(expected, this.elementNames(result));
            Element settings = (Element) result.getElementsByTagName("settings").item(0);
            assertEquals("1.0-server", settings.getAttribute("version"));
            assertFalse(result.hasAttribute("version"));
            assertEquals("left-server", result.getElementsByTagNameNS("urn:meteor:jackson:left", "name") //
                    .item(0).getTextContent());
            assertEquals("right", result.getElementsByTagNameNS("urn:meteor:jackson:right", "name") //
                    .item(0).getTextContent());
            Element details = (Element) result.getElementsByTagNameNS("urn:meteor:jackson:payload", "details").item(0);
            Element token = (Element) details.getElementsByTagNameNS("urn:meteor:jackson:payload", "payload").item(0);
            assertSame(details, token.getParentNode());
            assertEquals("token:alpha-server", token.getElementsByTagName("wireValue").item(0).getTextContent());
            assertTrue(reads.get() > 0);
            assertTrue(writes.get() > 0);
        }
    }

    @Test(timeout = 30000)
    public void directObjectUsesConfiguredJacksonDeserializerAtBothEndpoints() throws Exception {
        // 全局 Object 反序列化器由应用 XmlMapper 提供，不新增 Meteor 的类型反查接口。
        AtomicInteger serverReads = new AtomicInteger();
        AtomicInteger clientReads = new AtomicInteger();
        XmlMapper serverMapper = this.objectMapper(serverReads);
        XmlMapper clientMapper = this.objectMapper(clientReads);
        MetConfiguration incoming = this.configuration(true);
        incoming.addExtension(context -> new MetXmlJacksonExtension(serverMapper));
        MetConfiguration outgoing = this.configuration(false);
        outgoing.addExtension(context -> new MetXmlJacksonExtension(clientMapper));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(MetXmlJacksonObjectService.class, request -> {
                        MetXmlJacksonToken token = (MetXmlJacksonToken) request;
                        return new MetXmlJacksonToken(token.value() + "-server");
                    }) //
                    .aliasName("soap", "MetXmlJacksonObjectService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<MetXmlJacksonObjectService> subscription = client.publisher() //
                    .rsfService(MetXmlJacksonObjectService.class) //
                    .aliasName("soap", "MetXmlJacksonObjectService") //
                    .protocol("soap") //
                    .register();
            MetXmlJacksonObjectService remote = client.getRsfClient(server.getListenAddress("soap")) //
                    .getRemote(subscription);

            // 服务端收到真正 Token，改写后的响应也由客户端的 Object 读写器恢复。
            Object response = remote.echo(new MetXmlJacksonToken("alpha"));
            assertTrue(response instanceof MetXmlJacksonToken);
            assertEquals("alpha-server", ((MetXmlJacksonToken) response).value());
            assertTrue("Server Object deserializer must be used", serverReads.get() > 0);
            assertTrue("Client Object deserializer must be used", clientReads.get() > 0);
        }
    }

    private MetConfiguration configuration(boolean bind) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("soap");
        if (bind) {
            configuration.connector("http") //
                    .bind("127.0.0.1", 0) //
                    .protocol("soap") //
                    .contextPath("/services") //
                    .option("soapVersion", "1.1");
        } else {
            configuration.connector("http") //
                    .protocol("soap") //
                    .contextPath("/services") //
                    .option("soapVersion", "1.1");
        }
        return configuration;
    }

    private MetXmlJacksonUser user(String name, int age) {
        MetXmlJacksonUser user = new MetXmlJacksonUser();
        user.name = name;
        user.age = age;
        return user;
    }

    private XmlMapper tokenMapper(AtomicInteger writes, AtomicInteger reads) {
        SimpleModule module = new SimpleModule();
        module.addSerializer(MetXmlJacksonToken.class, new TokenSerializer(writes));
        module.addDeserializer(MetXmlJacksonToken.class, new TokenDeserializer(reads));
        XmlMapper mapper = new XmlMapper();
        mapper.registerModule(module);
        return mapper;
    }

    private XmlMapper objectMapper(AtomicInteger reads) {
        SimpleModule module = new SimpleModule();
        module.addSerializer(MetXmlJacksonToken.class, new TokenSerializer(new AtomicInteger()));
        module.addDeserializer(Object.class, new ObjectTokenDeserializer(reads));
        XmlMapper mapper = new XmlMapper();
        mapper.registerModule(module);
        return mapper;
    }

    private URI endpoint(MetContext context, String alias, String suffix) {
        int port = context.getListenAddress("soap").getPort();
        return URI.create("http://127.0.0.1:" + port + "/services/" + alias + suffix);
    }

    private byte[] envelope(String namespace, String payload) {
        String xml = """
                <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                    <soap:Body><m:echo xmlns:m="%s">%s</m:echo></soap:Body>
                </soap:Envelope>
                """.formatted(namespace, payload);
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private HttpRequest invocation(MetContext context, String alias, String namespace, byte[] input) {
        return HttpRequest.newBuilder(this.endpoint(context, alias, "")) //
                .header("Content-Type", "text/xml; charset=utf-8") //
                .header("SOAPAction", "\"" + namespace + "/echo\"") //
                .POST(HttpRequest.BodyPublishers.ofByteArray(input)) //
                .build();
    }

    private byte[] download(URI address) throws Exception {
        HttpResponse<byte[]> response = this.send(HttpRequest.newBuilder(address).GET().build());
        assertEquals(200, response.statusCode());
        return response.body();
    }

    private HttpResponse<byte[]> send(HttpRequest request) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request, //
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

    private Schema schema(URI address) throws Exception {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "http");
        return factory.newSchema(address.toURL());
    }

    private QName schemaElementType(Document wsdl, String name) {
        NodeList elements = wsdl.getElementsByTagNameNS(XMLConstants.W3C_XML_SCHEMA_NS_URI, "element");
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            if (!StringUtils.equals(name, element.getAttribute("name"))) {
                continue;
            }
            String lexical = element.getAttribute("type");
            int separator = lexical.indexOf(':');
            String prefix = separator < 0 ? null : lexical.substring(0, separator);
            return new QName(element.lookupNamespaceURI(prefix), lexical.substring(separator + 1));
        }
        return null;
    }

    private List<QName> elementNames(Element parent) {
        List<QName> result = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element) {
                String namespace = element.getNamespaceURI();
                result.add(new QName(namespace == null ? "" : namespace, element.getLocalName()));
            }
        }
        return result;
    }

    private static MetXmlJacksonToken readToken(JsonParser parser) throws IOException {
        JsonNode value = parser.getCodec().readTree(parser);
        JsonNode wire = value.get("wireValue");
        if (wire == null || !wire.isTextual() || !wire.asText().startsWith("token:")) {
            throw new IOException("Expected the custom token wireValue field");
        }
        return new MetXmlJacksonToken(wire.asText().substring("token:".length()));
    }

    private static class TokenSerializer extends JsonSerializer<MetXmlJacksonToken> {
        private final AtomicInteger writes;

        private TokenSerializer(AtomicInteger writes) {
            this.writes = writes;
        }

        @Override
        public void serialize(MetXmlJacksonToken value, JsonGenerator generator, SerializerProvider provider) //
                throws IOException {
            this.writes.incrementAndGet();
            generator.writeStartObject();
            generator.writeStringField("wireValue", "token:" + value.value());
            generator.writeEndObject();
        }
    }

    private static class TokenDeserializer extends JsonDeserializer<MetXmlJacksonToken> {
        private final AtomicInteger reads;

        private TokenDeserializer(AtomicInteger reads) {
            this.reads = reads;
        }

        @Override
        public MetXmlJacksonToken deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            MetXmlJacksonToken value = readToken(parser);
            this.reads.incrementAndGet();
            return value;
        }
    }

    private static class ObjectTokenDeserializer extends JsonDeserializer<Object> {
        private final AtomicInteger reads;

        private ObjectTokenDeserializer(AtomicInteger reads) {
            this.reads = reads;
        }

        @Override
        public Object deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            MetXmlJacksonToken value = readToken(parser);
            this.reads.incrementAndGet();
            return value;
        }
    }
}

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.soap.MetXmlDeclaredTypesService;
import net.hasor.example.protocol.soap.MetXmlDeclaredTypesServiceImpl;
import net.hasor.example.protocol.soap.MetXmlDeclaredUser;
import net.hasor.example.protocol.soap.MetXmlDeclaredUserService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.soap.definition.SoapWsdlResource;
import net.hasor.meteor.test.nxn.NxnRpc;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Timeout;

@NxnRpc(name = "soap", version = "1.1")
public class MetXmlDeclaredTypesScenarioTest {
    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void publishedContractDoesNotDependOnRuntimeMapContents() throws Exception {
        // 接口声明完整类型图，两端通过公开入口注册相同服务。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(MetXmlDeclaredTypesService.class, new MetXmlDeclaredTypesServiceImpl()) //
                    .aliasName("soap", "MetXmlDeclaredTypesService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<MetXmlDeclaredTypesService> subscription = client.publisher() //
                    .rsfService(MetXmlDeclaredTypesService.class) //
                    .aliasName("soap", "MetXmlDeclaredTypesService") //
                    .protocol("soap") //
                    .register();

            // 尚无请求时，线上契约已经包含 DTO，标准校验器也能加载全部 XSD。
            URI wsdlAddress = this.endpoint(server, "?wsdl");
            URI xsdAddress = this.endpoint(server, "?xsd=types.xsd");
            byte[] initialWsdl = this.get(wsdlAddress);
            byte[] initialXsd = this.get(xsdAddress);
            assertNotNull(this.complexType(this.parse(initialWsdl), "DeclaredUser"));
            assertNotNull(this.schema(xsdAddress));
            MetXmlDeclaredTypesService remote = client.getRsfClient(server.getListenAddress("soap")) //
                    .getRemote(subscription);

            // 空 Map、有 DTO 的 Map 和开放结构只改变报文，不改变先前发布的类型契约。
            assertTrue(remote.users(new LinkedHashMap<>()).isEmpty());
            assertEquals(1, remote.users(Map.of("alice", this.user("u-1", "Alice"))).size());
            assertEquals(1, remote.users(Map.of("bob", this.user("u-2", "Bob"))).size());
            byte[] xml = "<value><unknown>content</unknown></value>".getBytes(StandardCharsets.UTF_8);
            Element dynamic = this.parse(xml).getDocumentElement();
            Map<String, Object> open = new LinkedHashMap<>();
            open.put("counter", 7);
            open.put("tree", dynamic);
            assertEquals(2, remote.values(open).size());
            assertArrayEquals(initialWsdl, this.get(wsdlAddress));
            assertArrayEquals(initialXsd, this.get(xsdAddress));
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void typedMapUsesDeclaredDtoAttributesAndFieldsInBothDirections() throws Exception {
        // 服务实现会读取 User 并改写 id，不能用动态 Map 冒充已声明的 DTO。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(MetXmlDeclaredTypesService.class, new MetXmlDeclaredTypesServiceImpl()) //
                    .aliasName("soap", "MetXmlDeclaredTypesService") //
                    .protocol("soap") //
                    .register();

            // 客户端从服务端线上 WSDL 绑定，覆盖契约解析和实际 Java 类型绑定。
            SoapWsdlResource resource = new SoapWsdlResource(this.endpoint(server, "?wsdl").toURL());
            MetBindInfo<MetXmlDeclaredTypesService> subscription = client.publisher() //
                    .rsfService(MetXmlDeclaredTypesService.class) //
                    .bindResource("soap", resource) //
                    .aliasName("soap", "MetXmlDeclaredTypesService") //
                    .protocol("soap") //
                    .register();
            MetXmlDeclaredTypesService remote = client.getRsfClient(server.getListenAddress("soap")) //
                    .getRemote(subscription);

            // key 动态命名元素，value 的属性和子元素由泛型声明中的 User 决定。
            Map<String, MetXmlDeclaredUser> result = remote.users(Map.of("administrator", this.user("u-1", "Alice")));
            assertEquals(Set.of("administrator"), result.keySet());
            MetXmlDeclaredUser user = result.get("administrator");
            assertEquals(MetXmlDeclaredUser.class, user.getClass());
            assertEquals("u-1-server", user.id);
            assertEquals("Alice", user.name);
            assertEquals(30, user.age);
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void rawTypedMapKeepsTheKeyAsTheDtoElementName() throws Exception {
        // 线上固定 XSD 包含 User，而请求中的 administrator 由 Map key 决定。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(MetXmlDeclaredTypesService.class, new MetXmlDeclaredTypesServiceImpl()) //
                    .aliasName("soap", "MetXmlDeclaredTypesService") //
                    .protocol("soap") //
                    .register();
            Schema schema = this.schema(this.endpoint(server, "?xsd=types.xsd"));
            String namespace = MetXmlDeclaredTypesService.NAMESPACE;
            String envelope = """
                    <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                   xmlns:user="urn:meteor:soap:declared-types">
                        <soap:Body>
                            <m:users xmlns:m="%s">
                                <request>
                                    <administrator xsi:type="user:DeclaredUser" id="u-1">
                                        <name>Alice</name><age>30</age>
                                    </administrator>
                                </request>
                            </m:users>
                        </soap:Body>
                    </soap:Envelope>
                    """.formatted(namespace);
            byte[] input = envelope.getBytes(StandardCharsets.UTF_8);
            Node operation = this.parse(input).getElementsByTagNameNS(namespace, "users").item(0);
            schema.newValidator().validate(new DOMSource(operation));

            // 原始 HTTP 请求按接口声明的泛型绑定到 User，服务实现修改后返回 DTO Map。
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(server, "")) //
                    .header("Content-Type", "text/xml; charset=utf-8") //
                    .header("SOAPAction", "\"" + namespace + "/users\"") //
                    .POST(HttpRequest.BodyPublishers.ofByteArray(input)) //
                    .build();
            HttpResponse<byte[]> response = this.send(request);
            assertEquals(200, response.statusCode());
            Document output = this.parse(response.body());
            Node wrapper = output.getElementsByTagNameNS(namespace, "usersResponse").item(0);
            assertNotNull(wrapper);
            schema.newValidator().validate(new DOMSource(wrapper));

            // key 直接成为 result 下的元素名，DTO 属性和子元素保持自身映射。
            Element result = (Element) output.getElementsByTagName("result").item(0);
            assertNotNull(result);
            NodeList entries = result.getElementsByTagName("administrator");
            assertEquals(1, entries.getLength());
            Element administrator = (Element) entries.item(0);
            assertSame(result, administrator.getParentNode());
            assertEquals("u-1-server", administrator.getAttribute("id"));
            assertEquals("Alice", administrator.getElementsByTagName("name").item(0).getTextContent());
            assertEquals("30", administrator.getElementsByTagName("age").item(0).getTextContent());
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void objectListPreservesMixedScalarTypesAcrossTheProxy() throws Exception {
        // List 的条目类型声明为 Object，双端按 anyType 的标准类型信息读写。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(MetXmlDeclaredTypesService.class, new MetXmlDeclaredTypesServiceImpl()) //
                    .aliasName("soap", "MetXmlDeclaredTypesService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<MetXmlDeclaredTypesService> subscription = client.publisher() //
                    .rsfService(MetXmlDeclaredTypesService.class) //
                    .aliasName("soap", "MetXmlDeclaredTypesService") //
                    .protocol("soap") //
                    .register();
            MetXmlDeclaredTypesService remote = client.getRsfClient(server.getListenAddress("soap")) //
                    .getRemote(subscription);

            // 顺序对应回读后的条目位置，数字 7 和字符串 "7" 必须可区分。
            List<Object> values = new ArrayList<>();
            values.add(7);
            values.add(true);
            values.add("7");
            values.add(null);
            List<Object> result = remote.list(values);
            assertEquals(values, result);
            assertEquals(Integer.class, result.get(0).getClass());
            assertEquals(Boolean.class, result.get(1).getClass());
            assertEquals(String.class, result.get(2).getClass());
            assertNull(result.get(3));
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void objectSetPreservesMixedScalarTypesAcrossTheProxy() throws Exception {
        // Set 和 List 共用重复元素的 XML 表达，Java 回读仍遵循声明的容器类型。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(MetXmlDeclaredTypesService.class, new MetXmlDeclaredTypesServiceImpl()) //
                    .aliasName("soap", "MetXmlDeclaredTypesService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<MetXmlDeclaredTypesService> subscription = client.publisher() //
                    .rsfService(MetXmlDeclaredTypesService.class) //
                    .aliasName("soap", "MetXmlDeclaredTypesService") //
                    .protocol("soap") //
                    .register();
            MetXmlDeclaredTypesService remote = client.getRsfClient(server.getListenAddress("soap")) //
                    .getRemote(subscription);

            // 类型不同但文本相同的两个值不会在 Set 解码时被错误合并。
            Set<Object> values = new LinkedHashSet<>();
            values.add(7);
            values.add(true);
            values.add("7");
            Set<Object> result = remote.set(values);
            assertEquals(3, result.size());
            assertEquals(values, result);
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void nestedGenericMapsAndListsPreserveEveryContainerBoundary() throws Exception {
        // Map<String,List<Map<String,User>>> 的所有层次均在发布时确定。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(MetXmlDeclaredTypesService.class, new MetXmlDeclaredTypesServiceImpl()) //
                    .aliasName("soap", "MetXmlDeclaredTypesService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<MetXmlDeclaredTypesService> subscription = client.publisher() //
                    .rsfService(MetXmlDeclaredTypesService.class) //
                    .aliasName("soap", "MetXmlDeclaredTypesService") //
                    .protocol("soap") //
                    .register();
            MetXmlDeclaredTypesService remote = client.getRsfClient(server.getListenAddress("soap")) //
                    .getRemote(subscription);

            // 列表位置先有用户 Map、再有空 Map，另一团队的空列表也必须保持独立容器。
            List<Map<String, MetXmlDeclaredUser>> groups = new ArrayList<>();
            groups.add(Map.of("leader", this.user("u-1", "Alice")));
            groups.add(new LinkedHashMap<>());
            Map<String, List<Map<String, MetXmlDeclaredUser>>> request = new LinkedHashMap<>();
            request.put("engineering", groups);
            request.put("sales", new ArrayList<>());
            Map<String, List<Map<String, MetXmlDeclaredUser>>> result = remote.nested(request);
            assertEquals(Set.of("engineering", "sales"), result.keySet());
            assertTrue(result.get("sales").isEmpty());
            List<Map<String, MetXmlDeclaredUser>> receivedGroups = result.get("engineering");
            assertEquals(2, receivedGroups.size());
            assertTrue(receivedGroups.get(1).isEmpty());
            MetXmlDeclaredUser user = receivedGroups.get(0).get("leader");
            assertEquals("u-1", user.id);
            assertEquals("Alice", user.name);
            assertEquals(30, user.age);
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void inheritedGenericMethodResolvesTheConcreteDtoAtBothEndpoints() throws Exception {
        // 子接口将父接口的 T 绑定为 User，服务不需要重新声明继承的方法。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(MetXmlDeclaredUserService.class, request -> request) //
                    .aliasName("soap", "MetXmlDeclaredUserService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<MetXmlDeclaredUserService> subscription = client.publisher() //
                    .rsfService(MetXmlDeclaredUserService.class) //
                    .aliasName("soap", "MetXmlDeclaredUserService") //
                    .protocol("soap") //
                    .register();

            // 调用前下载的 XSD 已包含 User，证明继承的 T 没有退化成 Object。
            int port = server.getListenAddress("soap").getPort();
            URI wsdlAddress = URI.create("http://127.0.0.1:" + port + "/services/MetXmlDeclaredUserService?wsdl");
            Document wsdl = this.parse(this.get(wsdlAddress));
            Element userType = this.complexType(wsdl, "DeclaredUser");
            assertNotNull(userType);
            NodeList members = userType.getElementsByTagNameNS(XMLConstants.W3C_XML_SCHEMA_NS_URI, "element");
            Set<String> fieldNames = new LinkedHashSet<>();
            for (int index = 0; index < members.getLength(); index++) {
                fieldNames.add(((Element) members.item(index)).getAttribute("name"));
            }
            assertEquals(Set.of("name", "age"), fieldNames);
            MetXmlDeclaredUserService remote = client.getRsfClient(server.getListenAddress("soap")) //
                    .getRemote(subscription);

            // 泛型中的集合和 DTO 同时参与请求、响应的真实 SOAP 编解码。
            Map<String, List<MetXmlDeclaredUser>> request = Map.of("team", List.of(this.user("u-1", "Alice")));
            Map<String, List<MetXmlDeclaredUser>> result = remote.echo(request);
            MetXmlDeclaredUser user = result.get("team").get(0);
            assertEquals(MetXmlDeclaredUser.class, user.getClass());
            assertEquals("u-1", user.id);
            assertEquals("Alice", user.name);
            assertEquals(30, user.age);
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void rawAnyTypeIntegerUsesStandardTypeInformationAndPublishedSchema() throws Exception {
        // Object 参数与返回值是 anyType，外部客户端无需使用 Meteor 的编码器。
        MetConfiguration configuration = this.configuration(true);
        AtomicReference<Object> decoded = new AtomicReference<>();
        MetXmlDeclaredTypesServiceImpl service = new MetXmlDeclaredTypesServiceImpl() {
            @Override
            public Object any(Object request) {
                decoded.set(request);
                return request;
            }
        };
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher() //
                    .rsfService(MetXmlDeclaredTypesService.class, service) //
                    .aliasName("soap", "MetXmlDeclaredTypesService") //
                    .protocol("soap") //
                    .register();
            Schema schema = this.schema(this.endpoint(server, "?xsd=types.xsd"));
            String namespace = MetXmlDeclaredTypesService.NAMESPACE;
            String envelope = """
                    <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"
                                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                   xmlns:xs="http://www.w3.org/2001/XMLSchema">
                        <soap:Body>
                            <m:any xmlns:m="%s"><request xsi:type="xs:int">7</request></m:any>
                        </soap:Body>
                    </soap:Envelope>
                    """.formatted(namespace);
            byte[] input = envelope.getBytes(StandardCharsets.UTF_8);
            Node operation = this.parse(input).getElementsByTagNameNS(namespace, "any").item(0);
            schema.newValidator().validate(new DOMSource(operation));

            // 原始 HTTP 请求按 xs:int 得到 Integer，响应也由同一固定 XSD 校验。
            HttpRequest request = HttpRequest.newBuilder(this.endpoint(server, "")) //
                    .header("Content-Type", "text/xml; charset=utf-8") //
                    .header("SOAPAction", "\"" + namespace + "/any\"") //
                    .POST(HttpRequest.BodyPublishers.ofByteArray(input)) //
                    .build();
            HttpResponse<byte[]> response = this.send(request);
            assertEquals(200, response.statusCode());
            assertEquals(7, decoded.get());
            assertEquals(Integer.class, decoded.get().getClass());
            Document output = this.parse(response.body());
            Node wrapper = output.getElementsByTagNameNS(namespace, "anyResponse").item(0);
            assertNotNull(wrapper);
            schema.newValidator().validate(new DOMSource(wrapper));
            Element result = (Element) output.getElementsByTagName("result").item(0);
            assertNotNull(result);
            assertEquals("7", result.getTextContent());
            assertEquals(new QName(XMLConstants.W3C_XML_SCHEMA_NS_URI, "int"), this.valueType(result));
        }
    }

    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void unknownAnyTypeStructurePreservesXmlAcrossTheProxy() throws Exception {
        // Object 中未知的复杂内容通过 DOM 表达，不要求双方存在同名 Java 类。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(MetXmlDeclaredTypesService.class, new MetXmlDeclaredTypesServiceImpl()) //
                    .aliasName("soap", "MetXmlDeclaredTypesService") //
                    .protocol("soap") //
                    .register();
            MetBindInfo<MetXmlDeclaredTypesService> subscription = client.publisher() //
                    .rsfService(MetXmlDeclaredTypesService.class) //
                    .aliasName("soap", "MetXmlDeclaredTypesService") //
                    .protocol("soap") //
                    .register();
            MetXmlDeclaredTypesService remote = client.getRsfClient(server.getListenAddress("soap")) //
                    .getRemote(subscription);

            // 保留属性、命名空间、重复子元素和混合文本，槽位外层名称由参数与返回声明决定。
            String childrenXml = "<p:item>first</p:item><p:item enabled=\"true\">second</p:item>";
            String xml = "<value xmlns:p=\"urn:vendor:values\" p:code=\"A\">" + childrenXml + "tail</value>";
            Element input = this.parse(xml.getBytes(StandardCharsets.UTF_8)).getDocumentElement();
            Object response = remote.any(input);
            assertTrue(response instanceof Element);
            Element result = (Element) response;
            assertEquals("A", result.getAttributeNS("urn:vendor:values", "code"));
            NodeList items = result.getElementsByTagNameNS("urn:vendor:values", "item");
            assertEquals(2, items.getLength());
            assertEquals("first", items.item(0).getTextContent());
            Element second = (Element) items.item(1);
            assertEquals("second", second.getTextContent());
            assertEquals("true", second.getAttribute("enabled"));
            assertEquals("firstsecondtail", result.getTextContent());
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

    private MetXmlDeclaredUser user(String id, String name) {
        MetXmlDeclaredUser user = new MetXmlDeclaredUser();
        user.id = id;
        user.name = name;
        user.age = 30;
        return user;
    }

    private URI endpoint(MetContext context, String suffix) {
        int port = context.getListenAddress("soap").getPort();
        return URI.create("http://127.0.0.1:" + port + "/services/MetXmlDeclaredTypesService" + suffix);
    }

    private byte[] get(URI address) throws Exception {
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

    private Element complexType(Document wsdl, String name) {
        NodeList types = wsdl.getElementsByTagNameNS(XMLConstants.W3C_XML_SCHEMA_NS_URI, "complexType");
        for (int index = 0; index < types.getLength(); index++) {
            Element type = (Element) types.item(index);
            if (StringUtils.equals(name, type.getAttribute("name"))) {
                return type;
            }
        }
        return null;
    }

    private QName valueType(Element element) {
        String lexical = element.getAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "type");
        int separator = lexical.indexOf(':');
        String prefix = separator < 0 ? null : lexical.substring(0, separator);
        String name = lexical.substring(separator + 1);
        return new QName(element.lookupNamespaceURI(prefix), name);
    }
}

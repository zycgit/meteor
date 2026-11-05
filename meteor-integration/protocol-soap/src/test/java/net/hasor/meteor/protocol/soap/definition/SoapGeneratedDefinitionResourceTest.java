/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.definition;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator;
import net.hasor.example.protocol.soap.CollectionService;
import net.hasor.example.protocol.soap.MessageEchoService;
import net.hasor.example.protocol.soap.SignalService;
import net.hasor.meteor.annotation.MetMessage;
import net.hasor.meteor.annotation.MetReturn;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlMapping;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import static org.junit.jupiter.api.Assertions.*;

public class SoapGeneratedDefinitionResourceTest {
    private static final URI ENDPOINT = URI.create("http://localhost:2181/services/GeneratedService");

    @Test
    public void generatedServiceAliasCannotStartWithADigit() {
        // 数字开头的名称虽可作为 URI 路径段，却不能作为 WSDL 的 XML NCName。
        ServiceDomain<NamedService> service = this.service(NamedService.class);
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());
        service.putAliasName("soap", "123");

        // 生成器必须在创建任何 WSDL 组件之前拒绝非法名称。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapGeneratedDefinitionResource(service, config, SoapXmlMapping.newMapper()));
        assertEquals("SOAP service name must be an XML local name: 123", failure.getMessage());
    }

    @Test
    public void generatedServiceAliasCannotStartWithAHyphen() {
        // 连字符只能出现在 XML NCName 的首字符之后。
        ServiceDomain<NamedService> service = this.service(NamedService.class);
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());
        service.putAliasName("soap", "-echo");

        // 不应返回表面成功、但外部客户端无法读取的 WSDL。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapGeneratedDefinitionResource(service, config, SoapXmlMapping.newMapper()));
        assertEquals("SOAP service name must be an XML local name: -echo", failure.getMessage());
    }

    @Test
    public void operationNamesDoNotDependOnJavaParameterNames() {
        // 准备使用业务形参名的 Java 接口，由生成器建立 SOAP 调用契约。
        ServiceDomain<NamedService> service = this.service(NamedService.class);
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());
        SoapGeneratedDefinitionResource resource = new SoapGeneratedDefinitionResource( //
                service, config, SoapXmlMapping.newMapper());

        // 包装元素有服务命名空间，形参和结果使用稳定的无命名空间名称。
        String namespace = "urn:hasor:meteor:soap:GeneratedService";
        assertEquals(namespace, resource.definition().targetNamespace());
        SoapOperation operation = resource.definition().operation("combine");
        assertEquals(new QName(namespace, "combine"), operation.inputElement());
        assertEquals(new QName(namespace, "combineResponse"), operation.outputElement());
        assertEquals(List.of(new QName("", "arg0"), new QName("", "arg1")), operation.parameterElements());
        assertEquals(new QName("", "return"), operation.resultElement());
        assertEquals(namespace + "/combine", operation.soapAction());
        assertFalse(operation.oneWay());
    }

    @Test
    public void attributeOnAServiceMethodHasNoParentElement() {
        // 服务返回方法不会把返回值写成 XML 属性，不能忽略此声明。
        ServiceDomain<AttributeResultService> service = this.service(AttributeResultService.class);
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapGeneratedDefinitionResource(service, config, SoapXmlMapping.newMapper()));
        assertTrue(failure.getMessage().contains("XML attribute has no parent element"));
        assertTrue(failure.getMessage().contains(AttributeResultService.class.getName() + "#echo"));
    }

    @Test
    public void collectionParameterCannotDeclareAPropertyWrapper() {
        // 参数本身是集合，也不能把 DTO 属性的包装配置误用于操作元素。
        ServiceDomain<WrappedParameterService> service = this.service(WrappedParameterService.class);
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());

        // 在生成契约时拒绝不支持的配置，不能仅保留条目类型而忽略包装。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapGeneratedDefinitionResource(service, config, SoapXmlMapping.newMapper()));
        assertEquals("SOAP element wrapping is only supported on DTO properties", failure.getMessage());
    }

    @Test
    public void collectionResultCannotDeclareAPropertyWrapper() {
        // 返回值是集合，也不能把 DTO 属性的包装配置误用于操作元素。
        ServiceDomain<WrappedResultService> service = this.service(WrappedResultService.class);
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());

        // 生成器不能仅保留集合类型而忽略方法注解上的包装声明。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapGeneratedDefinitionResource(service, config, SoapXmlMapping.newMapper()));
        assertEquals("SOAP element wrapping is only supported on DTO properties", failure.getMessage());
    }

    @Test
    public void oneWayMethodCannotDeclareAPropertyWrapper() {
        // one-way 方法没有输出，仍应校验方法注解的包装声明。
        ServiceDomain<WrappedOneWayService> service = this.service(WrappedOneWayService.class);
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());

        // 生成器不能因为不生成响应而静默跳过错误配置。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapGeneratedDefinitionResource(service, config, SoapXmlMapping.newMapper()));
        assertEquals("SOAP element wrapping is only supported on DTO properties", failure.getMessage());
    }

    @Test
    public void voidMethodCannotConfigureAPropertyWrapper() {
        // 普通 void 方法没有返回元素，仍应检查方法注解上的包装声明。
        ServiceDomain<WrappedVoidService> service = this.service(WrappedVoidService.class);
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());

        // 缺少返回值不能成为跳过配置校验的原因。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapGeneratedDefinitionResource(service, config, SoapXmlMapping.newMapper()));
        assertEquals("SOAP element wrapping is only supported on DTO properties", failure.getMessage());
    }

    @Test
    public void operationElementOptionsDoNotRequireAPropertyWrapper() {
        // 服务参数和返回值使用自己的元素约束，不声明集合属性包装。
        ServiceDomain<MappedElementService> service = this.service(MappedElementService.class);
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());
        SoapGeneratedDefinitionResource resource = new SoapGeneratedDefinitionResource( //
                service, config, SoapXmlMapping.newMapper());

        // 普通 namespace、required 和 nillable 配置仍能生成服务契约。
        SoapOperation operation = resource.definition().operation("echo");
        assertEquals(new QName("urn:test:parameters", "arg0"), operation.parameterElements().get(0));
        assertEquals(new QName("urn:test:results", "return"), operation.resultElement());
    }

    @Test
    public void renderedWsdlIncludesTheSchemaAndPublishedEndpoint() throws Exception {
        // 为普通 Java 接口生成描述，并选择带上下文路径的发布地址。
        ServiceDomain<NamedService> service = this.service(NamedService.class);
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());
        SoapGeneratedDefinitionResource resource = new SoapGeneratedDefinitionResource( //
                service, config, SoapXmlMapping.newMapper());

        // WSDL 使用当前发布地址，并内嵌类型定义，不再依赖外部 XSD。
        byte[] definition = resource.renderWsdl(ENDPOINT);
        String wsdl = new String(definition, StandardCharsets.UTF_8);
        assertTrue(wsdl.contains("location=\"" + ENDPOINT + "\""));
        assertFalse(wsdl.contains("schemaLocation="));
        assertTrue(wsdl.contains("style=\"document\""));
        assertTrue(wsdl.contains("use=\"literal\""));

        // 禁止加载外部文档，仍能独立编译内嵌 Schema 并校验业务请求。
        DocumentBuilderFactory documents = DocumentBuilderFactory.newInstance();
        documents.setNamespaceAware(true);
        documents.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        documents.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        documents.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        documents.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Document document = documents.newDocumentBuilder().parse(new ByteArrayInputStream(definition));
        String xsdNamespace = XMLConstants.W3C_XML_SCHEMA_NS_URI;
        assertEquals(0, document.getElementsByTagNameNS(xsdNamespace, "import").getLength());
        Element types = (Element) document.getElementsByTagNameNS("http://schemas.xmlsoap.org/wsdl/", "types").item(0);
        Element schema = (Element) types.getElementsByTagNameNS(xsdNamespace, "schema").item(0);
        assertEquals(resource.definition().targetNamespace(), schema.getAttribute("targetNamespace"));
        SchemaFactory schemas = SchemaFactory.newInstance(xsdNamespace);
        schemas.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        schemas.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Schema compiled = schemas.newSchema(new DOMSource(schema));
        String input = "<s:combine xmlns:s=\"" + resource.definition().targetNamespace() //
                + "\"><arg0>hello</arg0><arg1>2</arg1></s:combine>";
        compiled.newValidator().validate(new StreamSource(new StringReader(input)));
    }

    @Test
    public void renderingAnotherEndpointDoesNotMutateTheFirstDefinition() throws Exception {
        // 同一服务契约挂载两个端点，并先保存第一个端点的文档。
        ServiceDomain<NamedService> service = this.service(NamedService.class);
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());
        SoapGeneratedDefinitionResource resource = new SoapGeneratedDefinitionResource( //
                service, config, SoapXmlMapping.newMapper());
        byte[] firstWsdl = resource.renderWsdl(ENDPOINT);
        URI secondEndpoint = URI.create("http://localhost:2182/other/GeneratedService");

        // 渲染另一端点之后，再次获取第一个端点仍应得到相同内容。
        String secondWsdl = new String(resource.renderWsdl(secondEndpoint), StandardCharsets.UTF_8);
        assertTrue(secondWsdl.contains("location=\"" + secondEndpoint + "\""));
        assertArrayEquals(firstWsdl, resource.renderWsdl(ENDPOINT));
    }

    @Test
    public void methodMessageProducesAnInputOnlyOperation() throws Exception {
        // 方法上的消息声明要求生成 SOAP one-way 契约。
        ServiceDomain<SignalService> service = this.service(SignalService.class);
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());
        SoapGeneratedDefinitionResource resource = new SoapGeneratedDefinitionResource( //
                service, config, SoapXmlMapping.newMapper());

        // 定义和 WSDL 都不包含业务响应。
        SoapOperation operation = resource.definition().operation("signal");
        assertTrue(operation.oneWay());
        assertNull(operation.outputElement());
        assertNull(operation.resultElement());
        String wsdl = new String(resource.renderWsdl(ENDPOINT), StandardCharsets.UTF_8);
        assertFalse(wsdl.contains("signalResponse"));
        assertFalse(wsdl.contains("wsdl:output"));
    }

    @Test
    public void methodMessageCannotReturnABusinessValue() {
        // 方法声明消息调用，却同时要求返回业务值。
        ServiceDomain<MessageEchoService> service = this.service(MessageEchoService.class);
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());

        // 生成阶段直接拒绝无法表达的 one-way 返回值。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapGeneratedDefinitionResource(service, config, SoapXmlMapping.newMapper()));
        assertEquals("SOAP one-way method must return void: echo", failure.getMessage());
    }

    @Test
    public void serviceMessageFlagCannotHideANonVoidReturnType() {
        // 通过服务声明开启消息模式，接口方法仍有返回值。
        ServiceDomain<NamedService> service = this.service(NamedService.class);
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());
        service.setMessage(true);

        // 服务级消息模式与方法注解遵循相同的返回类型约束。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapGeneratedDefinitionResource(service, config, SoapXmlMapping.newMapper()));
        assertEquals("SOAP one-way method must return void: combine", failure.getMessage());
    }

    @Test
    public void overloadedOperationNamesAreRejected() {
        // Java 接口声明同名但参数类型不同的方法。
        ServiceDomain<OverloadedService> service = this.service(OverloadedService.class);
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());

        // SOAP operation 不能仅凭 Java 参数签名区分重载。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapGeneratedDefinitionResource(service, config, SoapXmlMapping.newMapper()));
        assertEquals("SOAP service does not support overloaded method: echo", failure.getMessage());
    }

    @Test
    public void responseWrapperCannotCollideWithAnotherOperation() {
        // echoResponse 同时被声明为方法名和 echo 的响应包装名。
        ServiceDomain<WrapperCollisionService> service = this.service(WrapperCollisionService.class);
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());

        // 重复的全局 XML element 必须在生成 Schema 之前被拒绝。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapGeneratedDefinitionResource(service, config, SoapXmlMapping.newMapper()));
        assertTrue(failure.getMessage().contains("same name: echoResponse"));
    }

    @Test
    public void objectParameterPublishesAnyType() throws Exception {
        // Object 声明本身表示开放内容，不依赖运行时值推断 DTO 结构。
        ServiceDomain<ObjectService> service = this.service(ObjectService.class);
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());
        SoapGeneratedDefinitionResource resource = new SoapGeneratedDefinitionResource( //
                service, config, SoapXmlMapping.newMapper());

        // 参数与返回值都使用标准 anyType，复杂内容可通过固定 XSD 验证。
        String input = "<s:echo xmlns:s=\"" + resource.definition().targetNamespace() + "\">" //
                + "<arg0 id=\"A\"><name>Alice</name></arg0></s:echo>";
        Schema schema = this.schema(resource.renderResource("types.xsd", ENDPOINT));
        schema.newValidator().validate(new StreamSource(new StringReader(input)));
        assertEquals(new QName(XMLConstants.W3C_XML_SCHEMA_NS_URI, "anyType"), //
                resource.definition().operation("echo").inputBody().parts().get(0).type());
    }

    @Test
    public void mapParameterPublishesADynamicElementContract() throws Exception {
        // Map 的键在调用时决定元素名，发布时不枚举可能的键。
        ServiceDomain<MapService> service = this.service(MapService.class);
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());
        SoapGeneratedDefinitionResource resource = new SoapGeneratedDefinitionResource( //
                service, config, SoapXmlMapping.newMapper());

        // 一个容器内接受不同名称的条目，输入和输出引用相同的 Map 类型。
        String input = "<s:echo xmlns:s=\"" + resource.definition().targetNamespace() + "\">" //
                + "<arg0><timeout>30</timeout><mode>fast</mode></arg0></s:echo>";
        Schema schema = this.schema(resource.renderResource("types.xsd", ENDPOINT));
        schema.newValidator().validate(new StreamSource(new StringReader(input)));
    }

    @Test
    public void streamParameterIsRejected() {
        // Java Stream 不能作为普通 SOAP document 参数发布。
        ServiceDomain<StreamService> service = this.service(StreamService.class);
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());

        // 生成器拒绝流对象，而不是暴露其实现属性。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> new SoapGeneratedDefinitionResource(service, config, SoapXmlMapping.newMapper()));
        assertTrue(failure.getMessage().contains("only concrete ordinary POJOs are supported"));
    }

    @Test
    public void collectionWithoutAnElementTypeUsesAnyTypeItems() throws Exception {
        // 原始 List 的缺省元素类型为 Object，与 List<Object> 使用同一开放内容规则。
        ServiceDomain<RawCollectionService> service = this.service(RawCollectionService.class);
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());
        SoapGeneratedDefinitionResource resource = new SoapGeneratedDefinitionResource( //
                service, config, SoapXmlMapping.newMapper());

        // 每个 item 都能保留自己的 XML 结构，不根据集合中的第一个值猜测类型。
        String input = "<s:echo xmlns:s=\"" + resource.definition().targetNamespace() + "\">" //
                + "<arg0><item>text</item><item><name>Alice</name></item></arg0></s:echo>";
        Schema schema = this.schema(resource.renderResource("types.xsd", ENDPOINT));
        schema.newValidator().validate(new StreamSource(new StringReader(input)));
    }

    @Test
    public void nestedCollectionsKeepTheirContainerLevels() throws Exception {
        // 外层 item 是内层集合的容器，内层 item 才承载 String 值。
        ServiceDomain<NestedCollectionService> service = this.service(NestedCollectionService.class);
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());
        SoapGeneratedDefinitionResource resource = new SoapGeneratedDefinitionResource( //
                service, config, SoapXmlMapping.newMapper());

        // 固定类型引用保留非空和空的内层集合，不进行递归平铺。
        String input = "<s:echo xmlns:s=\"" + resource.definition().targetNamespace() + "\">" //
                + "<arg0><item><item>A</item><item>B</item></item><item/></arg0></s:echo>";
        Schema schema = this.schema(resource.renderResource("types.xsd", ENDPOINT));
        schema.newValidator().validate(new StreamSource(new StringReader(input)));
    }

    @Test
    public void customDtoPropertyNamespacesAreRetained() throws Exception {
        // Jackson XML 字段的 namespace 与 Meteor 注解共用完整 QName 模型。
        ServiceDomain<NamespacedService> service = this.service(NamespacedService.class);
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());
        SoapGeneratedDefinitionResource resource = new SoapGeneratedDefinitionResource( //
                service, config, SoapXmlMapping.newMapper());

        // 外部命名空间生成独立 Schema，参数内容按实际 QName 验证。
        String input = "<Body><s:echo xmlns:s=\"urn:hasor:meteor:soap:GeneratedService\"" //
                + " xmlns:p=\"urn:test:property\"><arg0><p:value>hello</p:value></arg0></s:echo></Body>";
        DocumentBuilderFactory documents = DocumentBuilderFactory.newInstance();
        documents.setNamespaceAware(true);
        byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
        Document document = documents.newDocumentBuilder().parse(new ByteArrayInputStream(inputBytes));
        resource.validateBody(document.getDocumentElement(), resource.definition().operation("echo").inputBody());
        assertNotNull(resource.renderResource("types-1.xsd", ENDPOINT));
    }

    @Test
    public void typedCollectionXmlMatchesItsGeneratedSchema() throws Exception {
        // 生成有明确字符串元素类型的集合参数定义。
        ServiceDomain<CollectionService> service = this.service(CollectionService.class);
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());
        SoapGeneratedDefinitionResource resource = new SoapGeneratedDefinitionResource( //
                service, config, SoapXmlMapping.newMapper());
        Schema schema = this.schema(resource.renderResource("types.xsd", ENDPOINT));

        // 共享 XML 映射器生成的重复 item 必须被对应 Schema 接受。
        String input = this.inputXml(resource.definition().targetNamespace(), List.of("first", "second"));
        schema.newValidator().validate(new StreamSource(new StringReader(input)));
    }

    @Test
    public void primitiveXmlMatchesItsGeneratedSchema() throws Exception {
        // primitive 参数应使用具体 XML Schema 标量类型。
        ServiceDomain<PrimitiveService> service = this.service(PrimitiveService.class);
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());
        SoapGeneratedDefinitionResource resource = new SoapGeneratedDefinitionResource( //
                service, config, SoapXmlMapping.newMapper());
        Schema schema = this.schema(resource.renderResource("types.xsd", ENDPOINT));

        // 使用共享映射器序列化实际整数，再验证生成的请求定义。
        String input = this.inputXml(resource.definition().targetNamespace(), 42);
        schema.newValidator().validate(new StreamSource(new StringReader(input)));
    }

    @Test
    public void boxedNullXmlMatchesItsGeneratedSchema() throws Exception {
        // 包装类型参数允许 null，并通过 xsi:nil 表达。
        ServiceDomain<BoxedService> service = this.service(BoxedService.class);
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());
        SoapGeneratedDefinitionResource resource = new SoapGeneratedDefinitionResource( //
                service, config, SoapXmlMapping.newMapper());
        Schema schema = this.schema(resource.renderResource("types.xsd", ENDPOINT));

        // 共享映射器的空值表示必须符合生成的 nillable 定义。
        String input = this.inputXml(resource.definition().targetNamespace(), null);
        assertTrue(input.contains("nil=\"true\""));
        schema.newValidator().validate(new StreamSource(new StringReader(input)));
    }

    @Test
    public void scalarDtoXmlMatchesItsGeneratedSchema() throws Exception {
        // 一个 DTO 同时包含枚举、二进制和 XML 标量字段。
        ServiceDomain<ScalarService> service = this.service(ScalarService.class);
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());
        SoapGeneratedDefinitionResource resource = new SoapGeneratedDefinitionResource( //
                service, config, SoapXmlMapping.newMapper());
        ScalarValue value = new ScalarValue();
        value.enabled = true;
        value.count = 3;
        value.optional = null;
        value.ratio = Double.POSITIVE_INFINITY;
        value.rate = Float.NEGATIVE_INFINITY;
        value.amount = new BigDecimal("1E+10");
        value.created = new Date(0);
        value.state = State.ACTIVE;
        value.bytes = new byte[] { 0, 1, -1 };
        value.code = 'A';
        value.text = "<hello>&SOAP";

        // 同一份运行时 XML 映射配置必须产生符合 DTO Schema 的内容。
        Schema schema = this.schema(resource.renderResource("types.xsd", ENDPOINT));
        String input = this.inputXml(resource.definition().targetNamespace(), value);
        schema.newValidator().validate(new StreamSource(new StringReader(input)));
    }

    @Test
    public void recursiveDtoSchemaAcceptsAFiniteObjectGraph() throws Exception {
        // 自引用 DTO 的类型图有环，具体业务对象以 null 结束。
        ServiceDomain<NodeService> service = this.service(NodeService.class);
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());
        SoapGeneratedDefinitionResource resource = new SoapGeneratedDefinitionResource( //
                service, config, SoapXmlMapping.newMapper());
        Node value = new Node();
        value.name = "root";
        value.next = new Node();
        value.next.name = "leaf";

        // Schema 使用类型引用表达递归，生成和校验都应正常终止。
        Schema schema = this.schema(resource.renderResource("types.xsd", ENDPOINT));
        String input = this.inputXml(resource.definition().targetNamespace(), value);
        schema.newValidator().validate(new StreamSource(new StringReader(input)));
    }

    @Test
    public void generatedPojoWsdlCanBeLoadedAsAnExternalContract() throws Exception {
        // POJO 的可选属性允许缺省，不应被当作 operation 的可选 Java 形参。
        ServiceDomain<ScalarService> service = this.service(ScalarService.class);
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());
        SoapGeneratedDefinitionResource generated = new SoapGeneratedDefinitionResource( //
                service, config, SoapXmlMapping.newMapper());
        Path wsdl = Files.createTempFile("meteor-generated-pojo", ".wsdl");
        try {
            Files.write(wsdl, generated.renderWsdl(ENDPOINT));

            // 重新走公开的 WSDL 资源加载入口，保留 operation 和结果映射。
            SoapWsdlResource loaded = new SoapWsdlResource(wsdl.toUri().toURL());
            assertEquals(generated.definition().targetNamespace(), loaded.definition().targetNamespace());
            SoapOperation operation = loaded.definition().operation("echo");
            String[] parameterElements = operation.parameterElements().stream() //
                    .map(Object::toString) //
                    .toArray(String[]::new);
            assertArrayEquals(new String[] { "arg0" }, parameterElements);
            assertEquals("return", operation.resultElement().toString());
        } finally {
            Files.deleteIfExists(wsdl);
        }
    }

    @Test
    public void generatedCollectionWsdlCanBeLoadedAsAnExternalContract() throws Exception {
        // 集合 item 的重复次数属于一个参数的数据类型，不增加 operation 的形参数量。
        ServiceDomain<CollectionService> service = this.service(CollectionService.class);
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());
        SoapGeneratedDefinitionResource generated = new SoapGeneratedDefinitionResource( //
                service, config, SoapXmlMapping.newMapper());
        Path wsdl = Files.createTempFile("meteor-generated-collection", ".wsdl");
        try {
            Files.write(wsdl, generated.renderWsdl(ENDPOINT));

            // 外部契约解析保留整个集合参数，不能拒绝其内部 maxOccurs=unbounded。
            SoapWsdlResource loaded = new SoapWsdlResource(wsdl.toUri().toURL());
            SoapOperation operation = loaded.definition().operation("echo");
            String[] parameterElements = operation.parameterElements().stream() //
                    .map(Object::toString) //
                    .toArray(String[]::new);
            assertArrayEquals(new String[] { "arg0" }, parameterElements);
            assertEquals("return", operation.resultElement().toString());
        } finally {
            Files.deleteIfExists(wsdl);
        }
    }

    @Test
    public void generatedSoap12WsdlRetainsItsBindingWhenReloaded() throws Exception {
        // 同一 Java 接口通过配置选择 SOAP 1.2，定义入口沿用 WSDL 1.1。
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of("soapVersion", "1.2"));
        ServiceDomain<NamedService> service = this.service(NamedService.class);
        byte[] definition = SoapDefinitionResource.create(config, new ProtocolMeta(service, null, null, Map.of())).renderWsdl(ENDPOINT);
        Path wsdl = Files.createTempFile("meteor-generated-soap12", ".wsdl");
        try {
            Files.write(wsdl, definition);

            // 重新加载验证消息绑定版本、action要求和操作包装信息均未丢失。
            SoapWsdlResource loaded = new SoapWsdlResource(wsdl.toUri().toURL());
            assertEquals("1.2", loaded.definition().version().displayName());
            SoapOperation operation = loaded.definition().operation("combine");
            assertTrue(operation.actionRequired());
            String[] parameterElements = operation.parameterElements().stream() //
                    .map(Object::toString) //
                    .toArray(String[]::new);
            assertArrayEquals(new String[] { "arg0", "arg1" }, //
                    parameterElements);
            String rendered = new String(definition, StandardCharsets.UTF_8);
            assertTrue(rendered.contains("http://schemas.xmlsoap.org/wsdl/soap12/"));
            assertTrue(rendered.contains("location=\"" + ENDPOINT + "\""));
        } finally {
            Files.deleteIfExists(wsdl);
        }
    }

    @Test
    public void generatedSoap12RequiresActionByDefault() throws Exception {
        // 自动生成 SOAP 1.2 契约，未声明可选 action 配置。
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of("soapVersion", "1.2"));
        ServiceDomain<NamedService> service = this.service(NamedService.class);
        SoapGeneratedDefinitionResource resource = new SoapGeneratedDefinitionResource( //
                service, config, SoapXmlMapping.newMapper());

        // 运行定义与公开 WSDL 均保留标准缺省的必填要求。
        assertTrue(resource.definition().operation("combine").actionRequired());
        String wsdl = new String(resource.renderWsdl(ENDPOINT), StandardCharsets.UTF_8);
        assertTrue(wsdl.contains("soapActionRequired=\"true\""), wsdl);
    }

    @Test
    public void generatedSoap12CanDeclareOptionalAction() throws Exception {
        // 通过我方协议配置生成明确允许缺少 action 的 SOAP 1.2 契约。
        Map<String, String> options = Map.of("soapVersion", "1.2", "soapActionRequired", "false");
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", options);
        ServiceDomain<NamedService> service = this.service(NamedService.class);
        SoapGeneratedDefinitionResource resource = new SoapGeneratedDefinitionResource( //
                service, config, SoapXmlMapping.newMapper());

        // WSDL 与运行定义采用相同要求，不修改必填契约的解码规则。
        assertFalse(resource.definition().operation("combine").actionRequired());
        String wsdl = new String(resource.renderWsdl(ENDPOINT), StandardCharsets.UTF_8);
        assertTrue(wsdl.contains("soapActionRequired=\"false\""), wsdl);
    }

    @Test
    public void soap12OptionalActionOptionDoesNotChangeSoap11() throws Exception {
        // SOAP 1.1 不使用 SOAP 1.2 的媒体类型 action 可选配置。
        Map<String, String> options = Map.of("soapVersion", "1.1", "soapActionRequired", "false");
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", options);
        ServiceDomain<NamedService> service = this.service(NamedService.class);
        SoapGeneratedDefinitionResource resource = new SoapGeneratedDefinitionResource( //
                service, config, SoapXmlMapping.newMapper());

        // SOAPAction 要求保持原样，WSDL 不添加 SOAP 1.2 的属性。
        assertTrue(resource.definition().operation("combine").actionRequired());
        String wsdl = new String(resource.renderWsdl(ENDPOINT), StandardCharsets.UTF_8);
        assertFalse(wsdl.contains("soapActionRequired="), wsdl);
    }

    @Test
    public void jsonPropertyNamespacesAreRetained() throws Exception {
        // JsonProperty namespace 保留为属性实际的 XML QName。
        ServiceDomain<JsonNamespacedService> service = this.service(JsonNamespacedService.class);
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());
        SoapGeneratedDefinitionResource resource = new SoapGeneratedDefinitionResource( //
                service, config, SoapXmlMapping.newMapper());

        // 生成契约接受带 namespace 的字段，不把它降为同名的无 namespace 字段。
        String input = "<Body><s:echo xmlns:s=\"urn:hasor:meteor:soap:GeneratedService\"" //
                + " xmlns:p=\"urn:test:property\"><arg0><p:value>hello</p:value></arg0></s:echo></Body>";
        DocumentBuilderFactory documents = DocumentBuilderFactory.newInstance();
        documents.setNamespaceAware(true);
        byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
        Document document = documents.newDocumentBuilder().parse(new ByteArrayInputStream(inputBytes));
        resource.validateBody(document.getDocumentElement(), resource.definition().operation("echo").inputBody());
    }

    private <T> ServiceDomain<T> service(Class<T> type) {
        ServiceDomain<T> service = new ServiceDomain<>(type);
        service.setBindName(type.getName());
        service.putAliasName("soap", "GeneratedService");
        return service;
    }

    private Schema schema(byte[] bytes) throws Exception {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newSchema(new StreamSource(new ByteArrayInputStream(bytes)));
    }

    private String inputXml(String namespace, Object value) throws Exception {
        XmlMapper mapper = XmlMapper.builder() //
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES) //
                .enable(ToXmlGenerator.Feature.WRITE_NULLS_AS_XSI_NIL) //
                .enable(ToXmlGenerator.Feature.WRITE_XML_SCHEMA_CONFORMING_FLOATS) //
                .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN) //
                .defaultUseWrapper(false) //
                .build();
        String parameter = mapper.writer().withRootName("arg0").writeValueAsString(value);
        return "<s:echo xmlns:s=\"" + namespace + "\">" + parameter + "</s:echo>";
    }

    private interface NamedService {
        String combine(String message, int repetition);
    }

    private interface AttributeResultService {
        @MetReturn(name = "result")
        @JacksonXmlProperty(isAttribute = true)
        String echo(String value);
    }

    private interface WrappedParameterService {
        String echo(@JacksonXmlElementWrapper(localName = "items") List<String> values);
    }

    private interface WrappedResultService {
        @JacksonXmlElementWrapper(localName = "items")
        List<String> echo(String value);
    }

    private interface WrappedOneWayService {
        @MetMessage
        @JacksonXmlElementWrapper(localName = "items")
        void echo(String value);
    }

    private interface WrappedVoidService {
        @JacksonXmlElementWrapper(localName = "items")
        void echo(String value);
    }

    private interface MappedElementService {
        @JacksonXmlProperty(namespace = "urn:test:results")
        @JsonProperty(required = true)
        @JsonSetter(nulls = Nulls.FAIL)
        String echo(@JacksonXmlProperty(namespace = "urn:test:parameters") @JsonProperty(required = true) @JsonSetter(nulls = Nulls.FAIL) String value);
    }

    private interface OverloadedService {
        String echo(String value);

        int echo(int value);
    }

    private interface WrapperCollisionService {
        String echo(String value);

        String echoResponse(String value);
    }

    private interface ObjectService {
        Object echo(Object value);
    }

    private interface MapService {
        Map<String, String> echo(Map<String, String> value);
    }

    private interface StreamService {
        Stream<String> echo(Stream<String> value);
    }

    @SuppressWarnings("rawtypes")
    private interface RawCollectionService {
        List echo(List value);
    }

    private interface NestedCollectionService {
        List<List<String>> echo(List<List<String>> value);
    }

    private interface PrimitiveService {
        int echo(int value);
    }

    private interface BoxedService {
        Integer echo(Integer value);
    }

    private interface ScalarService {
        ScalarValue echo(ScalarValue value);
    }

    private interface NodeService {
        Node echo(Node value);
    }

    private interface NamespacedService {
        NamespacedValue echo(NamespacedValue value);
    }

    private interface JsonNamespacedService {
        JsonNamespacedValue echo(JsonNamespacedValue value);
    }

    public static class JsonNamespacedValue {
        @JsonProperty(namespace = "urn:test:property")
        public String value;
    }

    public enum State {
        ACTIVE,
        CLOSED
    }

    public static class ScalarValue {
        public boolean    enabled;
        public int        count;
        public Integer    optional;
        public double     ratio;
        public float      rate;
        public BigDecimal amount;
        public Date       created;
        public State      state;
        public byte[]     bytes;
        public char       code;
        public String     text;
    }

    public static class Node {
        public String name;
        public Node   next;
    }

    public static class NamespacedValue {
        @JacksonXmlProperty(namespace = "urn:test:property")
        public String value;
    }
}

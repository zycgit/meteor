/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.definition;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlText;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.annotation.*;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlMapping;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import static org.junit.Assert.*;

public class SoapXmlSchemaMappingTest {
    private static final String SERVICE_NAMESPACE = "urn:hasor:meteor:soap:MappedService";
    private static final URI    ENDPOINT          = URI.create("http://localhost/services/MappedService");

    @Test
    public void customTypeNamespaceIsPublishedAsAnImportedSchema() throws Exception {
        // 类型映射独立于服务 operation 所在的命名空间。
        SoapGeneratedDefinitionResource resource = this.resource(OrderedService.class);

        // 主文档通过可读取的相对资源引用指定的类型名称。
        String main = new String(resource.renderResource("types.xsd", ENDPOINT), StandardCharsets.UTF_8);
        assertTrue(main.contains("schemaLocation=\"" + ENDPOINT + "?xsd=types-1.xsd\""));
        Document types = this.parse(resource.renderResource("types-1.xsd", ENDPOINT));
        assertEquals("urn:meteor:test:types", types.getDocumentElement().getAttribute("targetNamespace"));
        Element type = this.named(types, "complexType", "Ordered");
        assertNotNull(type);
    }

    @Test
    public void propertyOrderDeterminesTheGeneratedSequence() throws Exception {
        // 成员 order 数字越小越靠前，元素名称仍由 name 独立配置。
        SoapGeneratedDefinitionResource resource = this.resource(OrderedService.class);
        String body = "<arg0><lastName>last</lastName><firstName>first</firstName></arg0>";

        // 先 last 后 first 的文档符合发布的 sequence。
        this.validate(resource, body);
    }

    @Test
    public void propertyOrderRejectsAReversedWireSequence() {
        // 使用与成员 order 升序相反的字段顺序。
        SoapGeneratedDefinitionResource resource = this.resource(OrderedService.class);
        String body = "<arg0><firstName>first</firstName><lastName>last</lastName></arg0>";

        // Schema 不应继续接受与实际声明不一致的字段顺序。
        assertThrows(IOException.class, () -> this.validate(resource, body));
    }

    @Test
    public void attributeOrderDeterminesTheGeneratedDeclarationOrder() throws Exception {
        // 报文属性和 Schema 声明共用数字 order，此处验证 Schema 中的声明排列。
        SoapGeneratedDefinitionResource resource = this.resource(OrderedAttributeService.class);
        Document types = this.parse(resource.renderResource("types.xsd", ENDPOINT));
        Element type = this.named(types, "complexType", "OrderedAttributes");

        // 未指定 order 的属性使用 0，位于负数与正数之间。
        NodeList attributes = type.getElementsByTagNameNS(XMLConstants.W3C_XML_SCHEMA_NS_URI, "attribute");
        assertEquals(3, attributes.getLength());
        assertEquals("first", ((Element) attributes.item(0)).getAttribute("name"));
        assertEquals("middle", ((Element) attributes.item(1)).getAttribute("name"));
        assertEquals("last", ((Element) attributes.item(2)).getAttribute("name"));
    }

    @Test
    public void requiredAttributeIsRepresentedInTheGeneratedSchema() throws Exception {
        // DTO 包含必填的无命名空间 XML attribute。
        SoapGeneratedDefinitionResource resource = this.resource(AttributeService.class);

        // 必填属性与普通子元素可在一个 complexType 中同时使用。
        this.validate(resource, "<arg0 id=\"A\"><name>entry</name></arg0>");
    }

    @Test
    public void missingRequiredAttributeIsRejectedByTheGeneratedSchema() {
        // 请求省略发布契约中要求的 id 属性。
        SoapGeneratedDefinitionResource resource = this.resource(AttributeService.class);

        // 校验发生在 DTO 解码之前，不能把缺失属性当作默认 null。
        assertThrows(IOException.class, () -> this.validate(resource, "<arg0><name>entry</name></arg0>"));
    }

    @Test
    public void qualifiedAttributesKeepTheirOwnNamespace() throws Exception {
        // 属性的 namespace 与 DTO 类型和 operation 的 namespace 均不同。
        SoapGeneratedDefinitionResource resource = this.resource(QualifiedAttributeService.class);

        // 全局 attribute 声明通过 ref 引用，保留限定名。
        this.validate(resource, "<arg0 xmlns:a=\"urn:meteor:test:attributes\" a:id=\"A\"/>");
    }

    @Test
    public void qualifiedAttributeCannotBeReplacedByAnUnqualifiedName() {
        // 请求使用相同 localName，却遗漏属性的命名空间。
        SoapGeneratedDefinitionResource resource = this.resource(QualifiedAttributeService.class);

        // namespace 是契约的一部分，不按 localName 降级匹配。
        assertThrows(IOException.class, () -> this.validate(resource, "<arg0 id=\"A\"/>"));
    }

    @Test
    public void equalLocalNamesInDifferentNamespacesRemainDistinctElements() throws Exception {
        // 两个 Java 属性均映射为 name，所属命名空间不同。
        SoapGeneratedDefinitionResource resource = this.resource(QNameService.class);
        String body = "<arg0 xmlns:b=\"urn:meteor:test:buyer\" xmlns:d=\"urn:meteor:test:delivery\">" //
                + "<b:name>buyer</b:name><d:name>delivery</d:name></arg0>";

        // 两个全局元素分别校验，不会合并为一个字段。
        this.validate(resource, body);
    }

    @Test
    public void simpleContentCanCarryTextAndAttributes() throws Exception {
        // Value 注解将 amount 映射为文本，currency 映射为属性。
        SoapGeneratedDefinitionResource resource = this.resource(AmountService.class);

        // simpleContent extension 保留文本的 decimal 类型。
        this.validate(resource, "<arg0 currency=\"CNY\">12.50</arg0>");
    }

    @Test
    public void collectionWrapperAcceptsAnEmptyContainer() throws Exception {
        // 集合容器与 item 使用独立的局部名称。
        SoapGeneratedDefinitionResource resource = this.resource(CollectionService.class);

        // 空集合对应存在的 wrapper 和零个 item。
        this.validate(resource, "<arg0><entries/></arg0>");
    }

    @Test
    public void collectionWrapperAcceptsANilContainer() throws Exception {
        // 可空 wrapper 表示整个集合为 null。
        SoapGeneratedDefinitionResource resource = this.resource(CollectionService.class);

        // nil 容器与空容器由 Schema 分别表达。
        this.validate(resource, "<arg0 xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" //
                + "<entries xsi:nil=\"true\"/></arg0>");
    }

    @Test
    public void collectionWrapperAcceptsANilItem() throws Exception {
        // 存在集合并只包含一个空元素。
        SoapGeneratedDefinitionResource resource = this.resource(CollectionService.class);

        // item 与 wrapper 共享 nillable，两者均允许显式 nil。
        this.validate(resource, "<arg0 xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" //
                + "<entries><entry xsi:nil=\"true\"/></entries></arg0>");
    }

    @Test
    public void foreignCollectionWrapperIsPublishedAsAGlobalElement() throws Exception {
        // namespace 同时指定集合外层容器和内部条目的命名空间。
        SoapGeneratedDefinitionResource resource = this.resource(QualifiedCollectionService.class);
        String body = "<arg0 xmlns:i=\"urn:meteor:test:items\">" //
                + "<i:entries><i:entry>one</i:entry><i:entry>two</i:entry></i:entries></arg0>";

        // 外部 wrapper 与 item 使用同一 namespace 的全局声明。
        this.validate(resource, body);
    }

    @Test
    public void collectionWrapperRejectsADifferentNamespace() {
        // 即使 localName 相同，容器也必须使用元素声明的 namespace。
        SoapGeneratedDefinitionResource resource = this.resource(QualifiedCollectionService.class);
        String body = "<arg0 xmlns:w=\"urn:meteor:test:wrappers\" xmlns:i=\"urn:meteor:test:items\">" //
                + "<w:entries><i:entry>one</i:entry></w:entries></arg0>";

        // 不允许为外层容器隐式保留一套不同的 namespace。
        assertThrows(IOException.class, () -> this.validate(resource, body));
    }

    @Test
    public void collectionItemRejectsADifferentNamespace() {
        // 容器使用正确 namespace，内部条目不能退回无 namespace。
        SoapGeneratedDefinitionResource resource = this.resource(QualifiedCollectionService.class);
        String body = "<arg0 xmlns:i=\"urn:meteor:test:items\">" //
                + "<i:entries><entry>one</entry></i:entries></arg0>";

        // namespace 对包装层和条目同时生效。
        assertThrows(IOException.class, () -> this.validate(resource, body));
    }

    @Test
    public void enumLexicalValuesAppearInTheGeneratedRestriction() throws Exception {
        // 枚举 XML 值与 Java 常量名称不同。
        SoapGeneratedDefinitionResource resource = this.resource(EnumService.class);

        // restriction 与实际编码使用相同的 lexical value。
        this.validate(resource, "<arg0>in-progress</arg0>");
    }

    @Test
    public void methodAndParameterAnnotationsControlOperationPartQNames() {
        // 参数和返回值直接使用 Element 映射，不修改 Java 服务方法名。
        SoapGeneratedDefinitionResource resource = this.resource(ParameterService.class);

        // operation 保留 echo；两条消息的 part 使用完整的限定名。
        SoapOperation operation = resource.definition().operation("echo");
        assertEquals(List.of(new QName(SERVICE_NAMESPACE, "request")), operation.parameterElements());
        assertEquals(new QName("urn:meteor:test:results", "result"), operation.resultElement());
        assertEquals(1, operation.inputBody().parts().get(0).minOccurs());
    }

    @Test
    public void generatedMultiSchemaWsdlReloadsWithoutExportedSideFiles() throws Exception {
        // WSDL 内嵌全部 namespace 的 Schema，并按 namespace 关联 import。
        SoapGeneratedDefinitionResource resource = this.resource(QNameService.class);
        Path file = Files.createTempFile("meteor-xml-mapping", ".wsdl");
        try {
            Files.write(file, resource.renderWsdl(ENDPOINT));

            // 单独保存的 WSDL 可以经正式导入入口重新加载。
            SoapWsdlResource imported = new SoapWsdlResource(file.toUri().toURL());
            QName expected = resource.definition().operation("echo").inputElement();
            QName actual = imported.definition().operation("echo").inputElement();
            assertEquals(expected, actual);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void exportedSchemaImportsPointToPublishedResourceUrls() throws Exception {
        // 定义页面通过同一服务端点的 xsd 参数返回各 namespace 的文档。
        SoapGeneratedDefinitionResource resource = this.resource(QNameService.class);
        URI requestEndpoint = URI.create(ENDPOINT + "?wsdl#section");

        // import 使用实际公开 URL，不受当前请求的查询参数和 fragment 影响。
        String schema = new String(resource.renderResource("types.xsd", requestEndpoint), StandardCharsets.UTF_8);
        assertTrue(schema.contains("schemaLocation=\"" + ENDPOINT + "?xsd=types-1.xsd\""));
        assertTrue(schema.contains("schemaLocation=\"" + ENDPOINT + "?xsd=types-2.xsd\""));
        assertFalse(schema.contains("?wsdl"));
    }

    @Test
    public void differentJavaTypesCannotClaimTheSameXmlTypeQName() {
        // 两个不同 DTO 声明完全相同的类型名与命名空间。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            this.resource(TypeCollisionService.class);
        });

        // 发布阶段给出冲突，而不是输出重复或互相覆盖的类型。
        assertTrue(failure.getMessage().contains("XML type QName is already used"));
    }

    @Test
    public void incompatibleForeignElementsCannotClaimTheSameQName() {
        // 同一全局元素 QName 被声明为两个不兼容的标量类型。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            this.resource(ElementCollisionService.class);
        });

        // global element 的复用只接受相同契约。
        assertTrue(failure.getMessage().contains("Conflicting SOAP global XML element"));
    }

    @Test
    public void encodedBindingRejectsLiteralCollectionWrappers() {
        // SOAP encoded 数组使用标准 array/id/ref 描述。
        Map<String, String> options = Map.of("soapStyle", "rpc", "soapUse", "encoded");
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", options);

        // 不允许把 literal 集合包装默默降级为另一种线协议。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            this.resource(CollectionService.class, config);
        });
        assertTrue(failure.getMessage().contains("SOAP encoded values do not support XML element wrappers"));
    }

    @Test
    public void encodedBindingRejectsSimpleContentBeans() {
        // 文本+属性属于 literal simpleContent，不是 encoded struct 的成员列表。
        Map<String, String> options = Map.of("soapStyle", "rpc", "soapUse", "encoded");
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", options);

        // 契约准备时明确拒绝无法保留含义的映射组合。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            this.resource(AmountService.class, config);
        });
        assertTrue(failure.getMessage().contains("SOAP encoded values do not support XML text properties"));
    }

    @Test
    public void bareParameterCanUseAnUnqualifiedExplicitGlobalName() throws Exception {
        // bare 全局参数可以显式选择空 namespace，而不继承服务 namespace。
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of("soapParameterStyle", "bare"));
        SoapGeneratedDefinitionResource resource = this.resource(BareService.class, config);

        // 独立的无 namespace Schema 参与校验和 WSDL message QName 描述。
        SoapOperation operation = resource.definition().operation("echo");
        assertEquals(new QName("", "request"), operation.parameterElements().get(0));
        byte[] input = "<Body><request>hello</request></Body>".getBytes(StandardCharsets.UTF_8);
        Element body = this.parse(input).getDocumentElement();
        resource.validateBody(body, operation.inputBody());
    }

    @Test
    public void renamingAPrimitiveParameterKeepsItsRequiredOccurrence() {
        // 基本类型不能表达缺省 null；只修改名称不改变这个 Java 契约。
        SoapGeneratedDefinitionResource resource = this.resource(PrimitiveParameterService.class);

        // 发布出的 part 仍为必需，协议入口无需为改名拒绝该接口。
        SoapPart part = resource.definition().operation("echo").inputBody().parts().get(0);
        assertEquals("number", part.element().getLocalPart());
        assertEquals(1, part.minOccurs());
    }

    @Test
    public void primitiveArrayItemsCannotDeclareNil() {
        // 直接数组参数的 item 与 DTO 内数组属性使用相同的基本类型约束。
        SoapGeneratedDefinitionResource resource = this.resource(PrimitiveArrayService.class);
        String parameter = "<arg0 xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" //
                + "<item xsi:nil=\"true\"/></arg0>";

        // 生成 Schema 在解码前拒绝不能赋给 int[] 的空元素。
        assertThrows(IOException.class, () -> this.validate(resource, parameter));
    }

    @Test
    public void generatedTypeNamesDoNotCollideWithExplicitTypeNames() throws Exception {
        // 用户选择的 Type2 不应被另一未标注 DTO 的自动名称覆盖。
        SoapGeneratedDefinitionResource resource = this.resource(ReservedNameService.class);

        // 自动命名跳过已占用名称，同时保留用户指定的类型名。
        Document schema = this.parse(resource.renderResource("types.xsd", ENDPOINT));
        assertNotNull(this.named(schema, "complexType", "Type2"));
        assertNotNull(this.named(schema, "complexType", "Type3"));
    }

    @Test
    public void compatibleForeignElementDeclarationsAreReused() throws Exception {
        // 两个 DTO 的独立属性声明相同 QName、类型和空值约束。
        SoapGeneratedDefinitionResource resource = this.resource(SharedElementService.class);

        // 只发布一个全局 element，避免生成重复组件。
        Document schema = this.parse(resource.renderResource("types-1.xsd", ENDPOINT));
        assertEquals(1, schema.getElementsByTagNameNS(XMLConstants.W3C_XML_SCHEMA_NS_URI, "element").getLength());
    }

    @Test
    public void incompatibleForeignAttributesCannotClaimTheSameQName() {
        // 属性名称虽然相同，两个 DTO 却分别要求字符串和整数。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            this.resource(AttributeCollisionService.class);
        });

        // 不兼容的全局 attribute 在发布时被拒绝。
        assertTrue(failure.getMessage().contains("Conflicting SOAP global XML attribute"));
    }

    @Test
    public void requiredCollectionWrapperCannotBeOmitted() {
        // required 同时约束 wrapper 的存在和 item 的最小数量。
        SoapGeneratedDefinitionResource resource = this.resource(RequiredWrapperService.class);

        // 容器不能缺省。
        assertThrows(IOException.class, () -> this.validate(resource, "<arg0/>"));
    }

    @Test
    public void requiredCollectionWrapperCannotBeEmpty() {
        // 已出现的 required 容器还要求至少存在一个条目。
        SoapGeneratedDefinitionResource resource = this.resource(RequiredWrapperService.class);

        // 只有空容器不能满足共享的 required 约束。
        assertThrows(IOException.class, () -> this.validate(resource, "<arg0><entries/></arg0>"));
    }

    @Test
    public void requiredCollectionWrapperAcceptsAnItem() throws Exception {
        // 容器和条目均出现，满足两层共享的 required 约束。
        SoapGeneratedDefinitionResource resource = this.resource(RequiredWrapperService.class);

        // 单个 required 属性同时表达容器和条目的必需性。
        this.validate(resource, "<arg0><entries><values>one</values></entries></arg0>");
    }

    @Test
    public void requiredNillableCollectionWrapperAcceptsExplicitNil() throws Exception {
        // required 要求容器出现，nillable 允许出现的容器显式表示 null。
        SoapGeneratedDefinitionResource resource = this.resource(RequiredWrapperService.class);
        String parameter = "<arg0 xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" //
                + "<entries xsi:nil=\"true\"/></arg0>";

        // nil 容器无需包含 item，与非 nil 的空容器保持区别。
        this.validate(resource, parameter);
    }

    @Test
    public void nonNillableCollectionWrapperRejectsExplicitNil() {
        // nillable=false 同时禁止集合容器和条目声明 xsi:nil。
        SoapGeneratedDefinitionResource resource = this.resource(NonNillableCollectionService.class);
        String parameter = "<arg0 xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" //
                + "<entries xsi:nil=\"true\"/></arg0>";

        // 容器不能单独保留默认的可空设置。
        assertThrows(IOException.class, () -> this.validate(resource, parameter));
    }

    @Test
    public void nonNillableCollectionItemRejectsExplicitNil() {
        // 相同的 nillable=false 也作用于容器中的每一个条目。
        SoapGeneratedDefinitionResource resource = this.resource(NonNillableCollectionService.class);
        String parameter = "<arg0 xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" //
                + "<entries><entry xsi:nil=\"true\"/></entries></arg0>";

        // 存在非空容器也不能使其中的 nil 条目合法。
        assertThrows(IOException.class, () -> this.validate(resource, parameter));
    }

    @Test
    public void nonNillableElementsRejectExplicitNil() {
        // 非空元素可为可选，但一旦出现就不能声明 xsi:nil。
        SoapGeneratedDefinitionResource resource = this.resource(NonNillableService.class);
        String parameter = "<arg0 xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" //
                + "<value xsi:nil=\"true\"/></arg0>";

        // minOccurs 与 nillable 的含义由各自的 XSD 属性约束。
        assertThrows(IOException.class, () -> this.validate(resource, parameter));
    }

    @Test
    public void xmlLanguageAttributesUseTheBundledStandardSchema() throws Exception {
        // xml 命名空间使用标准前缀和内置声明，不生成一份镜像定义。
        SoapGeneratedDefinitionResource resource = this.resource(LanguageService.class);

        // 标准 xml:lang 属性参与普通 DTO 校验，全部资源可以离线解析。
        this.validate(resource, "<arg0 xml:lang=\"zh-CN\"><value>hello</value></arg0>");
        assertNull(resource.renderResource("types-1.xsd", ENDPOINT));
        assertNotNull(resource.renderResource("xml-namespace.xsd", ENDPOINT));
    }

    @Test
    public void soap11EncodedGraphIdsCannotBeReusedAsBusinessAttributes() {
        // SOAP 1.1 的无 namespace id 是对象图控制字段。
        Map<String, String> options = Map.of("soapStyle", "rpc", "soapUse", "encoded");
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", options);

        // literal 可使用 id，encoded 则必须在发布时报告这个冲突。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            this.resource(AttributeService.class, config);
        });
        assertTrue(failure.getMessage().contains("conflicts with SOAP encoded control attribute: id"));
    }

    @Test
    public void soap11EncodedGraphReferencesCannotBeReusedAsBusinessAttributes() {
        // href 会被 SOAP 1.1 解码器解释为对象引用。
        Map<String, String> options = Map.of("soapStyle", "rpc", "soapUse", "encoded");
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", options);

        // 提前拒绝业务字段与图引用的歧义。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            this.resource(ReferenceAttributeService.class, config);
        });
        assertTrue(failure.getMessage().contains("conflicts with SOAP encoded control attribute: href"));
    }

    @Test
    public void soap12EncodedControlAttributesCannotBeOverridden() {
        // SOAP 1.2 的 enc:ref 使用标准的编码 namespace。
        Map<String, String> options = Map.of("soapVersion", "1.2", "soapStyle", "rpc", "soapUse", "encoded");
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", options);

        // 业务属性不能覆盖编码器维护的对象图控制字段。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            this.resource(Soap12ControlAttributeService.class, config);
        });
        assertTrue(failure.getMessage().contains("conflicts with SOAP encoded control attribute"));
    }

    @Test
    public void xsiTypeCannotBeOverriddenByAnEncodedBusinessAttribute() {
        // xsi:type 决定 encoded 值的实际类型，不能由业务值重写。
        Map<String, String> options = Map.of("soapStyle", "rpc", "soapUse", "encoded");
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", options);

        // 契约生成阶段直接拒绝保留属性。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            this.resource(InstanceControlAttributeService.class, config);
        });
        assertTrue(failure.getMessage().contains("conflicts with SOAP encoded control attribute"));
    }

    @Test
    public void operationPartsCannotDeclareElementsInTheReservedXmlNamespace() {
        // xml 命名空间只承载标准 XML 属性，不能为业务参数定义新元素。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            this.resource(ReservedOperationNamespaceService.class);
        });

        // 方法参数注解也在发布时检查，不等待 Schema 编译产生前缀错误。
        assertTrue(failure.getMessage().contains("SOAP operation element cannot use a reserved XML namespace"));
    }

    private SoapGeneratedDefinitionResource resource(Class<?> type) {
        return this.resource(type, ProtocolConfig.newConfig("soap", "soap", Map.of()));
    }

    private SoapGeneratedDefinitionResource resource(Class<?> type, ProtocolConfig config) {
        ServiceDomain<?> service = new ServiceDomain<>(type);
        service.setBindName(type.getName());
        service.putAliasName("soap", "MappedService");
        return new SoapGeneratedDefinitionResource(service, config, SoapXmlMapping.newMapper());
    }

    private void validate(SoapGeneratedDefinitionResource resource, String parameter) throws Exception {
        String input = "<Body><s:echo xmlns:s=\"" + SERVICE_NAMESPACE + "\">" + parameter + "</s:echo></Body>";
        Element body = this.parse(input.getBytes(StandardCharsets.UTF_8)).getDocumentElement();
        resource.validateBody(body, resource.definition().operation("echo").inputBody());
    }

    private Document parse(byte[] body) throws Exception {
        DocumentBuilderFactory documents = DocumentBuilderFactory.newInstance();
        documents.setNamespaceAware(true);
        documents.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        documents.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        documents.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return documents.newDocumentBuilder().parse(new ByteArrayInputStream(body));
    }

    private Element named(Document document, String kind, String name) {
        Element root = document.getDocumentElement();
        for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element element)) {
                continue;
            }
            boolean sameName = StringUtils.equals(name, element.getAttribute("name"));
            if (sameName && StringUtils.equals(kind, element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private interface OrderedService {
        Ordered echo(Ordered value);
    }

    @MetType(name = "Ordered")
    @JacksonXmlRootElement(namespace = "urn:meteor:test:types")
    public static class Ordered {
        @MetField(name = "firstName")
        @JsonProperty(index = 20, required = true)
        public String first;
        @MetField(name = "lastName")
        @JsonProperty(index = 10, required = true)
        public String last;
    }

    private interface OrderedAttributeService {
        OrderedAttributes echo(OrderedAttributes value);
    }

    @MetType(name = "OrderedAttributes")
    public static class OrderedAttributes {
        @JacksonXmlProperty(isAttribute = true)
        @JsonProperty(index = 20)
        public String last;
        @JacksonXmlProperty(isAttribute = true)
        @JsonProperty(index = -10)
        public String first;
        @JacksonXmlProperty(isAttribute = true)
        public String middle;
    }

    private interface AttributeService {
        AttributeValue echo(AttributeValue value);
    }

    public static class AttributeValue {
        @JacksonXmlProperty(isAttribute = true)
        @JsonProperty(required = true)
        public String id;
        public String name;
    }

    private interface QualifiedAttributeService {
        QualifiedAttribute echo(QualifiedAttribute value);
    }

    public static class QualifiedAttribute {
        @JacksonXmlProperty(isAttribute = true, namespace = "urn:meteor:test:attributes")
        @JsonProperty(required = true)
        public String id;
    }

    private interface QNameService {
        QNameValue echo(QNameValue value);
    }

    public static class QNameValue {
        @MetField(name = "name")
        @JacksonXmlProperty(namespace = "urn:meteor:test:buyer")
        @JsonProperty(index = 10, required = true)
        public String buyer;
        @MetField(name = "name")
        @JacksonXmlProperty(namespace = "urn:meteor:test:delivery")
        @JsonProperty(index = 20, required = true)
        public String delivery;
    }

    private interface AmountService {
        Amount echo(Amount value);
    }

    public static class Amount {
        @JacksonXmlProperty(isAttribute = true)
        @JsonProperty(required = true)
        public String     currency;
        @JacksonXmlText
        public BigDecimal value;
    }

    private interface CollectionService {
        CollectionValue echo(CollectionValue value);
    }

    public static class CollectionValue {
        @MetField(name = "entry")
        @JacksonXmlElementWrapper(localName = "entries")
        public List<String> values;
    }

    private interface QualifiedCollectionService {
        QualifiedCollection echo(QualifiedCollection value);
    }

    public static class QualifiedCollection {
        @MetField(name = "entry")
        @JacksonXmlProperty(namespace = "urn:meteor:test:items")
        @JacksonXmlElementWrapper(localName = "entries", namespace = "urn:meteor:test:items")
        public List<String> values;
    }

    public static class User {
        @JacksonXmlProperty(isAttribute = true)
        public String id;
    }

    @MetType(name = "LocalQualifiedSingle")
    @JacksonXmlRootElement(namespace = "urn:meteor:test:users")
    public static class LocalQualifiedSingle {
        @MetField(name = "user")
        @JacksonXmlProperty(namespace = "urn:meteor:test:users")
        @JacksonXmlElementWrapper(localName = "users", namespace = "urn:meteor:test:users")
        public User users;
    }

    public static class RepeatedWrapper {
        @MetField(name = "value")
        @JacksonXmlProperty(namespace = "urn:meteor:test:shared")
        @JacksonXmlElementWrapper(localName = "values", namespace = "urn:meteor:test:shared")
        public List<String> values;
    }

    private interface EnumService {
        State echo(State value);
    }

    public enum State {
        @MetEnumValue(name = "in-progress") ACTIVE,
        CLOSED
    }

    private interface ParameterService {
        @MetReturn(name = "result")
        @JacksonXmlProperty(namespace = "urn:meteor:test:results")
        String echo(@MetParam(name = "request") @JacksonXmlProperty(namespace = SERVICE_NAMESPACE) String value);
    }

    private interface TypeCollisionService {
        CollisionOne echo(CollisionTwo value);
    }

    @MetType(name = "Conflict")
    public static class CollisionOne {
        public String one;
    }

    @MetType(name = "Conflict")
    public static class CollisionTwo {
        public String two;
    }

    private interface ElementCollisionService {
        String echo(@MetParam(name = "value") @JacksonXmlProperty(namespace = "urn:meteor:test:collision") String one, //
                @MetParam(name = "value") @JacksonXmlProperty(namespace = "urn:meteor:test:collision") Integer two);
    }

    private interface PrimitiveParameterService {
        int echo(@MetParam(name = "number") int value);
    }

    private interface PrimitiveArrayService {
        int[] echo(int[] value);
    }

    private interface ReservedNameService {
        DefaultNamed echo(ReservedName value);
    }

    @MetType(name = "Type2")
    public static class ReservedName {
        public String value;
    }

    public static class DefaultNamed {
        public String value;
    }

    private interface SharedElementService {
        SharedOne echo(SharedTwo value);
    }

    public static class SharedOne {
        @MetField(name = "value")
        @JacksonXmlProperty(namespace = "urn:meteor:test:shared")
        public String first;
    }

    public static class SharedTwo {
        @MetField(name = "value")
        @JacksonXmlProperty(namespace = "urn:meteor:test:shared")
        public String second;
    }

    private interface AttributeCollisionService {
        ConflictingAttributeOne echo(ConflictingAttributeTwo value);
    }

    public static class ConflictingAttributeOne {
        @MetField(name = "id")
        @JacksonXmlProperty(isAttribute = true, namespace = "urn:meteor:test:attributes")
        public String id;
    }

    public static class ConflictingAttributeTwo {
        @MetField(name = "id")
        @JacksonXmlProperty(isAttribute = true, namespace = "urn:meteor:test:attributes")
        public Integer id;
    }

    private interface RequiredWrapperService {
        RequiredWrapper echo(RequiredWrapper value);
    }

    public static class RequiredWrapper {
        @MetField
        @JacksonXmlElementWrapper(localName = "entries")
        @JsonProperty(required = true)
        public List<String> values;
    }

    private interface NonNillableCollectionService {
        NonNillableCollection echo(NonNillableCollection value);
    }

    public static class NonNillableCollection {
        @MetField(name = "entry")
        @JacksonXmlElementWrapper(localName = "entries")
        @JsonSetter(nulls = Nulls.FAIL)
        public List<String> values;
    }

    private interface NonNillableService {
        NonNillable echo(NonNillable value);
    }

    public static class NonNillable {
        @MetField
        @JsonSetter(nulls = Nulls.FAIL)
        public String value;
    }

    private interface LanguageService {
        LanguageValue echo(LanguageValue value);
    }

    public static class LanguageValue {
        @MetField(name = "lang")
        @JacksonXmlProperty(isAttribute = true, namespace = XMLConstants.XML_NS_URI)
        public String language;
        public String value;
    }

    private interface ReferenceAttributeService {
        ReferenceAttribute echo(ReferenceAttribute value);
    }

    public static class ReferenceAttribute {
        @JacksonXmlProperty(isAttribute = true)
        public String href;
    }

    private interface Soap12ControlAttributeService {
        Soap12ControlAttribute echo(Soap12ControlAttribute value);
    }

    public static class Soap12ControlAttribute {
        @MetField(name = "ref")
        @JacksonXmlProperty(isAttribute = true, namespace = "http://www.w3.org/2003/05/soap-encoding")
        public String reference;
    }

    private interface InstanceControlAttributeService {
        InstanceControlAttribute echo(InstanceControlAttribute value);
    }

    public static class InstanceControlAttribute {
        @MetField(name = "type")
        @JacksonXmlProperty(isAttribute = true, namespace = XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI)
        public String type;
    }

    private interface BareService {
        String echo(@MetParam(name = "request") String value);
    }

    private interface ReservedOperationNamespaceService {
        String echo(@MetParam(name = "request") @JacksonXmlProperty(namespace = XMLConstants.XML_NS_URI) String value);
    }
}

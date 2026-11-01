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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlText;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.annotation.MetEnumValue;
import net.hasor.meteor.annotation.MetField;
import net.hasor.meteor.annotation.MetType;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlMapping;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import static org.junit.jupiter.api.Assertions.*;

public class SoapMapSchemaTest {
    private static final URI ENDPOINT = URI.create("http://localhost/services/MapService");

    @Test
    public void mapParameterAcceptsDynamicElementNames() throws Exception {
        // 参数本身就是 Map，每个 key 在同一个容器下生成一个元素。
        SoapGeneratedDefinitionResource resource = this.resource(MapService.class);

        // 无需为 timeout 和 mode 预先发布不同的 element 声明。
        this.validate(resource, "<arg0><timeout>30</timeout><mode>fast</mode></arg0>");
    }

    @Test
    public void mapContractDoesNotEnumerateEntryNames() throws Exception {
        // 一个 Map 类型同时被输入和输出使用。
        SoapGeneratedDefinitionResource resource = this.resource(MapService.class);
        Document schema = this.parse(resource.renderResource("types.xsd", ENDPOINT));

        // wildcard 描述任意数量的动态键，V 类型校验留在运行期执行。
        NodeList wildcards = schema.getElementsByTagNameNS(XMLConstants.W3C_XML_SCHEMA_NS_URI, "any");
        assertEquals(1, wildcards.getLength());
        Element wildcard = (Element) wildcards.item(0);
        assertEquals("##any", wildcard.getAttribute("namespace"));
        assertEquals("skip", wildcard.getAttribute("processContents"));
        assertEquals("0", wildcard.getAttribute("minOccurs"));
        assertEquals("unbounded", wildcard.getAttribute("maxOccurs"));
    }

    @Test
    public void mapWithDtoValuesPublishesTheDeclaredValueType() throws Exception {
        // V 的结构由签名确定，与 Map 是否为空、含有哪些 key 无关。
        SoapGeneratedDefinitionResource resource = this.resource(DtoMapService.class);
        Document schema = this.parse(resource.renderResource("types.xsd", ENDPOINT));

        // DTO 类型提前存在于固定 XSD，动态 key 继续通过 wildcard 表达。
        assertNotNull(this.namedType(schema, "MapValue"));
        this.validate(resource, "<arg0><first><value>A</value></first><second><value>B</value></second></arg0>");
    }

    @Test
    public void objectMapCanPublishComplexValues() throws Exception {
        // Object value 保持开放，复杂值不需要按某次请求额外修改 XSD。
        SoapGeneratedDefinitionResource resource = this.resource(ObjectMapService.class);

        // 动态 key 可以承载文本、属性和嵌套元素。
        this.validate(resource, "<arg0><timeout>30</timeout><user id=\"A\"><name>Alice</name></user></arg0>");
    }

    @Test
    public void emptyDtoMapUsesTheSamePublishedSchema() throws Exception {
        // 发布只读取声明泛型，不需要第一条 DTO 数据来发现字段。
        SoapGeneratedDefinitionResource resource = this.resource(DtoMapService.class);
        byte[] published = resource.renderResource("types.xsd", ENDPOINT);

        // 空 Map 直接满足原有契约，不触发缺失类型或重新生成。
        this.validate(resource, "<arg0/>");
        assertArrayEquals(published, resource.renderResource("types.xsd", ENDPOINT));
    }

    @Test
    public void mapListValuesPublishTheirRepeatedItemType() throws Exception {
        // List 的每个条目仍在动态 key 元素内部，不平铺到 Map 层级。
        SoapGeneratedDefinitionResource resource = this.resource(ListMapService.class);
        Document schema = this.parse(resource.renderResource("types.xsd", ENDPOINT));
        NodeList items = schema.getElementsByTagNameNS(XMLConstants.W3C_XML_SCHEMA_NS_URI, "element");
        Element item = null;
        for (int index = 0; index < items.getLength(); index++) {
            Element element = (Element) items.item(index);
            if (StringUtils.equals("item", element.getAttribute("name"))) {
                item = element;
                break;
            }
        }

        // 固定 XSD 包含重复 int 元素，报文中的 key 仍由数据提供。
        assertNotNull(item);
        assertEquals("xsd:int", item.getAttribute("type"));
        assertEquals("unbounded", item.getAttribute("maxOccurs"));
        this.validate(resource, "<arg0><scores><item>1</item><item>2</item></scores></arg0>");
    }

    @Test
    public void mapSetValuesPublishTheirDtoItemType() throws Exception {
        // Set 在 XML 中沿用重复元素，声明的 DTO 需要在发布时递归生成。
        SoapGeneratedDefinitionResource resource = this.resource(SetMapService.class);
        Document schema = this.parse(resource.renderResource("types.xsd", ENDPOINT));

        // 不需要预先声明分组名称，也不会遗漏集合内部的 DTO。
        assertNotNull(this.namedType(schema, "MapValue"));
        this.validate(resource, "<arg0><group><item><value>A</value></item></group></arg0>");
    }

    @Test
    public void nestedMapsPublishEveryDeclaredValueType() throws Exception {
        // 两层动态 key 仍共享同一个提前确定的叶子 DTO 类型。
        SoapGeneratedDefinitionResource resource = this.resource(NestedMapService.class);
        Document schema = this.parse(resource.renderResource("types.xsd", ENDPOINT));

        // 每层 Map 各有一个 wildcard，叶子结构不依赖实际数据。
        assertEquals(2, schema.getElementsByTagNameNS(XMLConstants.W3C_XML_SCHEMA_NS_URI, "any").getLength());
        assertNotNull(this.namedType(schema, "MapValue"));
        this.validate(resource, "<arg0><region><user><value>A</value></user></region></arg0>");
    }

    @Test
    public void mapValueInAnotherNamespaceIsImported() throws Exception {
        // V 的类型 namespace 与 Map 容器所在的服务 namespace 不同。
        SoapGeneratedDefinitionResource resource = this.resource(ForeignDtoMapService.class);
        Document schema = this.parse(resource.renderResource("types.xsd", ENDPOINT));
        NodeList imports = schema.getElementsByTagNameNS(XMLConstants.W3C_XML_SCHEMA_NS_URI, "import");

        // 固定 XSD 可发现额外的值类型，不只在内存中的 schema 集合保留它。
        assertEquals(1, imports.getLength());
        assertEquals("urn:map:value", ((Element) imports.item(0)).getAttribute("namespace"));
        Document valueSchema = this.parse(resource.renderResource("types-1.xsd", ENDPOINT));
        assertNotNull(this.namedType(valueSchema, "ForeignMapValue"));
    }

    @Test
    public void mapKeyCannotAccidentallySelectAnOperationWrapperContract() throws Exception {
        // 动态 key 碰巧与全局 operation wrapper 同名，不应被改按参数包装结构校验。
        SoapGeneratedDefinitionResource resource = this.resource(MapService.class);
        String namespace = resource.definition().targetNamespace();

        // skip 保留 Map key 的语义，精确 namespace 仍由运行期映射检查。
        this.validate(resource, "<arg0><s:echo xmlns:s=\"" + namespace + "\">text</s:echo></arg0>");
    }

    @Test
    public void mapValuesStillPublishTheirEnumType() throws Exception {
        // value 的枚举别名继续由 公共枚举名称 定义。
        SoapGeneratedDefinitionResource resource = this.resource(EnumMapService.class);
        Document schema = this.parse(resource.renderResource("types.xsd", ENDPOINT));

        // wildcard 不枚举 key，但枚举值的 restriction 仍会生成。
        NodeList values = schema.getElementsByTagNameNS(XMLConstants.W3C_XML_SCHEMA_NS_URI, "enumeration");
        assertEquals(1, values.getLength());
        assertEquals("ready", ((Element) values.item(0)).getAttribute("value"));
    }

    @Test
    public void sameMapTypeCanBeUsedInDifferentElementNamespaces() throws Exception {
        // 两个字段共享 Map<String, String> 类型，但容器及动态键各有自己的 namespace。
        SoapGeneratedDefinitionResource resource = this.resource(QualifiedMapsService.class);
        String body = "<arg0 xmlns:a=\"urn:map:first\" xmlns:b=\"urn:map:second\">" //
                + "<a:first><a:timeout>30</a:timeout></a:first>" //
                + "<b:second><b:mode>fast</b:mode></b:second></arg0>";

        // 不把第一次遇到的字段 namespace 固化到共享 Map 类型。
        this.validate(resource, body);
    }

    @Test
    public void attributeMapCanCoexistWithStaticAttributes() throws Exception {
        // 固定 id 属性和动态属性 Map 共同归属参数元素。
        SoapGeneratedDefinitionResource resource = this.resource(AttributesService.class);

        // anyAttribute 不抹去静态属性的声明及 required 约束。
        this.validate(resource, "<arg0 id=\"A\" timeout=\"30\" mode=\"fast\"/>");
    }

    @Test
    public void dynamicAttributesDoNotSatisfyARequiredStaticAttribute() {
        // 请求提供动态属性，却遗漏固定的 id。
        SoapGeneratedDefinitionResource resource = this.resource(AttributesService.class);

        // 固定 attribute 声明仍优先于 wildcard 参与校验。
        assertThrows(IOException.class, () -> this.validate(resource, "<arg0 timeout=\"30\"/>"));
    }

    @Test
    public void attributeMapUsesItsDeclaredNamespace() throws Exception {
        // 动态属性的 namespace 直接来自 JacksonXmlProperty。
        SoapGeneratedDefinitionResource resource = this.resource(QualifiedAttributesService.class);

        // key 只提供 localName，prefix 可以由对端自由选择。
        this.validate(resource, "<arg0 xmlns:a=\"urn:map:attributes\" a:timeout=\"30\"/>");
    }

    @Test
    public void attributeMapRejectsAttributesOutsideItsNamespace() {
        // 同名的无 namespace 属性不属于限定 namespace 的动态映射。
        SoapGeneratedDefinitionResource resource = this.resource(QualifiedAttributesService.class);

        // anyAttribute 的 namespace 约束不能退化为接受所有属性。
        assertThrows(IOException.class, () -> this.validate(resource, "<arg0 timeout=\"30\"/>"));
    }

    @Test
    public void disjointAttributeMapsShareOneNamespaceUnionWildcard() throws Exception {
        // 一个父元素有两个不同 namespace 的属性 Map。
        SoapGeneratedDefinitionResource resource = this.resource(MultipleAttributesService.class);
        String body = "<arg0 xmlns:a=\"urn:map:first\" local=\"one\" a:qualified=\"two\"/>";
        this.validate(resource, body);

        // XSD 1.0 一个 complexType 只声明一个 anyAttribute，namespace 采用并集。
        Document schema = this.parse(resource.renderResource("types.xsd", ENDPOINT));
        NodeList wildcards = schema.getElementsByTagNameNS(XMLConstants.W3C_XML_SCHEMA_NS_URI, "anyAttribute");
        assertEquals(1, wildcards.getLength());
        assertEquals("##local urn:map:first", ((Element) wildcards.item(0)).getAttribute("namespace"));
    }

    @Test
    public void simpleTextCanCarryDynamicAttributes() throws Exception {
        // 文本值与属性 Map 可以共同归属同一个 simpleContent 元素。
        SoapGeneratedDefinitionResource resource = this.resource(TextAttributesService.class);

        // 动态属性不应把原有文本内容模型改成子元素模型。
        this.validate(resource, "<arg0 currency=\"CNY\">12.50</arg0>");
    }

    @Test
    public void soapEncodedMapParameterIsRejected() {
        // SOAP encoded 保留原有标准数组及引用规则，不声明动态 Map。
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", //
                Map.of("soapStyle", "rpc", "soapUse", "encoded"));

        // 发布准备就应失败，不生成宽泛却无法编码的契约。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> this.resource(MapService.class, config));
        assertTrue(failure.getMessage().contains("SOAP encoded values do not support dynamic XML maps"));
    }

    @Test
    public void soapEncodedAttributeMapIsRejected() {
        // 只包含动态属性，避免保留字段 id 的错误先于 Map 限制出现。
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", //
                Map.of("soapStyle", "rpc", "soapUse", "encoded"));

        // 与直接 Map 参数在同一个生成阶段报出明确错误。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> this.resource(QualifiedAttributesService.class, config));
        assertTrue(failure.getMessage().contains("SOAP encoded values do not support dynamic XML maps"));
    }

    @Test
    public void mapCannotHideAnUnsupportedStreamValue() {
        // 动态 key 不应让 value 类型免于契约生成阶段的校验。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> this.resource(StreamMapService.class));

        // 错误需要指向不支持的值类型，而不是静默发布开放 anyType。
        assertTrue(failure.getMessage().contains("Stream"));
    }

    @Test
    public void rawMapIsRejectedDuringDefinitionGeneration() {
        // raw Map 未声明可用于确定 XML 内容的 value 类型。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> this.resource(RawMapService.class));

        // 不能发布后再根据实际对象猜测 XML 类型。
        assertTrue(failure.getMessage().contains("Map"));
    }

    @Test
    public void nonStringMapKeysAreRejectedDuringDefinitionGeneration() {
        // 动态 XML 名称由 String key 提供，不自动转换其他 Java key 类型。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> this.resource(IntegerKeyMapService.class));

        // 避免数值 key 等数据在运行时形成非法名称。
        assertTrue(failure.getMessage().contains("Map"));
    }

    private SoapGeneratedDefinitionResource resource(Class<?> type) {
        return this.resource(type, ProtocolConfig.newConfig("soap", "soap", Map.of()));
    }

    private SoapGeneratedDefinitionResource resource(Class<?> type, ProtocolConfig config) {
        ServiceDomain<?> service = new ServiceDomain<>(type);
        service.setBindName(type.getName());
        service.putAliasName("soap", "MapService");
        return new SoapGeneratedDefinitionResource(service, config, SoapXmlMapping.newMapper());
    }

    private void validate(SoapGeneratedDefinitionResource resource, String parameter) throws Exception {
        String namespace = resource.definition().targetNamespace();
        String input = "<Body><s:echo xmlns:s=\"" + namespace + "\">" + parameter + "</s:echo></Body>";
        Element body = this.parse(input.getBytes(StandardCharsets.UTF_8)).getDocumentElement();
        resource.validateBody(body, resource.definition().operation("echo").inputBody());
    }

    private Document parse(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private Element namedType(Document schema, String name) {
        NodeList types = schema.getElementsByTagNameNS(XMLConstants.W3C_XML_SCHEMA_NS_URI, "complexType");
        for (int index = 0; index < types.getLength(); index++) {
            Element type = (Element) types.item(index);
            if (StringUtils.equals(name, type.getAttribute("name"))) {
                return type;
            }
        }
        return null;
    }

    private interface MapService {
        Map<String, String> echo(Map<String, String> values);
    }

    private interface ObjectMapService {
        Map<String, Object> echo(Map<String, Object> values);
    }

    private interface DtoMapService {
        Map<String, MapValue> echo(Map<String, MapValue> values);
    }

    private interface ListMapService {
        Map<String, List<Integer>> echo(Map<String, List<Integer>> values);
    }

    private interface SetMapService {
        Map<String, Set<MapValue>> echo(Map<String, Set<MapValue>> values);
    }

    private interface NestedMapService {
        Map<String, Map<String, MapValue>> echo(Map<String, Map<String, MapValue>> values);
    }

    private interface ForeignDtoMapService {
        Map<String, ForeignMapValue> echo(Map<String, ForeignMapValue> values);
    }

    @MetType(name = "MapValue")
    public static class MapValue {
        public String value;
    }

    @MetType(name = "ForeignMapValue")
    @JacksonXmlRootElement(namespace = "urn:map:value")
    public static class ForeignMapValue {
        public String value;
    }

    private interface EnumMapService {
        Map<String, State> echo(Map<String, State> values);
    }

    public enum State {
        @MetEnumValue(name = "ready") READY
    }

    private interface QualifiedMapsService {
        QualifiedMaps echo(QualifiedMaps value);
    }

    public static class QualifiedMaps {
        @MetField
        @JacksonXmlProperty(namespace = "urn:map:first")
        @JsonProperty(index = 1)
        public Map<String, String> first;
        @MetField
        @JacksonXmlProperty(namespace = "urn:map:second")
        @JsonProperty(index = 2)
        public Map<String, String> second;
    }

    private interface AttributesService {
        Attributes echo(Attributes value);
    }

    public static class Attributes {
        @JacksonXmlProperty(isAttribute = true)
        @JsonProperty(required = true)
        public String              id;
        @JacksonXmlProperty(isAttribute = true)
        public Map<String, String> values;
    }

    private interface QualifiedAttributesService {
        QualifiedAttributes echo(QualifiedAttributes value);
    }

    public static class QualifiedAttributes {
        @JacksonXmlProperty(isAttribute = true, namespace = "urn:map:attributes")
        public Map<String, String> values;
    }

    private interface MultipleAttributesService {
        MultipleAttributes echo(MultipleAttributes value);
    }

    public static class MultipleAttributes {
        @JacksonXmlProperty(isAttribute = true)
        @JsonProperty(index = 1)
        public Map<String, String> local;
        @JacksonXmlProperty(isAttribute = true, namespace = "urn:map:first")
        @JsonProperty(index = 2)
        public Map<String, String> qualified;
    }

    private interface TextAttributesService {
        TextAttributes echo(TextAttributes value);
    }

    public static class TextAttributes {
        @JacksonXmlText
        public String              text;
        @JacksonXmlProperty(isAttribute = true)
        public Map<String, String> values;
    }

    private interface StreamMapService {
        Map<String, Stream<String>> echo(Map<String, Stream<String>> values);
    }

    @SuppressWarnings("rawtypes")
    private interface RawMapService {
        Map echo(Map values);
    }

    private interface IntegerKeyMapService {
        Map<Integer, String> echo(Map<Integer, String> values);
    }
}

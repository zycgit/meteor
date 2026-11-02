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
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.annotation.MetField;
import net.hasor.meteor.annotation.MetType;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlMapping;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class SoapAnyTypeSchemaTest {
    private static final URI ENDPOINT = URI.create("http://localhost/services/AnyTypeService");

    @Test
    public void objectPropertyUsesAnyType() throws Exception {
        // DTO 中的 Object 属性应直接表达开放内容，不能被当成无字段 DTO。
        SoapGeneratedDefinitionResource resource = this.resource(ObjectPropertyService.class);
        Document schema = this.parse(resource.renderResource("types.xsd", ENDPOINT));
        Element value = this.namedElement(schema, "value");

        // 固定 XSD 明确声明 anyType，允许属性和嵌套元素共存。
        assertEquals("xsd:anyType", value.getAttribute("type"));
        this.validate(resource, "<arg0><value code=\"A\"><nested>text</nested></value></arg0>");
    }

    @Test
    public void objectPropertyAcceptsTypedScalarContent() throws Exception {
        // anyType 的实际值通过标准 xsi:type 声明为 int。
        SoapGeneratedDefinitionResource resource = this.resource(ObjectPropertyService.class);

        // 发布时无需知道数值 30，声明的标准类型负责校验文本格式。
        this.validate(resource, "<arg0><value xsi:type=\"xs:int\">30</value></arg0>");
    }

    @Test
    public void objectPropertyRejectsInvalidTypedScalarContent() {
        // 明确声明为 int 的值不能因为外层是 anyType 就跳过简单类型校验。
        SoapGeneratedDefinitionResource resource = this.resource(ObjectPropertyService.class);

        // 类型元数据与文本不一致时在 schema 校验阶段拒绝。
        assertThrows(IOException.class, //
                () -> this.validate(resource, "<arg0><value xsi:type=\"xs:int\">wrong</value></arg0>"));
    }

    @Test
    public void objectPropertyAllowsNil() throws Exception {
        // 普通引用属性的 nillable 默认保留，与 Object 开放内容互不冲突。
        SoapGeneratedDefinitionResource resource = this.resource(ObjectPropertyService.class);

        // 空值不需要附加运行时 Java 类型。
        this.validate(resource, "<arg0><value xsi:nil=\"true\"/></arg0>");
    }

    @Test
    public void anyTypeUsesDtoDefinitionsAlreadyPresentInTheContract() throws Exception {
        // User 通过另一个已声明参数进入固定 XSD，Object 值可以引用这个已知类型。
        SoapGeneratedDefinitionResource resource = this.resource(KnownTypeService.class);
        String input = "<arg0><id>1</id></arg0>" //
                + "<arg1 xsi:type=\"s:User\"><id>2</id></arg1>";

        // xsi:type 只引用已经存在的 XML 类型，不临时修改发布契约。
        this.validate(resource, input);
    }

    @Test
    public void anyTypeKnownDtoStillChecksItsDeclaredFields() {
        // 同一份固定 User 契约也适用于 anyType 上指向 User 的 xsi:type。
        SoapGeneratedDefinitionResource resource = this.resource(KnownTypeService.class);
        String input = "<arg0><id>1</id></arg0>" //
                + "<arg1 xsi:type=\"s:User\"><id>wrong</id></arg1>";

        // 开放入口不会绕过已经明确选择的 DTO 字段类型。
        assertThrows(IOException.class, () -> this.validate(resource, input));
    }

    @Test
    public void objectListKeepsEachItemOpen() throws Exception {
        // List<Object> 的容器结构固定，条目使用 anyType 承载异构值。
        SoapGeneratedDefinitionResource resource = this.resource(ObjectListService.class);
        Document schema = this.parse(resource.renderResource("types.xsd", ENDPOINT));
        Element item = this.namedElement(schema, "item");

        // int 和嵌套 XML 共存，不根据第一个条目选择后续条目的类型。
        assertEquals("xsd:anyType", item.getAttribute("type"));
        assertEquals("unbounded", item.getAttribute("maxOccurs"));
        this.validate(resource, "<arg0><item xsi:type=\"xs:int\">7</item><item><user id=\"A\"/></item></arg0>");
    }

    @Test
    public void objectSetUsesRepeatedAnyTypeItems() throws Exception {
        // Set 的去重由 Java 集合负责，XML 契约仍是重复条目。
        SoapGeneratedDefinitionResource resource = this.resource(ObjectSetService.class);

        // 每个条目可选择不同的基础类型，Set 本身不用运行时数据建立 XSD。
        this.validate(resource, "<arg0><item xsi:type=\"xs:int\">7</item>" //
                + "<item xsi:type=\"xs:boolean\">true</item></arg0>");
    }

    @Test
    public void objectArrayUsesRepeatedAnyTypeItems() throws Exception {
        // Object[] 与 List<Object> 使用同一开放条目规则。
        SoapGeneratedDefinitionResource resource = this.resource(ObjectArrayService.class);
        Document schema = this.parse(resource.renderResource("types.xsd", ENDPOINT));

        // 数组元素允许 nil 和复杂 XML，数组的类型本身在发布阶段已经存在。
        assertEquals("xsd:anyType", this.namedElement(schema, "item").getAttribute("type"));
        this.validate(resource, "<arg0><item xsi:nil=\"true\"/><item><user/></item></arg0>");
    }

    @Test
    public void typedSetPublishesTheDtoItemContract() throws Exception {
        // Set<User> 的 item 必须引用 User，而不是跟随 Object 退化为 anyType。
        SoapGeneratedDefinitionResource resource = this.resource(UserSetService.class);
        Document schema = this.parse(resource.renderResource("types.xsd", ENDPOINT));

        // DTO 的必填 int 成员也在同一份固定契约中校验。
        assertEquals("tns:User", this.namedElement(schema, "item").getAttribute("type"));
        this.validate(resource, "<arg0><item><id>1</id></item><item><id>2</id></item></arg0>");
    }

    @Test
    public void typedListRejectsComplexContentForScalarItems() {
        // 已声明 List<Integer> 继续保留具体类型约束。
        SoapGeneratedDefinitionResource resource = this.resource(IntegerListService.class);

        // 只有 Object 是开放内容，不能把所有集合统一放宽成任意 XML。
        assertThrows(IOException.class, () -> this.validate(resource, "<arg0><item><id>1</id></item></arg0>"));
    }

    @Test
    public void nestedCollectionsRejectFlattenedValues() {
        // 外层 item 的内容仍然是一个完整的 List<Integer> 容器。
        SoapGeneratedDefinitionResource resource = this.resource(NestedListService.class);

        // 直接把整数文本塞进外层 item 会丢失层级，固定 XSD 应拒绝。
        assertThrows(IOException.class, () -> this.validate(resource, "<arg0><item>1</item></arg0>"));
    }

    @Test
    public void nestedCollectionPropertyExpandsOnlyItsOuterLayer() throws Exception {
        // DTO 属性的重复 group 来自外层 List，group 内部仍保存内层 List 的 item。
        SoapGeneratedDefinitionResource resource = this.resource(NestedPropertyService.class);

        // 不带 wrap 的集合属性也不能把两层 List 递归平铺成相同元素。
        this.validate(resource, "<arg0><group><item>1</item><item>2</item></group><group/></arg0>");
    }

    @Test
    public void nestedCollectionWrapperKeepsTheInnerContainers() throws Exception {
        // wrap 只在属性外再包一层，既不代替内层集合也不改变 item 的类型。
        SoapGeneratedDefinitionResource resource = this.resource(WrappedNestedPropertyService.class);

        // 三个层次分别对应 wrap、外层 List 条目和内层 Integer 条目。
        this.validate(resource, "<arg0><groups><group><item>1</item></group><group/></groups></arg0>");
    }

    @Test
    public void listOfMapsPublishesItsNestedDtoType() throws Exception {
        // 泛型类型图穿过 List 和 Map 后，仍需发现 User。
        SoapGeneratedDefinitionResource resource = this.resource(ListMapService.class);
        Document schema = this.parse(resource.renderResource("types.xsd", ENDPOINT));

        // 不依赖 List 是否为空；DTO 字段类型在发布阶段即可确定。
        assertEquals("xsd:int", this.namedElement(schema, "id").getAttribute("type"));
        this.validate(resource, "<arg0><item><first><id>1</id></first></item><item/></arg0>");
    }

    @Test
    public void objectAttributeMapKeepsTheSimpleAttributeWildcard() throws Exception {
        // 属性 Map 的 Object 只表示混合简单文本，不采用元素的 anyType 内容模型。
        SoapGeneratedDefinitionResource resource = this.resource(ObjectAttributesService.class);
        Document schema = this.parse(resource.renderResource("types.xsd", ENDPOINT));

        // anyAttribute 只容纳属性，不生成复杂元素内容声明。
        assertEquals(1, schema.getElementsByTagNameNS(XMLConstants.W3C_XML_SCHEMA_NS_URI, "anyAttribute").getLength());
        this.validate(resource, "<arg0 age=\"30\" enabled=\"true\"/>");
    }

    @Test
    public void inheritedTypeVariableResolvesFromThePublishedService() throws Exception {
        // 方法声明在 Base<T>，实际发布接口将 T 绑定为 User。
        SoapGeneratedDefinitionResource resource = this.resource(InheritedUserService.class);

        // 不能把未在 Method 上替换的 T 错当 Object，否则这条非法 id 会被 anyType 接受。
        assertThrows(IOException.class, () -> this.validate(resource, "<arg0><id>wrong</id></arg0>"));
    }

    @Test
    public void inheritedNestedGenericPublishesTheBoundDto() throws Exception {
        // 继承绑定必须穿过 Map<String,List<T>>，不能只替换最外层参数。
        SoapGeneratedDefinitionResource resource = this.resource(InheritedNestedUserService.class);
        Document schema = this.parse(resource.renderResource("types.xsd", ENDPOINT));

        // 返回值与参数共享固定 User 类型，空 Map 也无需运行时补充结构。
        assertEquals("xsd:int", this.namedElement(schema, "id").getAttribute("type"));
        assertEquals("tns:User", this.namedElement(schema, "item").getAttribute("type"));
        this.validate(resource, "<arg0/>");
    }

    private SoapGeneratedDefinitionResource resource(Class<?> type) {
        ServiceDomain<?> service = new ServiceDomain<>(type);
        service.setBindName(type.getName());
        service.putAliasName("soap", "AnyTypeService");
        ProtocolConfig config = ProtocolConfig.newConfig("soap", "soap", Map.of());
        return new SoapGeneratedDefinitionResource(service, config, SoapXmlMapping.newMapper());
    }

    private void validate(SoapGeneratedDefinitionResource resource, String parameters) throws Exception {
        String namespace = resource.definition().targetNamespace();
        String input = "<Body><s:echo xmlns:s=\"" + namespace + "\"" //
                + " xmlns:xsi=\"" + XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI + "\"" //
                + " xmlns:xs=\"" + XMLConstants.W3C_XML_SCHEMA_NS_URI + "\">" + parameters + "</s:echo></Body>";
        Element body = this.parse(input.getBytes(StandardCharsets.UTF_8)).getDocumentElement();
        resource.validateBody(body, resource.definition().operation("echo").inputBody());
    }

    private Element namedElement(Document schema, String name) {
        NodeList elements = schema.getElementsByTagNameNS(XMLConstants.W3C_XML_SCHEMA_NS_URI, "element");
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            if (StringUtils.equals(name, element.getAttribute("name"))) {
                return element;
            }
        }
        throw new AssertionError("Missing element: " + name);
    }

    private Document parse(byte[] bytes) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private interface ObjectPropertyService {
        ObjectProperty echo(ObjectProperty value);
    }

    public static class ObjectProperty {
        public Object value;
    }

    private interface KnownTypeService {
        Object echo(User declared, Object value);
    }

    @MetType(name = "User")
    public static class User {
        public int id;
    }

    private interface ObjectListService {
        List<Object> echo(List<Object> values);
    }

    private interface ObjectSetService {
        Set<Object> echo(Set<Object> values);
    }

    private interface ObjectArrayService {
        Object[] echo(Object[] values);
    }

    private interface UserSetService {
        Set<User> echo(Set<User> values);
    }

    private interface IntegerListService {
        List<Integer> echo(List<Integer> values);
    }

    private interface NestedListService {
        List<List<Integer>> echo(List<List<Integer>> values);
    }

    private interface NestedPropertyService {
        NestedProperty echo(NestedProperty value);
    }

    public static class NestedProperty {
        @MetField(name = "group")
        public List<List<Integer>> groups;
    }

    private interface WrappedNestedPropertyService {
        WrappedNestedProperty echo(WrappedNestedProperty value);
    }

    public static class WrappedNestedProperty {
        @MetField(name = "group")
        @JacksonXmlElementWrapper(localName = "groups")
        public List<List<Integer>> values;
    }

    private interface ListMapService {
        List<Map<String, User>> echo(List<Map<String, User>> values);
    }

    private interface ObjectAttributesService {
        ObjectAttributes echo(ObjectAttributes value);
    }

    public static class ObjectAttributes {
        @JacksonXmlProperty(isAttribute = true)
        public Map<String, Object> values;
    }

    private interface GenericService<T> {
        T echo(T value);
    }

    private interface InheritedUserService extends GenericService<User> {
    }

    private interface GenericNestedService<T> {
        Map<String, List<T>> echo(Map<String, List<T>> values);
    }

    private interface InheritedNestedUserService extends GenericNestedService<User> {
    }
}

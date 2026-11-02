/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.mapping;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlText;
import net.hasor.meteor.annotation.MetField;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class SoapMapMappingTest {
    private final XmlMapper      mapper  = SoapXmlMapping.newMapper();
    private final SoapXmlMapping mapping = new SoapXmlMapping(this.mapper);

    @Test
    public void mapTypeRetainsStringKeysAndConcreteValuesWithoutBeanProperties() {
        // Map 是动态 XML 容器，不对 JDK Map 接口进行 POJO 属性发现。
        JavaType javaType = this.mapper.getTypeFactory().constructMapType(Map.class, String.class, Integer.class);
        SoapXmlType type = this.mapping.type(javaType);

        assertTrue(SoapXmlMapping.isMap(type.getJavaType()));
        assertEquals(String.class, type.getJavaType().getKeyType().getRawClass());
        assertEquals(Integer.class, type.getJavaType().getContentType().getRawClass());
        assertTrue(type.getProperties().isEmpty());
    }

    @Test
    public void mapPropertyRetainsDeclaredPojoValueType() {
        // 动态 key 只决定元素名称，value 的 DTO 结构来自声明泛型。
        JavaType type = this.mapper.constructType(PojoElementMap.class);
        SoapXmlProperty property = this.mapping.type(type).getProperties().get(0);

        assertEquals(User.class, property.getJavaType().getContentType().getRawClass());
        assertEquals(new QName("users"), property.getName());
    }

    @Test
    public void mapPropertyRetainsDeclaredCollectionValueType() {
        // 集合内容仍是 User，不能因外层为 Map 而丢失嵌套泛型。
        JavaType type = this.mapper.constructType(CollectionElementMap.class);
        SoapXmlProperty property = this.mapping.type(type).getProperties().get(0);
        JavaType valueType = property.getJavaType().getContentType();

        assertEquals(List.class, valueType.getRawClass());
        assertEquals(User.class, valueType.getContentType().getRawClass());
    }

    @Test
    public void mapPropertyRetainsNestedMapValueType() {
        // 各层 Map 分别保留自己的 key/value 声明，与运行时是否为空无关。
        JavaType type = this.mapper.constructType(NestedElementMap.class);
        SoapXmlProperty property = this.mapping.type(type).getProperties().get(0);
        JavaType valueType = property.getJavaType().getContentType();

        assertEquals(Map.class, valueType.getRawClass());
        assertEquals(String.class, valueType.getKeyType().getRawClass());
        assertEquals(Integer.class, valueType.getContentType().getRawClass());
    }

    @Test
    public void objectTypeIsAnOpenContentTerminal() {
        // Object 对应 anyType，不按无可读写属性的普通 DTO 拒绝。
        SoapXmlType type = this.mapping.type(this.mapper.constructType(Object.class));

        assertEquals(Object.class, type.getJavaType().getRawClass());
        assertTrue(type.getProperties().isEmpty());
    }

    @Test
    public void listPropertyAllowsObjectItems() {
        // Object 集合条目保留开放内容类型，不从样本值决定映射。
        SoapXmlProperty property = this.mapping.type(this.mapper.constructType(ObjectList.class)) //
                .getProperties().get(0);

        assertEquals(List.class, property.getJavaType().getRawClass());
        assertEquals(Object.class, property.getJavaType().getContentType().getRawClass());
    }

    @Test
    public void setPropertyAllowsObjectItems() {
        // Set 与 List 共用条目映射，但元数据继续保留 Java Set 的声明。
        SoapXmlProperty property = this.mapping.type(this.mapper.constructType(ObjectSet.class)) //
                .getProperties().get(0);

        assertEquals(Set.class, property.getJavaType().getRawClass());
        assertEquals(Object.class, property.getJavaType().getContentType().getRawClass());
    }

    @Test
    public void rawListPropertyUsesObjectItems() {
        // 未提供条目泛型的 List 使用 Object，而非猜测实际数据的第一个条目。
        SoapXmlProperty property = this.mapping.type(this.mapper.constructType(RawList.class)) //
                .getProperties().get(0);

        assertEquals(Object.class, property.getJavaType().getContentType().getRawClass());
    }

    @Test
    public void rawMapTypeIsRejected() {
        // 原始 Map 未声明 key/value，无法建立可靠的读写契约。
        JavaType type = this.mapper.constructType(Map.class);

        assertThrows(IllegalArgumentException.class, () -> this.mapping.type(type));
    }

    @Test
    public void nonStringMapKeysAreRejected() {
        // XML local name 的来源必须明确为 String，不隐式转换数字或对象 key。
        JavaType type = this.mapper.constructType(NonStringKeys.class);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> this.mapping.type(type));
        assertTrue(failure.getMessage().contains("String keys"));
    }

    @Test
    public void objectMapValuesAllowOpenElementContent() {
        // Object 明确表示开放元素内容，元数据不把它收窄为 String。
        JavaType type = this.mapper.constructType(ObjectValues.class);
        SoapXmlProperty property = this.mapping.type(type).getProperties().get(0);

        assertEquals(Object.class, property.getJavaType().getContentType().getRawClass());
        assertFalse(property.isAttribute());
    }

    @Test
    public void objectAttributeMapValuesAllowDynamicScalarContent() {
        // 动态属性也可声明 Object 值，不把其误当作可展开的 DTO。
        JavaType type = this.mapper.constructType(ObjectAttributeValues.class);
        SoapXmlProperty property = this.mapping.type(type).getProperties().get(0);

        assertEquals(Object.class, property.getJavaType().getContentType().getRawClass());
        assertTrue(property.isAttribute());
    }

    @Test
    public void attributeMapIgnoresConfiguredStaticName() {
        // Map 的 key 提供属性名，name 不会把全部条目重命名为同一个名称。
        SoapXmlProperty property = this.mapping.type(this.mapper.constructType(AttributeMap.class)) //
                .getProperties().get(0);

        assertTrue(property.isAttribute());
        assertEquals(new QName("urn:settings", "values"), property.getName());
        assertNull(property.getWrapper());
        assertFalse(property.isNillable());
    }

    @Test
    public void attributeMapAllowsEnumValues() {
        // 属性内容可用枚举的既有 XML 文本规则表达。
        SoapXmlProperty property = this.mapping.type(this.mapper.constructType(EnumAttributeMap.class)) //
                .getProperties().get(0);

        assertEquals(State.class, property.getJavaType().getContentType().getRawClass());
    }

    @Test
    public void attributeMapRejectsPojoValues() {
        // 属性只能承载文本内容，DTO 不能隐式压缩到单个 XML 属性里。
        JavaType type = this.mapper.constructType(PojoAttributeMap.class);

        assertThrows(IllegalArgumentException.class, () -> this.mapping.type(type));
    }

    @Test
    public void attributeMapRejectsCollectionValues() {
        // 集合属性的分隔协议未定义，不推测成空格分隔文本。
        JavaType type = this.mapper.constructType(CollectionAttributeMap.class);

        assertThrows(IllegalArgumentException.class, () -> this.mapping.type(type));
    }

    @Test
    public void attributeMapRejectsNestedMapValues() {
        // Map 元素可以嵌套，XML 属性仍不能承载子元素结构。
        JavaType type = this.mapper.constructType(NestedAttributeMap.class);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> this.mapping.type(type));
        assertTrue(failure.getMessage().contains("attributes and text require scalar or enum"));
    }

    @Test
    public void plainObjectAttributeIsRejected() {
        // anyType 只适用于元素，独立 Object 属性没有可固定的简单类型映射。
        JavaType type = this.mapper.constructType(ObjectAttribute.class);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> this.mapping.type(type));
        assertTrue(failure.getMessage().contains("attributes and text require scalar or enum"));
    }

    @Test
    public void objectTextPropertyIsRejected() {
        // JacksonXmlText 保持简单内容，不能隐式展开任意 Object。
        JavaType type = this.mapper.constructType(ObjectText.class);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> this.mapping.type(type));
        assertTrue(failure.getMessage().contains("attributes and text require scalar or enum"));
    }

    @Test
    public void staticAndDynamicAttributesMayShareOneParent() {
        // 一个动态命名空间加固定属性可共享父元素，具体 key 碰撞由运行期检测。
        SoapXmlType type = this.mapping.type(this.mapper.constructType(StaticAndDynamicAttributes.class));

        assertEquals(2, type.getProperties().size());
        assertTrue(type.getProperties().stream().allMatch(SoapXmlProperty::isAttribute));
    }

    @Test
    public void dynamicAttributesMayUseDifferentNamespacesOnOneParent() {
        // 未包装的属性 Map 可以用独立命名空间区分各自的 key 集合。
        SoapXmlType type = this.mapping.type(this.mapper.constructType(DifferentDynamicNamespaces.class));

        assertEquals(2, type.getProperties().size());
    }

    @Test
    public void unwrappedAttributeMapMayCoexistWithText() {
        // 动态属性直接属于文本所在元素时，simpleContent 结构仍成立。
        SoapXmlType type = this.mapping.type(this.mapper.constructType(TextWithAttributeMap.class));

        assertEquals(2, type.getProperties().size());
    }

    @Test
    public void dynamicAttributesCannotUseXmlNamespace() {
        // 动态 Map 不能生成 xml:* 协议属性，静态 xml:lang 另行保留。
        JavaType type = this.mapper.constructType(XmlNamespaceAttributeMap.class);

        assertThrows(IllegalArgumentException.class, () -> this.mapping.type(type));
    }

    @Test
    public void dynamicAttributesCannotUseXsiNamespace() {
        // 动态 key 不能覆盖 xsi:nil、xsi:type 等协议属性。
        JavaType type = this.mapper.constructType(XsiNamespaceAttributeMap.class);

        assertThrows(IllegalArgumentException.class, () -> this.mapping.type(type));
    }

    @Test
    public void dynamicAttributesCannotUseSoapEncodingNamespace() {
        // 动态属性不能伪造 SOAP encoding 元信息。
        JavaType type = this.mapper.constructType(SoapNamespaceAttributeMap.class);

        assertThrows(IllegalArgumentException.class, () -> this.mapping.type(type));
    }

    @Test
    public void staticXmlLanguageAttributeRemainsSupported() {
        // 普通固定 xml:lang 不受动态 Map 的命名空间限制影响。
        SoapXmlProperty property = this.mapping.type(this.mapper.constructType(XmlLanguageAttribute.class)) //
                .getProperties().get(0);

        assertEquals(new QName(XMLConstants.XML_NS_URI, "lang"), property.getName());
    }

    public static class PojoElementMap {
        @MetField(name = "users")
        public Map<String, User> values;
    }

    public static class CollectionElementMap {
        public Map<String, List<User>> values;
    }

    public static class NestedElementMap {
        public Map<String, Map<String, Integer>> values;
    }

    public static class ObjectList {
        public List<Object> values;
    }

    public static class ObjectSet {
        public Set<Object> values;
    }

    public static class RawList {
        public List values;
    }

    public static class NonStringKeys {
        public Map<Integer, String> values;
    }

    public static class ObjectValues {
        public Map<String, Object> values;
    }

    public static class ObjectAttributeValues {
        @JacksonXmlProperty(isAttribute = true)
        public Map<String, Object> values;
    }

    public static class AttributeMap {
        @MetField(name = "ignored:name")
        @JacksonXmlProperty(isAttribute = true, namespace = "urn:settings")
        public Map<String, Integer> values;
    }

    public static class EnumAttributeMap {
        @JacksonXmlProperty(isAttribute = true)
        public Map<String, State> values;
    }

    public static class PojoAttributeMap {
        @JacksonXmlProperty(isAttribute = true)
        public Map<String, User> values;
    }

    public static class CollectionAttributeMap {
        @JacksonXmlProperty(isAttribute = true)
        public Map<String, List<String>> values;
    }

    public static class NestedAttributeMap {
        @JacksonXmlProperty(isAttribute = true)
        public Map<String, Map<String, String>> values;
    }

    public static class ObjectAttribute {
        @JacksonXmlProperty(isAttribute = true)
        public Object value;
    }

    public static class ObjectText {
        @JacksonXmlText
        public Object value;
    }

    public static class StaticAndDynamicAttributes {
        @MetField(name = "id")
        @JacksonXmlProperty(isAttribute = true)
        public String              id;
        @JacksonXmlProperty(isAttribute = true)
        public Map<String, String> values;
    }

    public static class DifferentDynamicNamespaces {
        @JacksonXmlProperty(isAttribute = true, namespace = "urn:first")
        public Map<String, String> first;
        @JacksonXmlProperty(isAttribute = true, namespace = "urn:second")
        public Map<String, String> second;
    }

    public static class TextWithAttributeMap {
        @JacksonXmlText
        public String              text;
        @JacksonXmlProperty(isAttribute = true)
        public Map<String, String> attributes;
    }

    public static class XmlNamespaceAttributeMap {
        @JacksonXmlProperty(isAttribute = true, namespace = XMLConstants.XML_NS_URI)
        public Map<String, String> attributes;
    }

    public static class XsiNamespaceAttributeMap {
        @JacksonXmlProperty(isAttribute = true, namespace = XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI)
        public Map<String, String> attributes;
    }

    public static class SoapNamespaceAttributeMap {
        @JacksonXmlProperty(isAttribute = true, namespace = "http://schemas.xmlsoap.org/soap/encoding/")
        public Map<String, String> attributes;
    }

    public static class XmlLanguageAttribute {
        @MetField(name = "lang")
        @JacksonXmlProperty(isAttribute = true, namespace = XMLConstants.XML_NS_URI)
        public String language;
    }

    public static class User {
        @JacksonXmlProperty(isAttribute = true)
        public String id;
    }

    public enum State {
        ACTIVE
    }
}

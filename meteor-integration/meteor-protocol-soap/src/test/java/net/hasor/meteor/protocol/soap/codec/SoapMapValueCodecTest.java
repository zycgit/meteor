/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.codec;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.XMLConstants;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import net.hasor.meteor.annotation.MetEnumValue;
import net.hasor.meteor.annotation.MetField;
import net.hasor.meteor.protocol.soap.definition.SoapXmlUtils;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlMapping;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import static org.junit.Assert.*;

public class SoapMapValueCodecTest {
    @Test
    public void scalarMapValuesRejectPojoStyleAttributesOnRead() throws Exception {
        // 动态 key 的基础值只有文本，不能借 wildcard 跳过校验后丢弃 DTO 属性。
        Type type = new TypeReference<Map<String, Integer>>() {
        }.getType();
        Element target = this.target("");
        Element entry = SoapXmlUtils.append(target, "", "user");
        entry.setAttribute("id", "123");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> //
                        this.codec().read(target, type));
        assertTrue(failure.getMessage().contains("business attributes"));
    }

    @Test
    public void attributeMapExcludesStandardXmlAndSoapControlAttributes() throws Exception {
        // 标准 XML 与 SOAP 协议属性不参与无命名空间的业务 Map。
        Element target = this.target("");
        target.setAttributeNS(XMLConstants.XML_NS_URI, "xml:lang", "zh-CN");
        target.setAttributeNS("http://schemas.xmlsoap.org/soap/envelope/", "s:encodingStyle", "urn:encoding");

        AttributeMap result = (AttributeMap) this.codec().read(target, AttributeMap.class);
        assertTrue(result.attributes.isEmpty());
    }

    @Test
    public void rootMapUsesKeysAsElementsInIterationOrder() throws Exception {
        // 操作参数自身就是 Map，target 已是容器，不再生成 entry/key/value 固定结构。
        Type type = new TypeReference<Map<String, Integer>>() {
        }.getType();
        Map<String, Integer> value = new LinkedHashMap<>();
        value.put("timeout", 30);
        value.put("retries", 2);
        Element target = this.target("urn:settings");

        // 动态名称沿用容器 namespace，写出顺序沿用 Map 迭代顺序。
        this.codec().write(target, type, value, new LinkedHashMap<>());
        assertEquals("timeout", target.getFirstChild().getLocalName());
        assertEquals("retries", target.getLastChild().getLocalName());
        assertEquals("urn:settings", target.getFirstChild().getNamespaceURI());
        assertEquals(value, this.codec().read(target, type));
    }

    @Test
    public void objectValueMapsPreserveScalarTypesUsingXsiType() throws Exception {
        // Object 的基础值携带标准 XML 类型，数字与布尔不退化为文本。
        Type type = new TypeReference<Map<String, Object>>() {
        }.getType();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("timeout", 30);
        value.put("enabled", true);
        value.put("mode", "fast");
        value.put("state", State.READY);
        Element target = this.target("");

        this.codec().write(target, type, value, null);
        Map<String, Object> expected = Map.of("timeout", 30, "enabled", true, "mode", "fast", "state", "ready");
        assertEquals(expected, this.codec().read(target, type));
        Element timeout = (Element) target.getFirstChild();
        assertTrue(timeout.hasAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "type"));
    }

    @Test
    public void mapDeclarationReadsDtoValuesWithBusinessAttributes() throws Exception {
        // 泛型给出 DTO 类型，Map key 仍作为元素名，DTO 属性写到该元素。
        Type type = new TypeReference<Map<String, User>>() {
        }.getType();
        User user = new User();
        user.id = "123";
        Element target = this.target("");

        this.codec().write(target, type, Map.of("alice", user), null);
        assertEquals("123", ((Element) target.getFirstChild()).getAttribute("id"));
        Map<?, ?> decoded = (Map<?, ?>) this.codec().read(target, type);
        assertEquals("123", ((User) decoded.get("alice")).id);
    }

    @Test
    public void mapDeclarationReadsSetValuesInsideEachKey() throws Exception {
        // 每个动态 key 是集合容器，条目使用重复 item，Set 根据声明恢复。
        Type type = new TypeReference<Map<String, Set<Integer>>>() {
        }.getType();
        Set<Integer> ports = new LinkedHashSet<>();
        ports.add(80);
        ports.add(90);
        Map<String, Set<Integer>> value = Map.of("ports", ports);
        Element target = this.target("");

        this.codec().write(target, type, value, null);
        Element container = (Element) target.getFirstChild();
        assertEquals("ports", container.getLocalName());
        assertEquals("item", container.getFirstChild().getLocalName());
        assertEquals(value, this.codec().read(target, type));
    }

    @Test
    public void mapDeclarationReadsNestedMapValues() throws Exception {
        // 每层 Map 都按自身 key 创建元素，内层 Integer 不依赖运行数据推断。
        Type type = new TypeReference<Map<String, Map<String, Integer>>>() {
        }.getType();
        Map<String, Map<String, Integer>> value = Map.of("network", Map.of("timeout", 30));
        Element target = this.target("");

        this.codec().write(target, type, value, null);
        assertEquals("network", target.getFirstChild().getLocalName());
        assertEquals("timeout", target.getFirstChild().getFirstChild().getLocalName());
        assertEquals(value, this.codec().read(target, type));
    }

    @Test
    public void objectElementMapRetainsUnboundDtoAsXml() throws Exception {
        // Object 隐藏 DTO 时不虚构未发布的 XML 类型，未知结构保留为 DOM。
        Type type = new TypeReference<Map<String, Object>>() {
        }.getType();
        User value = new User();
        value.id = "123";
        Element target = this.target("");

        this.codec().write(target, type, Map.of("user", value), null);
        Map<?, ?> decoded = (Map<?, ?>) this.codec().read(target, type);
        Element user = (Element) decoded.get("user");
        assertEquals("123", user.getAttribute("id"));
        String xsiType = user.getAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "type");
        assertTrue(xsiType.endsWith(":anyType"));
    }

    @Test
    public void objectElementMapRetainsUnboundCollectionAsXml() throws Exception {
        // Object 中的集合展开 item，但接收端不会猜测 List 或 Set。
        Type type = new TypeReference<Map<String, Object>>() {
        }.getType();
        Element target = this.target("");

        this.codec().write(target, type, Map.of("ports", List.of(80, 90)), null);
        Map<?, ?> decoded = (Map<?, ?>) this.codec().read(target, type);
        Element ports = (Element) decoded.get("ports");
        assertEquals("item", ports.getFirstChild().getLocalName());
        assertEquals("80", ports.getFirstChild().getTextContent());
        assertEquals("90", ports.getLastChild().getTextContent());
    }

    @Test
    public void objectElementMapRetainsUnboundNestedMapAsXml() throws Exception {
        // Object 中的 Map 仍按动态 key 展开，接收端保留未绑定的子树。
        Type type = new TypeReference<Map<String, Object>>() {
        }.getType();
        Element target = this.target("");

        this.codec().write(target, type, Map.of("network", Map.of("timeout", 30)), null);
        Map<?, ?> decoded = (Map<?, ?>) this.codec().read(target, type);
        Element network = (Element) decoded.get("network");
        assertEquals("timeout", network.getFirstChild().getLocalName());
        assertEquals("30", network.getFirstChild().getTextContent());
    }

    @Test
    public void objectElementMapReadsUnknownNestedElementsAsXml() throws Exception {
        // 没有类型绑定的复杂内容按 XML 保留，不把重复元素压成 Map。
        Type type = new TypeReference<Map<String, Object>>() {
        }.getType();
        Element target = this.target("");
        Element value = SoapXmlUtils.append(target, "", "value");
        SoapXmlUtils.append(value, "", "nested").setTextContent("first");
        SoapXmlUtils.append(value, "", "nested").setTextContent("second");

        Map<?, ?> decoded = (Map<?, ?>) this.codec().read(target, type);
        Element actual = (Element) decoded.get("value");
        assertEquals("first", actual.getFirstChild().getTextContent());
        assertEquals("second", actual.getLastChild().getTextContent());
        assertNotSame(value, actual);
    }

    @Test
    public void integerElementMapRejectsActualStringValue() throws Exception {
        // 原始 Map 不能把与 Integer 声明不兼容的值传入框架。
        Type type = new TypeReference<Map<String, Integer>>() {
        }.getType();
        Object value = "thirty";

        assertThrows(IllegalArgumentException.class, //
                () -> this.codec().write(this.target(""), type, Map.of("timeout", value), null));
    }

    @Test
    public void integerElementMapRejectsActualLongValue() throws Exception {
        // 原始 Map 不能把与 Integer 声明不兼容的值传入框架。
        Type type = new TypeReference<Map<String, Integer>>() {
        }.getType();
        Object value = 30L;

        assertThrows(IllegalArgumentException.class, //
                () -> this.codec().write(this.target(""), type, Map.of("timeout", value), null));
    }

    @Test
    public void integerElementMapRejectsActualDecimalValue() throws Exception {
        // 原始 Map 不能把与 Integer 声明不兼容的值传入框架。
        Type type = new TypeReference<Map<String, Integer>>() {
        }.getType();
        Object value = 3.5;

        assertThrows(IllegalArgumentException.class, //
                () -> this.codec().write(this.target(""), type, Map.of("timeout", value), null));
    }

    @Test
    public void integerElementMapRejectsEmptyNumericText() throws Exception {
        // 空文本不是 Integer，也不等同显式 xsi:nil。
        Type type = new TypeReference<Map<String, Integer>>() {
        }.getType();
        Element invalid = this.target("");
        SoapXmlUtils.append(invalid, "", "timeout");

        assertThrows(IllegalArgumentException.class, //
                () -> this.codec().read(invalid, type));
    }

    @Test
    public void dynamicBinaryValueRecordsItsActualElementForAttachments() throws Exception {
        // Map value 是二进制标量，候选位置必须是动态 key 对应的最终元素。
        Type type = new TypeReference<Map<String, byte[]>>() {
        }.getType();
        Map<String, byte[]> value = Map.of("image", new byte[] { 1, 2, 3 });
        Map<Element, byte[]> binaries = new LinkedHashMap<>();
        Element target = this.target("");

        this.codec().write(target, type, value, binaries);
        Element image = (Element) target.getFirstChild();
        assertEquals("image", image.getLocalName());
        assertArrayEquals(value.get("image"), binaries.get(image));
        Object decodedValue = this.codec().read(target, type);
        @SuppressWarnings("unchecked") Map<String, byte[]> decoded = (Map<String, byte[]>) decodedValue;
        assertArrayEquals(value.get("image"), decoded.get("image"));
    }

    @Test
    public void nullElementMapUsesANilContainer() throws Exception {
        // null Map 的容器使用显式 nil，不等同空 Map。
        Element target = this.target("");

        this.codec().write(target, PlainMap.class, new PlainMap(), null);
        Element container = (Element) target.getFirstChild();
        assertEquals("true", container.getAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "nil"));
        assertNull(((PlainMap) this.codec().read(target, PlainMap.class)).settings);
    }

    @Test
    public void emptyElementMapRetainsAnEmptyContainer() throws Exception {
        // 非null空 Map 必须保留容器，从而与 null 值区分。
        PlainMap value = new PlainMap();
        value.settings = new LinkedHashMap<>();
        Element target = this.target("");

        this.codec().write(target, PlainMap.class, value, null);
        assertFalse(target.getFirstChild().hasChildNodes());
        assertTrue(((PlainMap) this.codec().read(target, PlainMap.class)).settings.isEmpty());
    }

    @Test
    public void nullElementMapEntryRetainsItsKeyWithNilContent() throws Exception {
        // null entry 不应与缺少该 key 混淆。
        PlainMap value = new PlainMap();
        value.settings = new LinkedHashMap<>();
        value.settings.put("missing", null);
        Element target = this.target("");

        this.codec().write(target, PlainMap.class, value, null);
        Map<String, String> decoded = ((PlainMap) this.codec().read(target, PlainMap.class)).settings;
        assertTrue(decoded.containsKey("missing"));
        assertNull(decoded.get("missing"));
    }

    @Test
    public void requiredElementMapAllowsAnEmptyPresentContainer() throws Exception {
        // required 控制 Map 容器是否出现，不强制动态 entry 数量。
        RequiredElementMap value = new RequiredElementMap();
        value.settings = new LinkedHashMap<>();
        Element target = this.target("");

        this.codec().write(target, RequiredElementMap.class, value, null);
        assertEquals("settings", target.getFirstChild().getLocalName());
        assertTrue(((RequiredElementMap) this.codec().read(target, RequiredElementMap.class)).settings.isEmpty());
    }

    @Test
    public void requiredNillableElementMapAllowsANilContainer() throws Exception {
        // required=true 与默认 nillable=true 可组合，显式 nil 容器仍然存在。
        Element target = this.target("");

        this.codec().write(target, RequiredElementMap.class, new RequiredElementMap(), null);
        assertEquals("settings", target.getFirstChild().getLocalName());
        assertNull(((RequiredElementMap) this.codec().read(target, RequiredElementMap.class)).settings);
    }

    @Test
    public void requiredElementMapRejectsAbsentContainer() throws Exception {
        // 缺少整个容器与已出现的空 Map 不同，接收端必须拒绝。
        Element target = this.target("");

        assertThrows(IllegalArgumentException.class, //
                () -> this.codec().read(target, RequiredElementMap.class));
    }

    @Test
    public void nonNillableMapRejectsNilEntriesInBothDirections() throws Exception {
        // nillable=false 同时约束容器及其直接动态 entry。
        NonNillableMap value = new NonNillableMap();
        value.settings = new LinkedHashMap<>();
        value.settings.put("missing", null);
        assertThrows(IllegalArgumentException.class, //
                () -> this.codec().write(this.target(""), NonNillableMap.class, value, null));

        Element target = this.target("");
        Element settings = SoapXmlUtils.append(target, "", "settings");
        Element entry = SoapXmlUtils.append(settings, "", "missing");
        entry.setAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "xsi:nil", "true");
        assertThrows(IllegalArgumentException.class, //
                () -> this.codec().read(target, NonNillableMap.class));
    }

    @Test
    public void dynamicElementNameRejectsNullKey() throws Exception {
        // 动态 key 必须是合法 XML local name，不进行名称修复或强制字符串转换。
        Type type = new TypeReference<Map<String, String>>() {
        }.getType();
        Map<Object, String> value = new LinkedHashMap<>();
        value.put(null, "value");

        assertThrows(IllegalArgumentException.class, //
                () -> this.codec().write(this.target(""), type, value, null));
    }

    @Test
    public void dynamicElementNameRejectsEmptyKey() throws Exception {
        // 动态 key 必须是合法 XML local name，不进行名称修复或强制字符串转换。
        Type type = new TypeReference<Map<String, String>>() {
        }.getType();
        Map<Object, String> value = new LinkedHashMap<>();
        value.put("", "value");

        assertThrows(IllegalArgumentException.class, //
                () -> this.codec().write(this.target(""), type, value, null));
    }

    @Test
    public void dynamicElementNameRejectsWhitespaceKey() throws Exception {
        // 动态 key 必须是合法 XML local name，不进行名称修复或强制字符串转换。
        Type type = new TypeReference<Map<String, String>>() {
        }.getType();
        Map<Object, String> value = new LinkedHashMap<>();
        value.put("with space", "value");

        assertThrows(IllegalArgumentException.class, //
                () -> this.codec().write(this.target(""), type, value, null));
    }

    @Test
    public void dynamicElementNameRejectsColonKey() throws Exception {
        // 动态 key 必须是合法 XML local name，不进行名称修复或强制字符串转换。
        Type type = new TypeReference<Map<String, String>>() {
        }.getType();
        Map<Object, String> value = new LinkedHashMap<>();
        value.put("a:b", "value");

        assertThrows(IllegalArgumentException.class, //
                () -> this.codec().write(this.target(""), type, value, null));
    }

    @Test
    public void dynamicElementNameRejectsLeadingDigitKey() throws Exception {
        // 动态 key 必须是合法 XML local name，不进行名称修复或强制字符串转换。
        Type type = new TypeReference<Map<String, String>>() {
        }.getType();
        Map<Object, String> value = new LinkedHashMap<>();
        value.put("1number", "value");

        assertThrows(IllegalArgumentException.class, //
                () -> this.codec().write(this.target(""), type, value, null));
    }

    @Test
    public void dynamicElementNameRejectsNonStringKey() throws Exception {
        // 动态 key 必须是合法 XML local name，不进行名称修复或强制字符串转换。
        Type type = new TypeReference<Map<String, String>>() {
        }.getType();
        Map<Object, String> value = new LinkedHashMap<>();
        value.put(123, "value");

        assertThrows(IllegalArgumentException.class, //
                () -> this.codec().write(this.target(""), type, value, null));
    }

    @Test
    public void duplicateElementMapKeysAreRejectedOnRead() throws Exception {
        // 重复动态名称不能后值覆盖前值。
        Type type = new TypeReference<Map<String, String>>() {
        }.getType();
        Element target = this.target("urn:settings");
        SoapXmlUtils.append(target, "urn:settings", "timeout").setTextContent("10");
        SoapXmlUtils.append(target, "urn:settings", "timeout").setTextContent("30");

        assertThrows(IllegalArgumentException.class, //
                () -> this.codec().read(target, type));
    }

    @Test
    public void elementMapRejectsEntriesFromAnUnexpectedNamespace() throws Exception {
        // namespace 属于动态 entry 的固定契约。
        Type type = new TypeReference<Map<String, String>>() {
        }.getType();
        Element target = this.target("urn:settings");
        SoapXmlUtils.append(target, "urn:wrong", "timeout").setTextContent("30");

        assertThrows(IllegalArgumentException.class, //
                () -> this.codec().read(target, type));
    }

    @Test
    public void elementMapRejectsDirectContainerText() throws Exception {
        // Map 容器只承载动态子元素，不能静默忽略无 key 的直接文本。
        Type type = new TypeReference<Map<String, String>>() {
        }.getType();
        Element target = this.target("");
        target.setTextContent("unmapped");

        assertThrows(IllegalArgumentException.class, //
                () -> this.codec().read(target, type));
    }

    @Test
    public void attributeMapCapturesDynamicValuesAlongsideStaticAttributes() throws Exception {
        // id 固定声明，其他同 namespace 属性由一个 Map 捕获，name 不成为实际属性名。
        AttributeMap value = new AttributeMap();
        value.id = "fixed";
        value.attributes = new LinkedHashMap<>();
        value.attributes.put("timeout", "30");
        value.attributes.put("mode", "fast");
        Element target = this.target("");

        this.codec().write(target, AttributeMap.class, value, null);
        assertEquals("30", target.getAttribute("timeout"));
        assertFalse(target.hasAttribute("attributes"));
        AttributeMap decoded = (AttributeMap) this.codec().read(target, AttributeMap.class);
        assertEquals("fixed", decoded.id);
        assertEquals(value.attributes, decoded.attributes);
    }

    @Test
    public void objectAttributeMapsWriteMixedScalarsAndReadText() throws Exception {
        // 属性 Map 与元素 Map 共用基础值限制，Object 接收端也只得到文本。
        ObjectAttributes value = new ObjectAttributes();
        value.attributes = new LinkedHashMap<>();
        value.attributes.put("timeout", 30);
        value.attributes.put("enabled", true);
        value.attributes.put("state", State.READY);
        Element target = this.target("");

        this.codec().write(target, ObjectAttributes.class, value, null);
        Map<String, String> expected = Map.of("timeout", "30", "enabled", "true", "state", "ready");
        ObjectAttributes decoded = (ObjectAttributes) this.codec().read(target, ObjectAttributes.class);
        assertEquals(expected, decoded.attributes);
    }

    @Test
    public void objectAttributeMapRejectsActualDtoValue() throws Exception {
        // 属性 Map 的 Object 值也必须是基础值，不写入复杂 Java 对象。
        ObjectAttributes value = new ObjectAttributes();
        value.attributes = Map.of("invalid", new User());

        assertThrows(IllegalArgumentException.class, //
                () -> this.codec().write(this.target(""), ObjectAttributes.class, value, null));
    }

    @Test
    public void objectAttributeMapRejectsActualCollectionValue() throws Exception {
        // 属性 Map 的 Object 值也必须是基础值，不写入复杂 Java 对象。
        ObjectAttributes value = new ObjectAttributes();
        value.attributes = Map.of("invalid", List.of(1));

        assertThrows(IllegalArgumentException.class, //
                () -> this.codec().write(this.target(""), ObjectAttributes.class, value, null));
    }

    @Test
    public void objectAttributeMapRejectsActualNestedMapValue() throws Exception {
        // 属性 Map 的 Object 值也必须是基础值，不写入复杂 Java 对象。
        ObjectAttributes value = new ObjectAttributes();
        value.attributes = Map.of("invalid", Map.of("nested", 30));

        assertThrows(IllegalArgumentException.class, //
                () -> this.codec().write(this.target(""), ObjectAttributes.class, value, null));
    }

    @Test
    public void integerAttributeMapRejectsIncompatibleRuntimeValue() throws Exception {
        // 原始 Map 不能绕过属性 Map 的 Integer 声明。
        IntegerAttributes value = new IntegerAttributes();
        @SuppressWarnings({ "rawtypes", "unchecked" }) Map<String, Integer> invalid = (Map) Map.of("timeout", "text");
        value.attributes = invalid;

        // DTO 交给 Jackson 按声明泛型序列化，错误仍在写出报文前暴露。
        assertThrows(JsonMappingException.class, //
                () -> this.codec().write(this.target(""), IntegerAttributes.class, value, null));
    }

    @Test
    public void integerAttributeMapRejectsEmptyNumericText() throws Exception {
        // wildcard Schema 不猜测动态属性类型，运行时拒绝无效的 Integer 文本。
        Element target = this.target("");
        target.setAttribute("timeout", "");

        assertThrows(IllegalArgumentException.class, //
                () -> this.codec().read(target, IntegerAttributes.class));
    }

    @Test
    public void attributeMapUsesOrderSlotAndPreservesEntryIteration() throws Exception {
        // Map 作为整体占据 order=0，各 key 保持 Map 次序，不能被 DOM 属性表按名字重排。
        OrderedAttributes value = new OrderedAttributes();
        value.first = "first";
        value.last = "last";
        value.attributes = new LinkedHashMap<>();
        value.attributes.put("zeta", "z");
        value.attributes.put("alpha", "a");
        Element target = this.target("");

        this.codec().write(target, OrderedAttributes.class, value, null);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        SoapXmlOutputUtils.write(target.getOwnerDocument(), output);
        String xml = output.toString(StandardCharsets.UTF_8);
        assertTrue(xml.indexOf("first=\"first\"") < xml.indexOf("zeta=\"z\""));
        assertTrue(xml.indexOf("zeta=\"z\"") < xml.indexOf("alpha=\"a\""));
        assertTrue(xml.indexOf("alpha=\"a\"") < xml.indexOf("last=\"last\""));
    }

    @Test
    public void attributeMapCannotOverwriteStaticQNameEvenWhenStaticValueIsNull() throws Exception {
        // 冲突按声明的 QName 校验，不能依赖静态字段是否已写出。
        AttributeMap value = new AttributeMap();
        value.attributes = Map.of("id", "dynamic");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> this.codec().write(this.target(""), AttributeMap.class, value, null));
        assertTrue(failure.getMessage().contains("conflicts"));
    }

    @Test
    public void absentUnwrappedAttributeMapReadsAsEmpty() throws Exception {
        // 无包装属性无法区分 null 和空值，接收端统一得到空 Map。
        Element target = this.target("");

        assertTrue(((AttributeMap) this.codec().read(target, AttributeMap.class)).attributes.isEmpty());
    }

    @Test
    public void attributeNamespacesRemainIndependentAndControlAttributesAreExcluded() throws Exception {
        // 同 localName 的静态/动态属性可拥有不同 namespace，协议属性不进入 Map。
        NamespacedAttributes value = new NamespacedAttributes();
        value.id = "static";
        value.attributes = Map.of("id", "dynamic");
        Element target = this.target("");
        this.codec().write(target, NamespacedAttributes.class, value, null);
        target.setAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "xsi:nil", "false");

        NamespacedAttributes decoded = (NamespacedAttributes) this.codec().read(target, NamespacedAttributes.class);
        assertEquals("static", decoded.id);
        assertEquals(Map.of("id", "dynamic"), decoded.attributes);
    }

    @Test
    public void attributeMapRejectsUnexpectedNamespace() throws Exception {
        // 动态属性只能使用注解声明的 namespace，不能伪装为其他命名空间属性。
        Element target = this.target("");
        target.setAttributeNS("urn:wrong", "wrong:timeout", "30");

        assertThrows(IllegalArgumentException.class, //
                () -> this.codec().read(target, NamespacedAttributes.class));
    }

    @Test
    public void dynamicAttributeCannotDeclareNamespaces() throws Exception {
        // xmlns 是命名空间声明，不允许作为无namespace的动态业务属性写入。
        AttributeMap value = new AttributeMap();
        value.attributes = Map.of("xmlns", "urn:hijack");
        assertThrows(IllegalArgumentException.class, //
                () -> this.codec().write(this.target(""), AttributeMap.class, value, null));
    }

    private SoapLiteralValueCodec codec() {
        return new SoapLiteralValueCodec(new SoapXmlMapping(SoapMessageCodec.newXmlMapper()));
    }

    private Element target(String namespace) {
        Document document = SoapXmlUtils.newDocument();
        Element target = document.createElementNS(namespace, "value");
        document.appendChild(target);
        return target;
    }

    public static class PlainMap {
        public Map<String, String> settings;
    }

    public static class NonNillableMap {
        @MetField
        @JsonSetter(nulls = Nulls.FAIL)
        public Map<String, String> settings;
    }

    public static class RequiredElementMap {
        @MetField
        @JsonProperty(required = true)
        public Map<String, String> settings;
    }

    public enum State {
        @MetEnumValue(name = "ready") READY {
            @Override
            public String toString() {
                return "Java label does not become XML lexical value";
            }
        }
    }

    public static class User {
        @JacksonXmlProperty(isAttribute = true)
        public String id;
    }

    public static class AttributeMap {
        @JacksonXmlProperty(isAttribute = true)
        public String              id;
        @MetField(name = "ignored")
        @JacksonXmlProperty(isAttribute = true)
        public Map<String, String> attributes;
    }

    public static class ObjectAttributes {
        @JacksonXmlProperty(isAttribute = true)
        public Map<String, Object> attributes;
    }

    public static class IntegerAttributes {
        @JacksonXmlProperty(isAttribute = true)
        public Map<String, Integer> attributes;
    }

    public static class OrderedAttributes {
        @JacksonXmlProperty(isAttribute = true)
        @JsonProperty(index = 10)
        public String              last;
        @JacksonXmlProperty(isAttribute = true)
        public Map<String, String> attributes;
        @JacksonXmlProperty(isAttribute = true)
        @JsonProperty(index = -10)
        public String              first;
    }

    public static class NamespacedAttributes {
        @JacksonXmlProperty(isAttribute = true, namespace = "urn:static")
        public String              id;
        @JacksonXmlProperty(isAttribute = true, namespace = "urn:dynamic")
        public Map<String, String> attributes;
    }
}

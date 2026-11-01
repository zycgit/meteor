/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.codec;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import javax.xml.XMLConstants;
import com.fasterxml.jackson.core.type.TypeReference;
import net.hasor.meteor.protocol.soap.definition.SoapXmlUtils;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlMapping;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import static org.junit.jupiter.api.Assertions.*;

public class SoapAnyTypeValueCodecTest {
    @Test
    public void mixedObjectScalarsRetainTheirXmlTypes() throws Exception {
        // 同一个 Object Map 包含不同基础类型，写入值不能决定 Map 的声明类型。
        Type type = new TypeReference<Map<String, Object>>() {
        }.getType();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("byteValue", (byte) 1);
        values.put("shortValue", (short) 2);
        values.put("intValue", 3);
        values.put("longValue", 4L);
        values.put("integerValue", new BigInteger("123456789012345678901234567890"));
        values.put("decimalValue", new BigDecimal("1234.500"));
        values.put("floatValue", Float.POSITIVE_INFINITY);
        values.put("doubleValue", Double.NEGATIVE_INFINITY);
        values.put("booleanValue", false);
        values.put("stringValue", "30");
        Element target = this.target("");

        this.codec().write(target, type, values, null);
        assertEquals(values, this.codec().read(target, type));
    }

    @Test
    public void objectBinaryKeepsItsAttachmentCandidateAndType() throws Exception {
        // Object 中的 byte[] 仍是二进制标量，附件定位不能丢失最终元素。
        byte[] value = { 1, 2, 3 };
        Map<Element, byte[]> binaries = new LinkedHashMap<>();
        Element target = this.target("");

        this.codec().write(target, Object.class, value, binaries);
        assertArrayEquals(value, binaries.get(target));
        assertArrayEquals(value, (byte[]) this.codec().read(target, Object.class));
    }

    @Test
    public void untypedNumericTextRemainsString() throws Exception {
        // 没有 xsi:type 时不猜测文本，避免把业务字符串 30 改成数字。
        Element target = this.target("");
        target.setTextContent("30");

        assertEquals("30", this.codec().read(target, Object.class));
    }

    @Test
    public void emptyObjectStringRemainsEmptyString() throws Exception {
        // 空字符串携带 xs:string，与空复杂结构和 null 分开。
        Element target = this.target("");

        this.codec().write(target, Object.class, "", null);
        assertEquals("", this.codec().read(target, Object.class));
    }

    @Test
    public void nullObjectRemainsNil() throws Exception {
        // Object 的 null 使用标准 xsi:nil，不生成空文本值。
        Element target = this.target("");

        this.codec().write(target, Object.class, null, null);
        assertEquals("true", target.getAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "nil"));
        assertNull(this.codec().read(target, Object.class));
    }

    @Test
    public void emptyObjectMapRemainsAnXmlContainer() throws Exception {
        // 开放复杂值写 xs:anyType，即使没有任何条目仍可保留其 XML 容器。
        Element target = this.target("");

        this.codec().write(target, Object.class, Map.of(), null);
        Element result = (Element) this.codec().read(target, Object.class);
        assertFalse(result.hasChildNodes());
    }

    @Test
    public void objectDateUsesTheExistingLongMapping() throws Exception {
        // Object 恢复 XML 基础类型，不通过协议外类型标记恢复 Java Date。
        Element target = this.target("");

        this.codec().write(target, Object.class, new Date(123456L), null);
        assertEquals(123456L, this.codec().read(target, Object.class));
    }

    @Test
    public void declaredObjectListReadsMixedValuesAndUnknownStructures() throws Exception {
        // List<Object> 每项独立读取类型，未知 DTO 的字段结构保留 DOM。
        Type type = new TypeReference<List<Object>>() {
        }.getType();
        User user = new User();
        user.name = "Alice";
        List<Object> value = Arrays.asList(30, true, user, null);
        Element target = this.target("");

        this.codec().write(target, type, value, null);
        List<?> result = (List<?>) this.codec().read(target, type);
        assertEquals(30, result.get(0));
        assertEquals(true, result.get(1));
        assertEquals("Alice", ((Element) result.get(2)).getFirstChild().getTextContent());
        assertNull(result.get(3));
    }

    @Test
    public void declaredObjectSetUsesSetDeduplication() throws Exception {
        // 声明 Set<Object> 时按已解码值去重，整数与文本仍是两个不同值。
        Type type = new TypeReference<Set<Object>>() {
        }.getType();
        Type sourceType = new TypeReference<List<Object>>() {
        }.getType();
        Element target = this.target("");
        this.codec().write(target, sourceType, List.of(30, 30, "30"), null);

        assertEquals(Set.of(30, "30"), this.codec().read(target, type));
    }

    @Test
    public void nestedListsRetainEachDeclaredContainer() throws Exception {
        // 外层 item 承载一个内层集合，空集合保持一个空 item 容器。
        Type type = new TypeReference<List<List<Integer>>>() {
        }.getType();
        List<List<Integer>> value = List.of(List.of(1, 2), List.of());
        Element target = this.target("");

        this.codec().write(target, type, value, null);
        assertEquals(2, target.getChildNodes().getLength());
        assertEquals("item", target.getFirstChild().getFirstChild().getLocalName());
        assertEquals(value, this.codec().read(target, type));
    }

    @Test
    public void declaredListOfMapsRetainsDynamicNamesAndDtoBindings() throws Exception {
        // List<Map<String, User>> 的每层声明都参与读取，DTO 不退化为 DOM。
        Type type = new TypeReference<List<Map<String, User>>>() {
        }.getType();
        User user = new User();
        user.name = "Alice";
        Element target = this.target("");

        this.codec().write(target, type, List.of(Map.of("alice", user)), null);
        List<?> result = (List<?>) this.codec().read(target, type);
        Map<?, ?> first = (Map<?, ?>) result.get(0);
        assertEquals("Alice", ((User) first.get("alice")).name);
    }

    @Test
    public void dtoObjectPropertyRetainsTheDecodedDomElement() throws Exception {
        // Object 字段直接接收 DOM，不经过 mapper 再次转换为普通 Map。
        Holder value = new Holder();
        User user = new User();
        user.name = "Alice";
        value.value = user;
        Element target = this.target("");

        this.codec().write(target, Holder.class, value, null);
        Holder result = (Holder) this.codec().read(target, Holder.class);
        assertTrue(result.value instanceof Element);
        assertEquals("Alice", ((Element) result.value).getFirstChild().getTextContent());
    }

    @Test
    public void declaredIntegerListRejectsIncompatibleItem() throws Exception {
        // 原始集合传入 String 时也必须遵守 List<Integer>，不能输出非法整数字面量。
        Type type = new TypeReference<List<Integer>>() {
        }.getType();
        Element target = this.target("");

        assertThrows(IllegalArgumentException.class, () -> this.codec().write(target, type, List.of("30"), null));
    }

    @Test
    public void nestedCollectionReadsContractDefinedItemNames() throws Exception {
        // 外部 XSD 可使用自定义条目 QName，声明泛型仍用于解析每个条目的值。
        Type type = new TypeReference<Map<String, List<Integer>>>() {
        }.getType();
        Element target = this.target("");
        Element ports = SoapXmlUtils.append(target, "", "ports");
        SoapXmlUtils.append(ports, "urn:external", "entry").setTextContent("80");

        assertEquals(Map.of("ports", List.of(80)), this.codec().read(target, type));
    }

    @Test
    public void typedObjectScalarRejectsNestedElements() throws Exception {
        // xs:int 不能借开放 Object 路径携带结构并悄悄丢掉内容。
        Element target = this.target("");
        this.xmlType(target, "int");
        SoapXmlUtils.append(target, "", "value").setTextContent("30");

        assertThrows(IllegalArgumentException.class, () -> this.codec().read(target, Object.class));
    }

    @Test
    public void typedObjectScalarRejectsBusinessAttributes() throws Exception {
        // 简单类型不允许带业务属性，避免标量解码丢弃这些属性。
        Element target = this.target("");
        this.xmlType(target, "int");
        target.setAttribute("unit", "seconds");
        target.setTextContent("30");

        assertThrows(IllegalArgumentException.class, () -> this.codec().read(target, Object.class));
    }

    @Test
    public void typedObjectBooleanRejectsInvalidText() throws Exception {
        // 标准 boolean 只接受 true/false/1/0，不继承 JSON 的宽松强制转换。
        Element target = this.target("");
        this.xmlType(target, "boolean");
        target.setTextContent("yes");

        assertThrows(IllegalArgumentException.class, () -> this.codec().read(target, Object.class));
    }

    @Test
    public void typedObjectRejectsAnUnboundTypePrefix() throws Exception {
        // xsi:type 必须是能解析的 QName，不允许只取冒号后的字符串猜类型。
        Element target = this.target("");
        target.setAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "xsi:type", "missing:int");
        target.setTextContent("30");

        assertThrows(IllegalArgumentException.class, () -> this.codec().read(target, Object.class));
    }

    @Test
    public void unknownTypeRetainsItsAncestorNamespaceAndAttributes() throws Exception {
        // 类型前缀只在祖先声明，脱离源文档后仍要能解释 QName 属性。
        Element root = this.target("");
        root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:m", "urn:types");
        Element source = SoapXmlUtils.append(root, "", "value");
        source.setAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "xsi:type", "m:User");
        source.setAttribute("id", "123");

        Element result = (Element) this.codec().read(source, Object.class);
        root.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:m", "urn:changed");
        assertEquals("urn:types", result.lookupNamespaceURI("m"));
        assertEquals("m:User", result.getAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "type"));
        assertEquals("123", result.getAttribute("id"));
        assertNotSame(source.getOwnerDocument(), result.getOwnerDocument());
    }

    @Test
    public void xmlLanguageAttributePreventsLossyStringFallback() throws Exception {
        // xml:lang 也是内容的一部分，不能因为它不是业务属性就丢掉。
        Element source = this.target("");
        source.setAttributeNS(XMLConstants.XML_NS_URI, "xml:lang", "zh-CN");
        source.setTextContent("正文");

        Element result = (Element) this.codec().read(source, Object.class);
        assertEquals("zh-CN", result.getAttributeNS(XMLConstants.XML_NS_URI, "lang"));
        assertEquals("正文", result.getTextContent());
    }

    @Test
    public void domRoundTripRetainsMixedContentAndRepeatedChildren() throws Exception {
        // XML 树包含混合文本、CDATA、注释和重复子元素，不能压缩成键值 Map。
        Element source = this.target("");
        Document document = source.getOwnerDocument();
        source.setAttribute("id", "123");
        source.appendChild(document.createTextNode("before"));
        SoapXmlUtils.append(source, "", "part").setTextContent("first");
        source.appendChild(document.createCDATASection("<middle>"));
        source.appendChild(document.createComment("comment"));
        SoapXmlUtils.append(source, "", "part").setTextContent("last");
        Element target = this.target("");

        Object decoded = this.codec().read(source, Object.class);
        this.codec().write(target, Object.class, decoded, null);
        assertEquals("123", target.getAttribute("id"));
        assertEquals(5, target.getChildNodes().getLength());
        assertEquals(Node.TEXT_NODE, target.getChildNodes().item(0).getNodeType());
        assertEquals(Node.CDATA_SECTION_NODE, target.getChildNodes().item(2).getNodeType());
        assertEquals(Node.COMMENT_NODE, target.getChildNodes().item(3).getNodeType());
        assertEquals("beforefirst<middle>last", target.getTextContent());
        assertEquals(5, source.getChildNodes().getLength());
    }

    @Test
    public void emptyDomRemainsAnOpenXmlElement() throws Exception {
        // 显式 DOM 即使为空仍是复杂值，不应在回读时退化为字符串。
        Element source = this.target("");
        Element target = this.target("");

        this.codec().write(target, Object.class, source, null);
        Element decoded = (Element) this.codec().read(target, Object.class);
        assertFalse(decoded.hasChildNodes());
    }

    @Test
    public void untypedCommentRetainsItsXmlStructure() throws Exception {
        // 纯文本旁的注释也属于开放 XML 结构，不能因没有子元素就丢弃。
        Element source = this.target("");
        source.setTextContent("text");
        source.appendChild(source.getOwnerDocument().createComment("comment"));

        Element decoded = (Element) this.codec().read(source, Object.class);
        assertEquals(Node.COMMENT_NODE, decoded.getLastChild().getNodeType());
        assertEquals("text", decoded.getTextContent());
    }

    @Test
    public void domRootQNameBindingSurvivesCarrierReplacement() throws Exception {
        // xs 前缀仅来自源元素 QName，没有 xmlns 属性，替换载体后仍须保留类型绑定。
        Document document = SoapXmlUtils.newDocument();
        Element source = document.createElementNS(XMLConstants.W3C_XML_SCHEMA_NS_URI, "xs:source");
        document.appendChild(source);
        source.setAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "xsi:type", "xs:anyType");
        Element target = this.target("");

        this.codec().write(target, Object.class, source, null);
        assertEquals(XMLConstants.W3C_XML_SCHEMA_NS_URI, target.lookupNamespaceURI("xs"));
        assertTrue(this.codec().read(target, Object.class) instanceof Element);
    }

    @Test
    public void domAncestorQNameBindingSurvivesDetachment() throws Exception {
        // 祖先 QName 的隐式绑定也属于源节点的作用域，脱离文档后不能丢失。
        Document document = SoapXmlUtils.newDocument();
        Element ancestor = document.createElementNS("urn:types", "m:ancestor");
        document.appendChild(ancestor);
        Element source = SoapXmlUtils.append(ancestor, "", "source");
        source.setAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "xsi:type", "m:User");

        Element decoded = (Element) this.codec().read(source, Object.class);
        assertEquals("urn:types", decoded.lookupNamespaceURI("m"));
        assertEquals("m:User", decoded.getAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "type"));
    }

    @Test
    public void domNearestExplicitBindingOverridesAnAncestorQName() throws Exception {
        // 子节点最近的显式声明优先于祖先元素 QName 的隐式绑定。
        Document document = SoapXmlUtils.newDocument();
        Element ancestor = document.createElementNS("urn:ancestor", "m:ancestor");
        document.appendChild(ancestor);
        Element source = SoapXmlUtils.append(ancestor, "", "source");
        source.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:m", "urn:nearest");
        source.setAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "xsi:type", "m:User");

        Element decoded = (Element) this.codec().read(source, Object.class);
        assertEquals("urn:nearest", decoded.lookupNamespaceURI("m"));
    }

    @Test
    public void domPrefixCollisionRenamesOnlyTheTargetCarrier() throws Exception {
        // 源属性中的 QName 不可盲目改写；冲突时仅调整目标载体的前缀。
        Element source = this.target("");
        source.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:p", "urn:source");
        source.setAttribute("kind", "p:User");
        Element target = this.target("urn:target");
        target.setPrefix("p");
        target.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:p", "urn:target");

        this.codec().write(target, Object.class, source, null);
        assertEquals("urn:target", target.getNamespaceURI());
        assertEquals("urn:target", target.lookupNamespaceURI(target.getPrefix()));
        assertEquals("urn:source", target.lookupNamespaceURI("p"));
        assertEquals("p:User", target.getAttribute("kind"));
    }

    @Test
    public void domDefaultNamespaceRemainsAvailableForQNameContent() throws Exception {
        // 默认 namespace 不属于原元素名独占，QName 文本也可能依赖它。
        Element source = this.target("urn:source");
        source.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns", "urn:source");
        source.setAttribute("kind", "User");
        Element target = this.target("urn:target");

        this.codec().write(target, Object.class, source, null);
        assertEquals("urn:source", target.lookupNamespaceURI(null));
        assertEquals("urn:target", target.lookupNamespaceURI(target.getPrefix()));
        assertEquals("User", target.getAttribute("kind"));
    }

    @Test
    public void domRejectsUnrepresentableDefaultNamespaceOnUnqualifiedTarget() throws Exception {
        // 目标无 namespace 时，不能同时挂上非空默认 namespace 并保留目标 QName。
        Element source = this.target("urn:source");
        source.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns", "urn:source");
        source.setAttribute("kind", "User");
        Element target = this.target("");

        assertThrows(IllegalArgumentException.class, () -> this.codec().write(target, Object.class, source, null));
    }

    @Test
    public void objectMapRejectsCircularReference() throws Exception {
        // 动态 Object Map 的递归展开必须共享循环检测状态。
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("self", value);
        Element target = this.target("");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            this.codec().write(target, Object.class, value, null);
        });
        assertTrue(failure.getMessage().contains("circular"));
    }

    @Test
    public void objectListRejectsCircularReference() throws Exception {
        // Object 集合不能在递归时重建检测状态，漏掉自身引用。
        List<Object> value = new ArrayList<>();
        value.add(value);
        Element target = this.target("");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            this.codec().write(target, Object.class, value, null);
        });
        assertTrue(failure.getMessage().contains("circular"));
    }

    @Test
    public void objectDomReadRejectsExcessiveDepthBeforeCopying() {
        // DOM fallback 也服从同一深度限制，不允许复制超深的开放内容。
        Element source = this.deepElement();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            this.codec().read(source, Object.class);
        });
        assertTrue(failure.getMessage().contains("depth"));
    }

    @Test
    public void objectDomWriteRejectsExcessiveDepthBeforeCopying() {
        // 直接传入 DOM 也不能绕过深度限制。
        Element source = this.deepElement();
        Element target = this.target("");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            this.codec().write(target, Object.class, source, null);
        });
        assertTrue(failure.getMessage().contains("depth"));
    }

    @Test
    public void objectDomRejectsProcessingInstructions() {
        // SOAP 消息不允许处理指令，不能通过开放 DOM 生成本端无法接收的报文。
        Element source = this.target("");
        source.appendChild(source.getOwnerDocument().createProcessingInstruction("target", "data"));
        Element target = this.target("");

        assertThrows(IllegalArgumentException.class, () -> this.codec().write(target, Object.class, source, null));
    }

    @Test
    public void objectDomRejectsEntityReferences() {
        // 应用提供的 DOM 不能绕过 SOAP 解析器的实体限制。
        Element source = this.target("");
        source.appendChild(source.getOwnerDocument().createEntityReference("external"));
        Element target = this.target("");

        assertThrows(IllegalArgumentException.class, () -> this.codec().write(target, Object.class, source, null));
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

    private void xmlType(Element target, String local) {
        target.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:xs", XMLConstants.W3C_XML_SCHEMA_NS_URI);
        target.setAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "xsi:type", "xs:" + local);
    }

    private Element deepElement() {
        Element source = this.target("");
        Element last = source;
        for (int depth = 0; depth < 130; depth++) {
            last = SoapXmlUtils.append(last, "", "item");
        }
        return source;
    }

    public static class User {
        public String name;
    }

    public static class Holder {
        public Object value;
    }
}

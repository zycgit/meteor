/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.unit;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import net.hasor.meteor.protocol.soap.codec.SoapBodyCodecAccess;
import org.junit.Test;
import org.w3c.dom.Element;
import static org.junit.Assert.*;

public class SoapBodyCodecTest {
    private static final QName WRAPPER = new QName("urn:test:body", "call");
    private static final QName VALUE   = new QName("value");
    private static final QName OTHER   = new QName("other");
    private static final QName STRING  = new QName(XMLConstants.W3C_XML_SCHEMA_NS_URI, "string");
    private static final Type  STRINGS = new TypeReference<List<String>>() {
    }.getType();

    @Test
    public void barePartsMapToParallelJavaArguments() throws Exception {
        // document/literal bare 的两个 part 直接位于 Body，按各自完整 QName 映射。
        SoapBodyCodecAccess codec = SoapBodyCodecAccess.literal(null) //
                .part(new QName("urn:first", "value"), STRING, 1, 1) //
                .part(new QName("urn:second", "value"), STRING, 1, 1);
        String first = "<a:value xmlns:a='urn:first'>a</a:value>";
        String second = "<b:value xmlns:b='urn:second'>b</b:value>";
        Element body = this.body(first + second);

        // 同一 localName 不会让两个 Java 参数读取相同元素。
        assertArrayEquals(new Object[] { "a", "b" }, codec.read(body, String.class, String.class));
    }

    @Test
    public void absentOptionalScalarMapsToNull() throws Exception {
        // 可选 part 缺省，另一个必需 part 仍正常存在。
        SoapBodyCodecAccess codec = SoapBodyCodecAccess.literal(WRAPPER) //
                .part(VALUE, STRING, 0, 1) //
                .part(OTHER, STRING, 1, 1);
        Element body = this.wrapped("<other>present</other>");

        // 缺省参数映射为 null，不影响相邻参数位置。
        assertArrayEquals(new Object[] { null, "present" }, codec.read(body, String.class, String.class));
    }

    @Test
    public void repeatedElementsMapToTypedCollection() throws Exception {
        // 同一 part 在正文出现多次，对应一个有明确元素类型的集合参数。
        SoapBodyCodecAccess codec = SoapBodyCodecAccess.literal(WRAPPER).part(VALUE, STRING, 0, -1);
        Element body = this.wrapped("<value>a</value><value>b</value>");

        // 重复顺序保留在集合中。
        assertEquals(List.of("a", "b"), codec.read(body, STRINGS)[0]);
    }

    @Test
    public void repeatedElementsMapToPrimitiveArray() throws Exception {
        // 重复数值 part 可以映射为 Java primitive 数组。
        QName integer = new QName(XMLConstants.W3C_XML_SCHEMA_NS_URI, "int");
        SoapBodyCodecAccess codec = SoapBodyCodecAccess.literal(WRAPPER).part(VALUE, integer, 1, -1);
        Element body = this.wrapped("<value>3</value><value>5</value>");

        // 反序列化保留数组元素的具体类型与顺序。
        assertArrayEquals(new int[] { 3, 5 }, (int[]) codec.read(body, int[].class)[0]);
    }

    @Test
    public void absentRepeatedPartMapsToEmptyCollection() throws Exception {
        // 普通 minOccurs=0 的重复 part 可以完全缺省。
        SoapBodyCodecAccess codec = SoapBodyCodecAccess.literal(WRAPPER).part(VALUE, STRING, 0, -1);

        // 无元素表示空集合，与 choice 未选择分支的 null 区分。
        assertEquals(List.of(), codec.read(this.wrapped(""), STRINGS)[0]);
    }

    @Test
    public void nilCannotMapToAPrimitiveParameter() throws Exception {
        // primitive 形参没有 null 表示，不能把显式 nil 推迟到反射调用时才报错。
        QName integer = new QName(XMLConstants.W3C_XML_SCHEMA_NS_URI, "int");
        SoapBodyCodecAccess codec = SoapBodyCodecAccess.literal(WRAPPER).part(VALUE, integer, 1, 1);
        Element body = this.wrapped("<value xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance' xsi:nil='true'/>");

        // 正文解码阶段就暴露 Java 类型与空值的不一致。
        RuntimeException failure = assertThrows(IllegalArgumentException.class, () -> codec.read(body, int.class));
        assertTrue(failure.getMessage().contains("primitive"));
    }

    @Test
    public void tooFewRepeatedElementsAreRejected() throws Exception {
        // 契约要求至少两个重复元素，正文只提供一个。
        SoapBodyCodecAccess codec = SoapBodyCodecAccess.literal(WRAPPER).part(VALUE, STRING, 2, -1);
        Element body = this.wrapped("<value>one</value>");

        // 最小次数不能因 Java 参数是集合而丢失。
        RuntimeException failure = assertThrows(IllegalArgumentException.class, () -> codec.read(body, STRINGS));
        assertTrue(failure.getMessage().contains("too few"));
    }

    @Test
    public void tooManyRepeatedElementsAreRejected() throws Exception {
        // 契约最多允许两个重复元素，正文包含三个。
        SoapBodyCodecAccess codec = SoapBodyCodecAccess.literal(WRAPPER).part(VALUE, STRING, 0, 2);
        Element body = this.wrapped("<value>a</value><value>b</value><value>c</value>");

        // 超量元素不能静默截断。
        RuntimeException failure = assertThrows(IllegalArgumentException.class, () -> codec.read(body, STRINGS));
        assertTrue(failure.getMessage().contains("too many"));
    }

    @Test
    public void repeatedPartRequiresAConcreteElementType() throws Exception {
        // 原始 List 没有声明元素类型，不能确定每个重复 part 如何解码。
        SoapBodyCodecAccess codec = SoapBodyCodecAccess.literal(WRAPPER).part(VALUE, STRING, 0, -1);
        Element body = this.wrapped("<value>a</value>");

        // 不把未知集合元素默认为任意 Object。
        RuntimeException failure = assertThrows(IllegalArgumentException.class, () -> codec.read(body, List.class));
        assertTrue(failure.getMessage().contains("typed collection"));
    }

    @Test
    public void oneChoiceBranchLeavesTheOtherCollectionNull() throws Exception {
        // 一个分支为重复集合，正文只选择另一个标量分支。
        SoapBodyCodecAccess codec = SoapBodyCodecAccess.literal(WRAPPER) //
                .part(VALUE, STRING, 0, -1) //
                .part(OTHER, STRING, 0, 1) //
                .choice(1, 0, 1);
        Element body = this.wrapped("<other>selected</other>");

        // 未选集合保持 null，避免回写时凭空选择该分支。
        assertArrayEquals(new Object[] { null, "selected" }, codec.read(body, STRINGS, String.class));
    }

    @Test
    public void requiredChoiceRejectsAnAbsentSelection() throws Exception {
        // choice 的各分支都可缺省，但整个组要求选择一次。
        SoapBodyCodecAccess codec = this.choice();
        Element body = this.wrapped("");

        // 组约束独立于每个 part 的最小次数。
        assertThrows(IllegalArgumentException.class, () -> codec.read(body, String.class, String.class));
    }

    @Test
    public void choiceRejectsTwoSelectedBranches() throws Exception {
        // 必选 choice 中两个不同分支同时出现。
        SoapBodyCodecAccess codec = this.choice();
        Element body = this.wrapped("<value>a</value><other>b</other>");

        // 不把同时存在的分支当作普通 sequence。
        assertThrows(IllegalArgumentException.class, () -> codec.read(body, String.class, String.class));
    }

    @Test
    public void writeChoiceRejectsTwoNonNullArguments() throws Exception {
        // Java 参数同时提供两个 choice 分支。
        SoapBodyCodecAccess codec = this.choice();
        Element body = this.body("");
        Type[] types = { String.class, String.class };
        Object[] values = { "a", "b" };

        // 在写出任何包装元素前拒绝歧义选择。
        assertThrows(IllegalArgumentException.class, () -> codec.write(body, types, values, new LinkedHashMap<>()));
        assertFalse(body.hasChildNodes());
    }

    @Test
    public void writeOmitsOptionalNullAndRepeatsCollectionItems() throws Exception {
        // 可选标量缺省，重复 part 的集合含两个值。
        SoapBodyCodecAccess codec = SoapBodyCodecAccess.literal(WRAPPER) //
                .part(OTHER, STRING, 0, 1) //
                .part(VALUE, STRING, 0, -1);
        Element body = this.body("");
        Type[] types = { String.class, STRINGS };
        Object[] values = { null, List.of("a", "b") };

        // 只写存在的 part，重复元素保持同级且可以按相同契约还原。
        codec.write(body, types, values, new LinkedHashMap<>());
        assertEquals(0, body.getElementsByTagName("other").getLength());
        assertEquals(2, body.getElementsByTagName("value").getLength());
        assertArrayEquals(values, codec.read(body, types));
    }

    @Test
    public void allFieldsMayAppearInAnotherOrder() throws Exception {
        // xs:all 的两个标量字段允许按与声明不同的顺序出现。
        SoapBodyCodecAccess codec = SoapBodyCodecAccess.literal(WRAPPER) //
                .part(VALUE, STRING, 1, 1) //
                .part(OTHER, STRING, 1, 1);
        Element body = this.wrapped("<other>b</other><value>a</value>");

        // 值映射按 QName 查找，完整 compositor 约束由契约 Schema 校验。
        assertArrayEquals(new Object[] { "a", "b" }, codec.read(body, String.class, String.class));
    }

    @Test
    public void wrongPartNamespaceIsRejected() throws Exception {
        // 同名字段来自错误命名空间，不能按 localName 降级匹配。
        SoapBodyCodecAccess codec = SoapBodyCodecAccess.literal(WRAPPER).part(VALUE, STRING, 1, 1);
        Element body = this.wrapped("<x:value xmlns:x='urn:wrong'>a</x:value>");

        // 错误字段在 Java 反序列化前被拒绝。
        RuntimeException failure = assertThrows(IllegalArgumentException.class, () -> codec.read(body, String.class));
        assertTrue(failure.getMessage().contains("Unexpected SOAP part"));
    }

    @Test
    public void literalWrapperRejectsAdditionalBodyElements() throws Exception {
        // literal 包装消息之外额外插入一个看似多引用定义的元素。
        SoapBodyCodecAccess codec = SoapBodyCodecAccess.literal(WRAPPER).part(VALUE, STRING, 1, 1);
        Element body = this.body("<m:call xmlns:m='urn:test:body'><value>a</value></m:call><multiRef id='x'/>");

        // encoded 的多引用例外不能用于放宽 literal Body。
        assertThrows(IllegalArgumentException.class, () -> codec.read(body, String.class));
    }

    @Test
    public void encodedBareBodyRetainsItsSoap11ReferenceDefinitions() throws Exception {
        // encoded bare 数组会产生 accessor 与 Body 直属多引用定义。
        SoapBodyCodecAccess codec = SoapBodyCodecAccess.encoded(null, false).part(VALUE, null, 1, 1);
        Element body = this.body("");
        Type[] types = { String[].class };
        Object[] values = { new String[] { "a", "b" } };

        // BodyCodec 只映射声明的 part，多引用定义交给 encoded 解码器。
        codec.write(body, types, values, new LinkedHashMap<>());
        assertEquals(1, body.getElementsByTagName("multiRef").getLength());
        assertArrayEquals((String[]) values[0], (String[]) codec.read(body, types)[0]);
    }

    @Test
    public void encodedWrapperRetainsItsSoap12ReferenceDefinitions() throws Exception {
        // 独立对端用 enc:ref 把 wrapper 参数指向 Body 中的数组定义。
        SoapBodyCodecAccess codec = SoapBodyCodecAccess.encoded(WRAPPER, true).part(VALUE, null, 1, 1);
        String xml = """
                <s:Body xmlns:s="http://www.w3.org/2003/05/soap-envelope"
                        xmlns:enc="http://www.w3.org/2003/05/soap-encoding"
                        xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:m="urn:test:body">
                    <m:call><value enc:ref="items"/></m:call>
                    <multiRef enc:id="items" enc:nodeType="array" enc:itemType="xsd:string" enc:arraySize="2">
                        <item>a</item><item>b</item>
                    </multiRef>
                </s:Body>
                """;
        Element body = this.parse(xml);

        // 定义节点不被当成额外参数，客户端读出引用数组的实际值。
        Type[] types = { String[].class };
        assertArrayEquals(new String[] { "a", "b" }, (String[]) codec.read(body, types)[0]);
    }

    private SoapBodyCodecAccess choice() {
        return SoapBodyCodecAccess.literal(WRAPPER) //
                .part(VALUE, STRING, 0, 1) //
                .part(OTHER, STRING, 0, 1) //
                .choice(1, 0, 1);
    }

    private Element wrapped(String fields) throws Exception {
        return this.body("<m:call xmlns:m='urn:test:body'>" + fields + "</m:call>");
    }

    private Element body(String content) throws Exception {
        return this.parse("<s:Body xmlns:s='http://schemas.xmlsoap.org/soap/envelope/'>" + content + "</s:Body>");
    }

    private Element parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes)).getDocumentElement();
    }
}

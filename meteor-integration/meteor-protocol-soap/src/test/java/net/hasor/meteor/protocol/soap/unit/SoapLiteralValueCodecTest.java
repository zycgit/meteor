/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.unit;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import net.hasor.meteor.protocol.soap.codec.SoapBodyCodecAccess;
import net.hasor.meteor.protocol.soap.unit.support.LiteralBinaryAttribute;
import net.hasor.meteor.protocol.soap.unit.support.LiteralBinaryBean;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import static org.junit.Assert.*;

public class SoapLiteralValueCodecTest {
    @Test
    public void rootBinaryRetainsItsFinalDocumentElement() throws Exception {
        // 根参数为真正的 byte[]，目标元素已经属于最终 SOAP 文档。
        Element target = this.target();
        byte[] value = { 1, 2, 3 };
        Map<Element, byte[]> binaries = new LinkedHashMap<>();

        // 内联 XML 与附件候选使用同一内容，候选不引用临时序列化文档。
        SoapBodyCodecAccess.writeLiteral(target, byte[].class, value, binaries);
        assertEquals("AQID", target.getTextContent());
        assertEquals(1, binaries.size());
        assertArrayEquals(value, binaries.get(target));
        assertArrayEquals(value, (byte[]) SoapBodyCodecAccess.readLiteral(target, byte[].class));
    }

    @Test
    public void beanBinaryFieldsAreCapturedWithoutReReadingGetters() throws Exception {
        // 普通字符串与二进制属性具有相同 base64 文本，集合内也包含真正的二进制。
        LiteralBinaryBean value = new LiteralBinaryBean();
        value.setData(new byte[] { 1, 2, 3 });
        value.setText("AQID");
        value.setChunks(List.of(new byte[] { 4, 5 }, new byte[] { 6 }));
        Element target = this.target();
        Map<Element, byte[]> binaries = new LinkedHashMap<>();

        // 只有真实 byte[] 对应的三个元素被标记，读取业务属性只发生在序列化阶段。
        Type beanType = LiteralBinaryBean.class;
        SoapBodyCodecAccess.writeLiteral(target, beanType, value, binaries);
        assertEquals(3, binaries.size());
        assertEquals(1, value.getDataReads());
        Element text = (Element) target.getElementsByTagName("text").item(0);
        assertEquals("AQID", text.getTextContent());
        assertFalse(binaries.containsKey(text));
        for (Element binary : binaries.keySet()) {
            assertSame(target.getOwnerDocument(), binary.getOwnerDocument());
            assertTrue(target.isSameNode(binary.getParentNode()));
        }

        // 恢复后的正文仍能按同一 Java 类型解码。
        LiteralBinaryBean decoded = (LiteralBinaryBean) SoapBodyCodecAccess.readLiteral(target, beanType);
        assertArrayEquals(new byte[] { 1, 2, 3 }, decoded.getData());
        assertEquals("AQID", decoded.getText());
        assertArrayEquals(new byte[] { 4, 5 }, decoded.getChunks().get(0));
        assertArrayEquals(new byte[] { 6 }, decoded.getChunks().get(1));
    }

    @Test
    public void base64TextIsNotAnAttachmentCandidate() throws Exception {
        // 普通字符串恰好是合法 base64，也不能据此推断二进制类型。
        Element target = this.target();
        Map<Element, byte[]> binaries = new LinkedHashMap<>();

        // 原文本保持不变，附件候选为空。
        SoapBodyCodecAccess.writeLiteral(target, String.class, "AQID", binaries);
        assertEquals("AQID", target.getTextContent());
        assertTrue(binaries.isEmpty());
    }

    @Test
    public void binaryXmlAttributeRemainsInline() throws Exception {
        // 属性使用 byte[] 值，但 MTOM 只允许替换元素内容。
        LiteralBinaryAttribute value = new LiteralBinaryAttribute();
        value.setData(new byte[] { 1, 2, 3 });
        Element target = this.target();
        Map<Element, byte[]> binaries = new LinkedHashMap<>();

        // 目标元素保留序列化属性并恢复 base64，不制造附件候选。
        SoapBodyCodecAccess.writeLiteral(target, LiteralBinaryAttribute.class, value, binaries);
        assertEquals("AQID", target.getAttribute("data"));
        assertTrue(binaries.isEmpty());
    }

    @Test
    public void nullLiteralRemainsNil() throws Exception {
        // 必需元素可以显式携带 xsi:nil，而不是消失。
        Element target = this.target();

        // 空值编码与解码保持一致。
        SoapBodyCodecAccess.writeLiteral(target, String.class, null, new LinkedHashMap<>());
        assertEquals("true", target.getAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "nil"));
        assertNull(SoapBodyCodecAccess.readLiteral(target, String.class));
    }

    @Test
    public void emptyLiteralCollectionRemainsEmpty() throws Exception {
        // 集合类型的单个 XML 元素内部没有 item，区别于重复 part 的缺省。
        Element target = this.target();
        Type type = new TypeReference<List<String>>() {
        }.getType();

        // 空集合不退化为 null。
        SoapBodyCodecAccess.writeLiteral(target, type, List.of(), new LinkedHashMap<>());
        assertEquals(List.of(), SoapBodyCodecAccess.readLiteral(target, type));
    }

    private Element target() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder().newDocument();
        Element target = document.createElementNS("urn:test:values", "value");
        document.appendChild(target);
        return target;
    }
}

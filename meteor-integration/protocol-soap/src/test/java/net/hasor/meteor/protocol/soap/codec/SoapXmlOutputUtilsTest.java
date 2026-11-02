/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.codec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import net.hasor.meteor.annotation.MetField;
import net.hasor.meteor.protocol.soap.definition.SoapVersion;
import net.hasor.meteor.protocol.soap.definition.SoapXmlUtils;
import net.hasor.meteor.protocol.soap.mapping.SoapXmlMapping;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import static org.junit.jupiter.api.Assertions.*;

public class SoapXmlOutputUtilsTest {
    private static final String ATTRIBUTES = "urn:test:ordered-attributes";
    private static final int    MAX_BYTES  = 64 * 1024;

    @Test
    public void literalAttributeOrderIsPreservedInFinalXmlBytes() throws Exception {
        // 属性名与 order 反向，命名空间属性还包含转义字符和需保留的空白。
        OrderedValue value = this.value();
        Document document = SoapXmlUtils.newDocument();
        Element envelope = document.createElementNS(SoapVersion.SOAP_11.namespace(), "soap:Envelope");
        document.appendChild(envelope);
        Element body = SoapXmlUtils.append(envelope, SoapVersion.SOAP_11.namespace(), "soap:Body");
        Element target = SoapXmlUtils.append(body, "", "value");
        SoapLiteralValueCodec codec = new SoapLiteralValueCodec(new SoapXmlMapping(SoapMessageCodec.newXmlMapper()));
        codec.write(target, OrderedValue.class, value, new LinkedHashMap<>());

        // 最终发送字节按负数、默认 0、正数排列，不能退回 DOM 的属性名字典序。
        SoapAttachmentCodec.Message message = SoapAttachmentCodec.encode(document, Map.of(), //
                SoapVersion.SOAP_11, null, false, MAX_BYTES);
        String wire = new String(message.body(), StandardCharsets.UTF_8);
        int negative = wire.indexOf(" zeta=\"");
        int defaultOrder = wire.indexOf(":mu=\"");
        int positive = wire.indexOf(" alpha=\"");
        assertTrue(negative >= 0, wire);
        assertTrue(defaultOrder > negative, wire);
        assertTrue(positive > defaultOrder, wire);
        assertTrue(wire.contains("&amp;"), wire);
        assertTrue(wire.contains("&quot;"), wire);
        assertTrue(wire.contains("&lt;"), wire);

        // 重新解析最终 XML，属性的 QName、换行、回车和制表符均保持原值。
        Element restored = (Element) this.parse(message.body()).getElementsByTagName("value").item(0);
        assertEquals(value.zero, restored.getAttributeNS(ATTRIBUTES, "mu"));
        assertEquals(value.negative, restored.getAttribute("zeta"));
        assertEquals(value.positive, restored.getAttribute("alpha"));
    }

    @Test
    public void encodedAttributeOrderIsPreservedInFinalXmlBytes() throws Exception {
        // SOAP Encoding 的业务属性应在实际图节点上保持数字顺序。
        OrderedValue value = this.value();
        Document document = SoapXmlUtils.newDocument();
        Element envelope = document.createElementNS(SoapVersion.SOAP_11.namespace(), "soap:Envelope");
        document.appendChild(envelope);
        Element body = SoapXmlUtils.append(envelope, SoapVersion.SOAP_11.namespace(), "soap:Body");
        SoapXmlMapping mapping = new SoapXmlMapping(SoapMessageCodec.newXmlMapper());
        SoapEncodedValueCodec codec = new SoapEncodedValueCodec(SoapVersion.SOAP_11, mapping);
        SoapEncodedValueCodec.Encoder encoder = codec.encoder(document, body);
        encoder.write(body, new QName("value"), null, OrderedValue.class, value);
        encoder.finish();

        // 读取最终 XML 原文验证属性位置，不从会重排属性的 DOM 推断文字顺序。
        SoapAttachmentCodec.Message message = SoapAttachmentCodec.encode(document, Map.of(), //
                SoapVersion.SOAP_11, null, false, MAX_BYTES);
        String wire = new String(message.body(), StandardCharsets.UTF_8);
        int negative = wire.indexOf(" zeta=\"");
        int defaultOrder = wire.indexOf(":mu=\"");
        int positive = wire.indexOf(" alpha=\"");
        assertTrue(negative >= 0, wire);
        assertTrue(defaultOrder > negative, wire);
        assertTrue(positive > defaultOrder, wire);

        // 图节点上的属性命名空间和原值也必须仍可正常解析。
        Document restored = this.parse(message.body());
        Element graph = (Element) restored.getElementsByTagName("multiRef").item(0);
        assertEquals(value.zero, graph.getAttributeNS(ATTRIBUTES, "mu"));
    }

    @Test
    public void mtomClonePreservesAttributeOrderInTheRootMimeXml() throws Exception {
        // 二进制字段使附件编码器实际走克隆 DOM 并替换 XOP 节点的路径。
        OrderedValue value = this.value();
        value.binary = new byte[] { 0, (byte) 0xff, 1, 2 };
        Document document = SoapXmlUtils.newDocument();
        Element envelope = document.createElementNS(SoapVersion.SOAP_11.namespace(), "soap:Envelope");
        document.appendChild(envelope);
        Element body = SoapXmlUtils.append(envelope, SoapVersion.SOAP_11.namespace(), "soap:Body");
        Element target = SoapXmlUtils.append(body, "", "value");
        SoapLiteralValueCodec codec = new SoapLiteralValueCodec(new SoapXmlMapping(SoapMessageCodec.newXmlMapper()));
        Map<Element, byte[]> binaries = new LinkedHashMap<>();
        codec.write(target, OrderedValue.class, value, binaries);
        assertEquals(1, binaries.size());

        // 直接提取发送 MIME 包的根 XML，避免解码重建 DOM 掩盖词法顺序变化。
        SoapAttachmentCodec.Message message = SoapAttachmentCodec.encode(document, binaries, //
                SoapVersion.SOAP_11, null, true, MAX_BYTES);
        MimeMultipart multipart = new MimeMultipart(new ByteArrayDataSource(message.body(), message.contentType()));
        assertEquals(2, multipart.getCount());
        byte[] rootXml;
        try (InputStream input = multipart.getBodyPart(0).getInputStream()) {
            rootXml = input.readAllBytes();
        }
        String wire = new String(rootXml, StandardCharsets.UTF_8);
        int negative = wire.indexOf(" zeta=\"");
        int defaultOrder = wire.indexOf(":mu=\"");
        int positive = wire.indexOf(" alpha=\"");
        assertTrue(negative >= 0, wire);
        assertTrue(defaultOrder > negative, wire);
        assertTrue(positive > defaultOrder, wire);

        // 属性仍正确限定和转义，二进制值确实作为独立附件输出。
        Document restored = this.parse(rootXml);
        Element restoredValue = (Element) restored.getElementsByTagName("value").item(0);
        assertEquals(value.zero, restoredValue.getAttributeNS(ATTRIBUTES, "mu"));
        NodeList includes = restored.getElementsByTagNameNS("http://www.w3.org/2004/08/xop/include", "Include");
        assertEquals(1, includes.getLength());
        try (InputStream input = multipart.getBodyPart(1).getInputStream()) {
            assertArrayEquals(value.binary, input.readAllBytes());
        }
    }

    @Test
    public void importedNodePreservesAttributeOrderInFinalXmlBytes() throws Exception {
        // 跨文档导入已映射的节点，顺序元数据不能只留在原文档上。
        Document source = SoapXmlUtils.newDocument();
        Element value = source.createElement("value");
        source.appendChild(value);
        SoapLiteralValueCodec codec = new SoapLiteralValueCodec(new SoapXmlMapping(SoapMessageCodec.newXmlMapper()));
        codec.write(value, OrderedValue.class, this.value(), new LinkedHashMap<>());
        Document imported = SoapXmlUtils.newDocument();
        imported.appendChild(imported.importNode(value, true));

        // 导入后的实际输出保持 zeta、mu、alpha，不能因 DOM 复制退回字典序。
        SoapAttachmentCodec.Message message = SoapAttachmentCodec.encode(imported, Map.of(), //
                SoapVersion.SOAP_11, null, false, MAX_BYTES);
        String wire = new String(message.body(), StandardCharsets.UTF_8);
        int negative = wire.indexOf(" zeta=\"");
        int defaultOrder = wire.indexOf(":mu=\"");
        int positive = wire.indexOf(" alpha=\"");
        assertTrue(negative >= 0, wire);
        assertTrue(defaultOrder > negative, wire);
        assertTrue(positive > defaultOrder, wire);
    }

    @Test
    public void defaultNamespaceIsDeclaredAndClearedForAnUnqualifiedChild() throws Exception {
        // DOM 的元素拥有命名空间，但没有人为添加 xmlns 属性。
        Document document = SoapXmlUtils.newDocument();
        Element root = document.createElementNS("urn:test:root", "root");
        document.appendChild(root);
        root.appendChild(document.createElementNS(null, "child"));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        // 输出负责补齐父元素声明，并清除子元素不应继承的默认命名空间。
        SoapXmlOutputUtils.write(document, output);
        Element restored = this.parse(output.toByteArray()).getDocumentElement();
        assertEquals("urn:test:root", restored.getNamespaceURI());
        assertNull(restored.getFirstChild().getNamespaceURI());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("xmlns=\"\""));
    }

    @Test
    public void prefixedAttributeNamespaceIsDeclaredWithoutAnExplicitXmlns() throws Exception {
        // 只有属性 QName 声明了前缀，DOM 上没有手工设置 xmlns:p。
        Document document = SoapXmlUtils.newDocument();
        Element root = document.createElement("root");
        document.appendChild(root);
        root.setAttributeNS(ATTRIBUTES, "p:code", "value");
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        // 最终报文必须能独立解析，属性也不能退回无命名空间。
        SoapXmlOutputUtils.write(document, output);
        Element restored = this.parse(output.toByteArray()).getDocumentElement();
        assertEquals("value", restored.getAttributeNS(ATTRIBUTES, "code"));
        assertEquals(ATTRIBUTES, restored.lookupNamespaceURI("p"));
    }

    @Test
    public void textCdataAndAttributeCharactersRoundTripWithoutNormalization() throws Exception {
        // 文本和属性含 XML 特殊字符，属性还含不能被规范化为空格的换行及制表符。
        String attribute = "&\"<>\r\n\t中文";
        String text = "<text>&\r\n\t中文";
        String cdata = "left]]>right<&";
        Document document = SoapXmlUtils.newDocument();
        Element root = document.createElement("root");
        document.appendChild(root);
        root.setAttribute("value", attribute);
        root.appendChild(document.createTextNode(text));
        root.appendChild(document.createCDATASection(cdata));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        // 输出后重新解析，转义和 CDATA 分段都不能改变原始字符内容。
        SoapXmlOutputUtils.write(document, output);
        Element restored = this.parse(output.toByteArray()).getDocumentElement();
        assertEquals(attribute, restored.getAttribute("value"));
        assertEquals(text + cdata, restored.getTextContent());
    }

    private OrderedValue value() {
        OrderedValue value = new OrderedValue();
        value.negative = "negative";
        value.zero = "&\"<>\r\n\t中文";
        value.positive = "positive";
        return value;
    }

    private Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory documents = DocumentBuilderFactory.newInstance();
        documents.setNamespaceAware(true);
        documents.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        documents.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        documents.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return documents.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    public static class OrderedValue {
        @MetField(name = "alpha")
        @JacksonXmlProperty(isAttribute = true)
        @JsonProperty(index = 20)
        public String positive;
        @MetField(name = "zeta")
        @JacksonXmlProperty(isAttribute = true)
        @JsonProperty(index = -10)
        public String negative;
        @MetField(name = "mu")
        @JacksonXmlProperty(isAttribute = true, namespace = ATTRIBUTES)
        public String zero;
        @MetField
        @JsonSetter(nulls = Nulls.FAIL)
        public byte[] binary;
    }
}

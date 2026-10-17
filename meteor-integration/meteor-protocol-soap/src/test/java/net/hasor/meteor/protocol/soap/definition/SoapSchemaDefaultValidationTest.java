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
import java.nio.charset.StandardCharsets;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Element;
import static org.junit.Assert.*;

public class SoapSchemaDefaultValidationTest {
    @Test
    public void omittedAttributeReceivesSchemaDefault() throws Exception {
        // 外部契约为未提供的 mode 属性声明默认值。
        Element payload = this.validate("", "<label>explicit</label>");

        // 校验后的 DOM 直接供 DTO 编解码使用。
        assertEquals("standard", payload.getAttribute("mode"));
    }

    @Test
    public void explicitAttributeKeepsItsOwnValue() throws Exception {
        // 显式属性覆盖默认值，校验不能把应用提供的值改回默认值。
        Element payload = this.validate("mode='custom'", "<label>explicit</label>");
        assertEquals("custom", payload.getAttribute("mode"));
    }

    @Test
    public void omittedFixedAttributeReceivesItsFixedValue() throws Exception {
        // fixed 属性缺省时，Schema 同样提供固定值。
        Element payload = this.validate("", "<label>explicit</label>");
        assertEquals("3", payload.getAttribute("version"));
    }

    @Test
    public void wrongFixedAttributeIsRejected() throws Exception {
        // 外部消息试图使用不同于契约的版本。
        IOException failure = assertThrows(IOException.class, () -> //
                this.validate("version='4'", "<label>explicit</label>"));
        assertTrue(failure.getMessage().contains("does not match its XML Schema"));
    }

    @Test
    public void emptyElementReceivesSchemaDefault() throws Exception {
        // 默认值填充空元素，不要求重新序列化整个 SOAP Envelope。
        Element payload = this.validate("", "<label/>");
        assertEquals("fallback", payload.getElementsByTagName("label").item(0).getTextContent());
    }

    @Test
    public void explicitElementKeepsItsOwnValue() throws Exception {
        // 有内容的元素不受 Schema 默认值影响。
        Element payload = this.validate("", "<label>provided</label>");
        assertEquals("provided", payload.getElementsByTagName("label").item(0).getTextContent());
    }

    @Test
    public void nilElementDoesNotReceiveSchemaDefault() throws Exception {
        // 明确的 nil 与空字符串是不同语义。
        String nilLabel = "<label xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance' xsi:nil='true'/>";
        Element payload = this.validate("", nilLabel);
        Element label = (Element) payload.getElementsByTagName("label").item(0);
        assertEquals("true", label.getAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "nil"));
        assertEquals("", label.getTextContent());
    }

    @Test
    public void absentOptionalElementIsNotInvented() throws Exception {
        // 元素的 default 只作用于已经出现的空元素，不能新增缺失的可选元素。
        Element payload = this.validate("", "<label/>");
        assertEquals(0, payload.getElementsByTagName("optional").getLength());
    }

    @Test
    public void validatedPartRetainsInjectedBusinessAttributes() throws Exception {
        // Header/单独 part 使用副本校验，也需要把业务默认值带回原消息。
        SoapWsdlResource resource = this.resource();
        Element payload = this.element("<m:notify xmlns:m='urn:meteor:test:schema-defaults'><label/></m:notify>");
        QName name = new QName("urn:meteor:test:schema-defaults", "notify");
        SoapPart part = new SoapPart("parameters", name, null, 1, 1);

        // 业务属性与默认文本在 validatePart 后可被映射器读取。
        resource.validatePart(payload, part);
        assertEquals("standard", payload.getAttribute("mode"));
        assertEquals("fallback", payload.getElementsByTagName("label").item(0).getTextContent());
    }

    private Element validate(String attributes, String content) throws Exception {
        SoapWsdlResource resource = this.resource();
        String xml = "<soap:Body xmlns:soap='http://schemas.xmlsoap.org/soap/envelope/'>" + //
                "<m:notify xmlns:m='urn:meteor:test:schema-defaults' " + attributes + ">" + //
                content + "</m:notify></soap:Body>";
        Element body = this.element(xml);
        resource.validateBody(body, resource.definition().operation("notify").inputBody());
        return (Element) body.getFirstChild();
    }

    private SoapWsdlResource resource() throws Exception {
        return new SoapWsdlResource(this.getClass().getResource("/wsdl/schema-defaults.wsdl"));
    }

    private Element element(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes)).getDocumentElement();
    }
}

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
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Document;
import static org.junit.Assert.*;

public class SoapStandardSchemaIdentityTest {
    private static final String XML_SCHEMA = "/META-INF/meteor/protocol/soap/xml-namespace.xsd";
    private static final String NAMESPACE  = "urn:meteor:test:standard-identity";
    private static final String ENDPOINT   = "http://localhost/services/Identity";

    @Test
    public void publishedXmlStandardSchemaSharesTheBuiltinComponentIndex() throws Exception {
        // 线上定义页返回插件携带的原始 XML 标准 Schema，资源 URL 与内置路径不同。
        SoapXmlResource main = this.resource("Identity?xsd=types.xsd", this.mainSchema());
        SoapXmlResource imported = this.resource("Identity?xsd=xml-namespace.xsd", this.standardSchema());
        SoapSchemaParser parser = new SoapSchemaParser(List.of(main, imported), main);

        // xml:lang 同时参与实际验证，不能因第二次索引相同标准声明而拒绝整个契约。
        Document payload = this.document("<m:value xmlns:m=\"" + NAMESPACE + "\" xml:lang=\"zh-CN\"/>");
        SoapPart part = new SoapPart("value", new QName(NAMESPACE, "value"), null, 1, 1);
        parser.validatePart(payload.getDocumentElement(), part);
    }

    @Test
    public void multipleUrlsForTheSameStandardSchemaShareOneIndexView() throws Exception {
        // 两个已加载 URL 返回同一标准原文，分别作为定义页与相对文件资源。
        byte[] standard = this.standardSchema();
        SoapXmlResource main = this.resource("Identity?xsd=types.xsd", this.mainSchema());
        SoapXmlResource first = this.resource("Identity?xsd=xml-namespace.xsd", standard);
        SoapXmlResource second = this.resource("xml.xsd", standard);

        // 资源 URL 继续独立保留，但索引只建立一份相同的标准组件。
        SoapSchemaParser parser = new SoapSchemaParser(List.of(main, first, second), main);
        assertTrue(parser.hasElement(new QName(NAMESPACE, "value")));
    }

    @Test
    public void aChangedStandardNamespaceDeclarationStillConflictsWithTheBuiltinContract() throws Exception {
        // 业务资源尝试把标准 xml:lang 改成整数类型，正文不再是插件的标准原文。
        SoapXmlResource main = this.resource("Identity?xsd=types.xsd", this.mainSchema());
        String changed = "<xsd:schema xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"" //
                + " targetNamespace=\"http://www.w3.org/XML/1998/namespace\">" //
                + "<xsd:attribute name=\"lang\" type=\"xsd:int\"/></xsd:schema>";
        SoapXmlResource imported = this.resource("Identity?xsd=xml-namespace.xsd", changed);

        // 标准身份复用不能放宽成按 namespace 或 QName 忽略重复。
        IOException failure = assertThrows(IOException.class, () -> {
            new SoapSchemaParser(List.of(main, imported), main);
        });
        assertTrue(failure.getMessage().contains("Duplicate XML Schema attribute"));
        assertTrue(failure.getMessage().contains("}lang"));
    }

    @Test
    public void differentCustomTypesWithTheSameQNameRemainRejected() throws Exception {
        // 两份自定义 Schema 以相同 QName 声明了不同的 Entry 内容模型。
        String firstType = "<xsd:schema xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\"" //
                + " targetNamespace=\"urn:meteor:test:custom\"><xsd:complexType name=\"Entry\">" //
                + "<xsd:sequence><xsd:element name=\"text\" type=\"xsd:string\"/></xsd:sequence>" //
                + "</xsd:complexType></xsd:schema>";
        String secondType = firstType.replace("name=\"text\" type=\"xsd:string\"", "name=\"number\" type=\"xsd:int\"");
        SoapXmlResource first = this.resource("first.xsd", firstType);
        SoapXmlResource second = this.resource("second.xsd", secondType);

        // 普通组件仍按严格 QName 冲突规则拒绝，不能借用标准文档的去重规则。
        IOException failure = assertThrows(IOException.class, () -> {
            new SoapSchemaParser(List.of(first, second), first);
        });
        assertEquals("Duplicate XML Schema type: {urn:meteor:test:custom}Entry", failure.getMessage());
    }

    private String mainSchema() {
        return "<xsd:schema xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" targetNamespace=\"" + NAMESPACE + "\">" //
                + "<xsd:import namespace=\"http://www.w3.org/XML/1998/namespace\" schemaLocation=\"" //
                + ENDPOINT + "?xsd=xml-namespace.xsd\"/><xsd:element name=\"value\"><xsd:complexType>" //
                + "<xsd:attribute ref=\"xml:lang\" use=\"required\"/></xsd:complexType></xsd:element></xsd:schema>";
    }

    private byte[] standardSchema() throws IOException {
        try (InputStream input = this.getClass().getResourceAsStream(XML_SCHEMA)) {
            assertNotNull(input);
            return input.readAllBytes();
        }
    }

    private SoapXmlResource resource(String name, String xml) throws Exception {
        return this.resource(name, xml.getBytes(StandardCharsets.UTF_8));
    }

    private SoapXmlResource resource(String name, byte[] body) throws Exception {
        URL source = new URL("http://localhost/services/" + name);
        return new SoapXmlResource(name, source, body, this.document(body));
    }

    private Document document(String xml) throws Exception {
        return this.document(xml.getBytes(StandardCharsets.UTF_8));
    }

    private Document document(byte[] body) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
    }
}

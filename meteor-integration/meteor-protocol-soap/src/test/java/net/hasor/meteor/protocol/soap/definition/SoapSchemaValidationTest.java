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
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Element;
import static org.junit.Assert.*;

public class SoapSchemaValidationTest {
    @Test
    public void optionalElementMayBeAbsent() throws Exception {
        // 可选参数未出现在包装元素中。
        SoapWsdlResource resource = this.resource("/wsdl/optional.wsdl");
        Element body = this.body("<m:notify xmlns:m=\"urn:meteor:test:optional\"/>");

        // XML Schema 接受 minOccurs=0 的空包装体。
        SoapOperation operation = resource.definition().operation("notify");
        resource.validateBody(body, operation.inputBody());
    }

    @Test
    public void repeatedElementMayAppearMoreThanOnce() throws Exception {
        // 可重复参数在同一个包装元素中出现两次。
        SoapWsdlResource resource = this.resource("/wsdl/repeated.wsdl");
        String payload = "<m:notify xmlns:m=\"urn:meteor:test:repeated\"><text>a</text><text>b</text></m:notify>";
        Element body = this.body(payload);

        // XML Schema 接受 maxOccurs=unbounded 的多个值。
        SoapOperation operation = resource.definition().operation("notify");
        resource.validateBody(body, operation.inputBody());
    }

    @Test
    public void choiceAcceptsOneBranch() throws Exception {
        // choice 只携带 text 分支。
        SoapWsdlResource resource = this.resource("/wsdl/choice.wsdl");
        String payload = "<m:notify xmlns:m=\"urn:meteor:test:choice\"><text>selected</text></m:notify>";
        Element body = this.body(payload);

        // XML Schema 接受唯一选择分支。
        SoapOperation operation = resource.definition().operation("notify");
        resource.validateBody(body, operation.inputBody());
    }

    @Test
    public void choiceRejectsMultipleBranches() throws Exception {
        // 同一个 choice 同时携带 text 和 number。
        SoapWsdlResource resource = this.resource("/wsdl/choice.wsdl");
        String payload = "<m:notify xmlns:m=\"urn:meteor:test:choice\"><text>a</text><number>1</number></m:notify>";
        Element body = this.body(payload);

        // Schema 校验在 Java 参数映射前拒绝不合法选择。
        SoapOperation operation = resource.definition().operation("notify");
        IOException failure = assertThrows(IOException.class, () -> //
                resource.validateBody(body, operation.inputBody()));
        assertTrue(failure.getMessage().contains("does not match its XML Schema"));
    }

    @Test
    public void extensionAcceptsBaseAndDerivedFields() throws Exception {
        // 派生包装体依次携带基类型字段和扩展字段。
        SoapWsdlResource resource = this.resource("/wsdl/extension.wsdl");
        String payload = "<m:notify xmlns:m=\"urn:meteor:test:extension\"><base>a</base><extra>b</extra></m:notify>";
        Element body = this.body(payload);

        // Schema 校验接受 complexContent extension 的完整内容模型。
        SoapOperation operation = resource.definition().operation("notify");
        resource.validateBody(body, operation.inputBody());
    }

    @Test
    public void chameleonElementsUseIncludingNamespace() throws Exception {
        // chameleon Schema 的全局包装体和 qualified 局部元素使用包含方命名空间。
        SoapWsdlResource resource = this.resource("/wsdl/chameleon.wsdl");
        String payload = "<m:notify xmlns:m=\"urn:meteor:test:chameleon\"><m:text>a</m:text></m:notify>";
        Element body = this.body(payload);

        // 内存 resolver 编译 include 后按有效命名空间校验消息。
        SoapOperation operation = resource.definition().operation("notify");
        resource.validateBody(body, operation.inputBody());
    }

    @Test
    public void undeclaredSoapControlAttributeDoesNotChangeBusinessSchema() throws Exception {
        // 普通字符串 Header 没有在业务 Schema 中声明 SOAP 控制属性。
        SoapWsdlResource resource = this.resource("/wsdl/styles/headers11.wsdl");
        String xml = "<m:token xmlns:m=\"urn:meteor:test:headers\" " + //
                "xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\" " + //
                "soap:mustUnderstand=\"1\">token</m:token>";
        Element header = this.element(xml);

        // 控制属性从验证副本移除，业务元素仍按原 XSD 校验。
        SoapOperation operation = resource.definition().operation("echo");
        resource.validatePart(header, operation.inputHeaders().get(0).part());
    }

    @Test
    public void declaredSoapControlAttributeEnforcesItsFixedValue() throws Exception {
        // Header Schema 显式声明 mustUnderstand 的固定值为 1。
        SoapWsdlResource resource = this.resource("/wsdl/header-control-fixed.wsdl");
        String xml = "<m:token xmlns:m=\"urn:meteor:test:header-control\" " + //
                "xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\" " + //
                "soap:mustUnderstand=\"0\">token</m:token>";
        Element header = this.element(xml);

        // 显式声明的 SOAP 属性保留给 Schema Validator，错误固定值必须失败。
        SoapOperation operation = resource.definition().operation("notify");
        IOException failure = assertThrows(IOException.class, () -> //
                resource.validatePart(header, operation.inputHeaders().get(0).part()));
        assertTrue(failure.getMessage().contains("does not match its XML Schema"));
    }

    @Test
    public void declaredSoapControlAttributeAcceptsItsFixedValue() throws Exception {
        // Header Schema 显式声明 mustUnderstand 的固定值为 1。
        SoapWsdlResource resource = this.resource("/wsdl/header-control-fixed.wsdl");
        String xml = "<m:token xmlns:m=\"urn:meteor:test:header-control\" " + //
                "xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope\" " + //
                "soap:mustUnderstand=\"1\">token</m:token>";
        Element header = this.element(xml);

        // 正确固定值由 Schema Validator 接受。
        SoapOperation operation = resource.definition().operation("notify");
        resource.validatePart(header, operation.inputHeaders().get(0).part());
    }

    private SoapWsdlResource resource(String name) throws Exception {
        URL resource = this.getClass().getResource(name);
        assertNotNull("Missing test resource " + name, resource);
        return new SoapWsdlResource(resource);
    }

    private Element body(String payload) throws Exception {
        String xml = "<soap:Body xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">" + payload + //
                "</soap:Body>";
        return this.element(xml);
    }

    private Element element(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes)).getDocumentElement();
    }
}

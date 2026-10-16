/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.definition;
import java.io.IOException;
import java.net.URL;
import javax.xml.namespace.QName;
import org.junit.Test;
import static org.junit.Assert.*;

public class SoapHeaderContractTest {
    @Test
    public void soap11HeadersRetainTheirDeclaredParts() throws Exception {
        // 同一个输入 message 同时声明 Body 和 Header part。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("headers11.wsdl"));

        // 选择 Body part 后，Header 和 HeaderFault 保留完整 QName。
        QName token = new QName("urn:meteor:test:headers", "token");
        SoapOperation operation = resource.definition().operation("echo");
        assertEquals(token, operation.inputHeaders().get(0).part().element());
        assertEquals(token, operation.outputHeaders().get(0).part().element());
        assertEquals(new QName("urn:meteor:test:headers", "tokenFault"), //
                operation.inputHeaders().get(0).faults().get(0).part().element());
        assertEquals(1, operation.inputBody().parts().size());
    }

    @Test
    public void soap12HeadersRetainTheirDeclaredParts() throws Exception {
        // SOAP 1.2 使用独立 WSDL 扩展命名空间声明 Header。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("headers12.wsdl"));

        // HeaderFault 仍通过 message/part 引用实际的全局 XML 元素。
        SoapOperation operation = resource.definition().operation("echo");
        assertEquals(new QName("urn:meteor:test:headers", "token"), //
                operation.inputHeaders().get(0).part().element());
        assertEquals(new QName("urn:meteor:test:headers", "tokenFault"), //
                operation.inputHeaders().get(0).faults().get(0).part().element());
    }

    @Test
    public void declaredFaultRetainsItsDetailPartAndUse() throws Exception {
        // portType fault 与 binding fault 通过名称关联。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("headers11.wsdl"));

        // 运行时根据声明的 detail QName 选择显式 Fault 映射。
        SoapOperation operation = resource.definition().operation("echo");
        SoapFaultBinding fault = operation.faults().get("Problem");
        assertEquals(new QName("urn:meteor:test:headers", "problem"), fault.part().element());
        assertEquals("literal", fault.use());
    }

    @Test
    public void explicitEmptyBodyDoesNotIncludeHeaderParts() throws Exception {
        // 显式 parts="" 表达空 Body，仍可从同一 message 选择 Header。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("headers-empty-body.wsdl"));

        // Header 不会被误当成 Java 业务参数。
        SoapOperation operation = resource.definition().operation("echo");
        assertEquals(0, operation.inputBody().parts().size());
        assertEquals(new QName("urn:meteor:test:headers", "token"), //
                operation.inputHeaders().get(0).part().element());
    }

    @Test
    public void headerCannotReferenceAnUnknownMessage() {
        // Header 的 message 引用在契约图中不存在。
        URL wsdl = this.resource("headers-missing-message.wsdl");

        // 错误必须在资源加载阶段暴露。
        IOException failure = assertThrows(IOException.class, () -> new SoapWsdlResource(wsdl));
        assertTrue(failure.getMessage().contains("unknown message"));
    }

    @Test
    public void headerCannotReferenceAnUnknownPart() {
        // message 存在，但 Header 选择了未声明的 part。
        URL wsdl = this.resource("headers-missing-part.wsdl");

        // 不允许把一个无法编码的 Header 保留到调用阶段。
        IOException failure = assertThrows(IOException.class, () -> new SoapWsdlResource(wsdl));
        assertTrue(failure.getMessage().contains("unknown message part"));
    }

    @Test
    public void faultMessageMustContainExactlyOnePart() {
        // SOAP fault binding 对应的抽象消息错误地声明两个 part。
        URL wsdl = this.resource("fault-multipart.wsdl");

        // 根据 WSDL SOAP fault 约束拒绝不确定的 detail 映射。
        IOException failure = assertThrows(IOException.class, () -> new SoapWsdlResource(wsdl));
        assertTrue(failure.getMessage().contains("fault message must contain one part"));
    }

    private URL resource(String name) {
        URL resource = this.getClass().getResource("/wsdl/styles/" + name);
        assertNotNull("Missing WSDL fixture " + name, resource);
        return resource;
    }
}

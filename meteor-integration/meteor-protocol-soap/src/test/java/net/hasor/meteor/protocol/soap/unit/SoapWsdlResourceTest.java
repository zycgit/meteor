/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.unit;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.hasor.meteor.protocol.soap.definition.SoapWsdlResource;
import net.hasor.meteor.protocol.soap.definition.SoapWsdlResourceAccess;
import org.junit.Test;
import static org.junit.Assert.*;

public class SoapWsdlResourceTest {
    @Test
    public void documentLiteralResourceIsParsed() throws Exception {
        // 加载包含外部 XSD 的 document/literal wrapped 服务定义。
        URL wsdl = this.resource("/wsdl/echo.wsdl");
        SoapWsdlResource resource = new SoapWsdlResource(wsdl);

        // 验证 WSDL 服务与 Java 方法映射所需的定义信息。
        assertEquals(wsdl, resource.getWsdl());
        assertEquals("urn:meteor:test:echo", SoapWsdlResourceAccess.targetNamespace(resource));
        assertEquals(1, SoapWsdlResourceAccess.operationCount(resource));
        assertEquals("urn:meteor:test:echo", SoapWsdlResourceAccess.soapAction(resource, "echo"));
        assertEquals("{urn:meteor:test:echo:types}echo", SoapWsdlResourceAccess.inputElement(resource, "echo"));
        String typeNamespace = "urn:meteor:test:echo:types";
        assertArrayEquals(new String[] { "{" + typeNamespace + "}message" }, //
                SoapWsdlResourceAccess.parameterElements(resource, "echo"));
        assertEquals("{" + typeNamespace + "}result", SoapWsdlResourceAccess.resultElement(resource, "echo"));
        assertFalse(SoapWsdlResourceAccess.oneWay(resource, "echo"));
    }

    @Test
    public void soap12DocumentLiteralResourceIsParsed() throws Exception {
        // SOAP 1.2 使用 WSDL 1.1 的 soap12 扩展描述消息绑定。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("/wsdl/echo12.wsdl"));

        // 绑定版本及缺省要求的 Content-Type action 保留到运行时契约。
        assertEquals("1.2", SoapWsdlResourceAccess.soapVersion(resource));
        assertEquals("urn:meteor:test:echo", SoapWsdlResourceAccess.soapAction(resource, "echo"));
        assertTrue(SoapWsdlResourceAccess.actionRequired(resource, "echo"));
    }

    @Test
    public void soap12OptionalActionMayBeOmitted() throws Exception {
        // 明确声明 soapActionRequired=false 的 SOAP 1.2 operation 不要求提供 action。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("/wsdl/optional-action12.wsdl"));

        // 无 action 与要求发送空 action 是不同契约。
        assertFalse(SoapWsdlResourceAccess.actionRequired(resource, "echo"));
        assertEquals("", SoapWsdlResourceAccess.soapAction(resource, "echo"));
    }

    @Test
    public void soap12RequiredActionMustBeDeclared() {
        // 未声明 soapActionRequired 时，SOAP 1.2 缺省要求发送 action。
        URL wsdl = this.resource("/wsdl/missing-action12.wsdl");

        // 契约不能要求一个从未声明的 action。
        IOException failure = assertThrows(IOException.class, () -> new SoapWsdlResource(wsdl));
        assertTrue(failure.getMessage().contains("requires soapAction"));
    }

    @Test
    public void soap12ActionMustBeAnAbsoluteUri() {
        // SOAP 1.2 action 与 SOAP 1.1 SOAPAction 不同，必须为绝对 URI。
        URL wsdl = this.resource("/wsdl/relative-action12.wsdl");

        // 不把无法构造合法 Content-Type action 的定义带入运行时。
        IOException failure = assertThrows(IOException.class, () -> new SoapWsdlResource(wsdl));
        assertTrue(failure.getMessage().contains("absolute URI"));
    }

    @Test
    public void soapPortCannotSelectAnotherBindingVersion() {
        // SOAP 1.1 address 错误地选择了 SOAP 1.2 binding。
        URL wsdl = this.resource("/wsdl/mixed-binding-version.wsdl");

        // 端点地址和消息绑定必须指向同一个 SOAP 版本。
        IOException failure = assertThrows(IOException.class, () -> new SoapWsdlResource(wsdl));
        assertTrue(failure.getMessage().contains("different SOAP versions"));
    }

    @Test
    public void undeclaredBindingQNamePrefixIsRejected() {
        // 服务端口 binding 引用了未声明的 XML 命名空间前缀。
        URL wsdl = this.resource("/wsdl/undeclared-prefix.wsdl");

        // 不把缺失前缀静默降为无命名空间 QName。
        IOException failure = assertThrows(IOException.class, () -> new SoapWsdlResource(wsdl));
        assertTrue(failure.getMessage().contains("Undeclared XML QName prefix"));
    }

    @Test
    public void soap12RenderedWsdlUsesPublishedEndpoint() throws Exception {
        // SOAP 1.2 定义中的 address 也必须替换为当前发布地址。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("/wsdl/echo12.wsdl"));
        URI endpoint = URI.create("http://127.0.0.1:2181/services/EchoService");

        // 渲染保留 soap12 绑定，只更新动态地址。
        byte[] rendered = SoapWsdlResourceAccess.renderWsdl(resource, endpoint);
        String wsdl = new String(rendered, StandardCharsets.UTF_8);
        assertTrue(wsdl.contains("http://schemas.xmlsoap.org/wsdl/soap12/"));
        assertTrue(wsdl.contains("location=\"" + endpoint + "\""));
        assertFalse(wsdl.contains("/placeholder/EchoService"));
    }

    @Test
    public void renderedWsdlUsesPublishedEndpoint() throws Exception {
        // 加载带有占位服务地址的定义并指定实际发布端点。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("/wsdl/echo.wsdl"));
        URI endpoint = URI.create("http://127.0.0.1:2181/services/EchoService");

        // 验证返回给客户端的 WSDL 使用实际端点。
        byte[] rendered = SoapWsdlResourceAccess.renderWsdl(resource, endpoint);
        String wsdl = new String(rendered, StandardCharsets.UTF_8);
        assertTrue(wsdl.contains("location=\"http://127.0.0.1:2181/services/EchoService\""));
        assertFalse(wsdl.contains("/placeholder/EchoService"));
    }

    @Test
    public void importedSchemaCanBeRead() throws Exception {
        // 加载引用外部 XML Schema 的服务定义。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("/wsdl/echo.wsdl"));

        // 验证定义目录与 Schema 原始内容均可读取。
        assertTrue(SoapWsdlResourceAccess.resourceNames(resource).contains("echo-types.xsd"));
        byte[] schemaBytes = SoapWsdlResourceAccess.resource(resource, "echo-types.xsd");
        String schema = new String(schemaBytes, StandardCharsets.UTF_8);
        assertTrue(schema.contains("targetNamespace=\"urn:meteor:test:echo:types\""));
    }

    @Test
    public void inputOnlyOperationIsOneWay() throws Exception {
        // 加载只声明 input 消息的 WSDL one-way 服务。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("/wsdl/signal.wsdl"));

        // 验证该操作被识别为无需 SOAP 业务响应的调用。
        assertEquals(1, SoapWsdlResourceAccess.operationCount(resource));
        assertTrue(SoapWsdlResourceAccess.oneWay(resource, "signal"));
    }

    @Test
    public void unreferencedBindingIsIgnored() throws Exception {
        // 准备包含两个 binding、但服务端口只挂载其中一个的 WSDL。
        URL wsdl = this.resource("/wsdl/selected-binding.wsdl");
        SoapWsdlResource resource = new SoapWsdlResource(wsdl);

        // 验证操作表只来自服务端口实际引用的 binding。
        assertEquals(1, SoapWsdlResourceAccess.operationCount(resource));
        assertEquals("urn:meteor:test:selected:echo", SoapWsdlResourceAccess.soapAction(resource, "echo"));
    }

    @Test
    public void wrapperFieldsRetainTheirEffectiveQNames() throws Exception {
        // 准备同时使用 schema 默认值、局部 form 和全局 element ref 的定义。
        URL wsdl = this.resource("/wsdl/field-namespaces.wsdl");
        SoapWsdlResource resource = new SoapWsdlResource(wsdl);

        // 无 form 的局部元素保持无命名空间，qualified 和 ref 保留目标命名空间。
        String namespace = "urn:meteor:test:fields";
        assertArrayEquals(new String[] { "plain", "{" + namespace + "}qualified" }, //
                SoapWsdlResourceAccess.parameterElements(resource, "combine"));
        assertEquals("{" + namespace + "}finalResult", //
                SoapWsdlResourceAccess.resultElement(resource, "combine"));
    }

    @Test
    public void schemaChoiceRetainsItsBranchConstraint() throws Exception {
        // 准备使用 choice 组合参数的 SOAP 定义。
        URL wsdl = this.resource("/wsdl/choice.wsdl");
        SoapWsdlResource resource = new SoapWsdlResource(wsdl);

        // 两个分支分别映射 Java 参数，并由 choice 约束要求恰好选择一个。
        assertArrayEquals(new String[] { "text", "number" }, //
                SoapWsdlResourceAccess.parameterElements(resource, "notify"));
        assertEquals(1, SoapWsdlResourceAccess.choiceCount(resource, "notify"));
        assertEquals(1, SoapWsdlResourceAccess.choiceMinOccurs(resource, "notify", 0));
        assertEquals(List.of(0, 1), SoapWsdlResourceAccess.choicePartIndexes(resource, "notify", 0));
    }

    @Test
    public void repeatedSchemaChoiceCannotBeForcedIntoWrappedParameters() throws Exception {
        // 异构 choice 整体重复时无法稳定映射到一组固定 Java 参数。
        URL wsdl = this.resource("/wsdl/repeated-choice.wsdl");
        SoapWsdlResource resource = new SoapWsdlResource(wsdl);

        // 显式 wrapped 仍报告无法展开的粒子，不能悄悄改变用户选择的 Java 映射。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> SoapWsdlResourceAccess.inputWrapper(resource, "notify", "wrapped"));
        assertTrue(failure.getCause().getMessage().contains("Repeated XML Schema choice"));
    }

    @Test
    public void autoMappingRetainsRepeatedChoiceAsOneBareElement() throws Exception {
        // 合法复杂元素恰好与 operation 同名，但内容不能展开为固定参数列表。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("/wsdl/repeated-choice.wsdl"));

        // auto 将整个 global element 作为一个 bare 参数，保留完整 Schema 验证。
        assertNull(SoapWsdlResourceAccess.inputWrapper(resource, "notify", "auto"));
        assertArrayEquals(new String[] { "{urn:meteor:test:repeated-choice}notify" }, //
                SoapWsdlResourceAccess.parameterElements(resource, "notify"));
    }

    @Test
    public void explicitBareMappingCanBeSelectedAfterAutoLoading() throws Exception {
        // 资源构造不能先因 wrapped 展开失败而阻止后续端点选择 bare。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("/wsdl/repeated-choice.wsdl"));

        // 已验证的资源图可以继续选择明确的 bare 参数映射。
        assertNull(SoapWsdlResourceAccess.inputWrapper(resource, "notify", "bare"));
    }

    @Test
    public void autoMappingDoesNotHideInvalidSchemaOccurrences() {
        // minOccurs 大于 maxOccurs 属于非法 XSD，而不是 Java 参数展开限制。
        URL wsdl = this.resource("/wsdl/invalid-occurrence.wsdl");

        // Schema 编译失败必须直接传播，不能把错误契约作为 bare 接受。
        IOException failure = assertThrows(IOException.class, () -> new SoapWsdlResource(wsdl));
        assertTrue(failure.getMessage().contains("Invalid XML Schema contract"));
    }

    @Test
    public void optionalSchemaElementRetainsItsMinimum() throws Exception {
        // 准备包含 minOccurs=0 可选参数的 SOAP 定义。
        URL wsdl = this.resource("/wsdl/optional.wsdl");
        SoapWsdlResource resource = new SoapWsdlResource(wsdl);

        // 可选参数在运行时缺失时映射为 null。
        assertEquals(0, SoapWsdlResourceAccess.parameterMinOccurs(resource, "notify", 0));
        assertEquals(1, SoapWsdlResourceAccess.parameterMaxOccurs(resource, "notify", 0));
    }

    @Test
    public void repeatedSchemaElementRetainsItsMaximum() throws Exception {
        // 准备包含可重复参数的 SOAP 定义。
        URL wsdl = this.resource("/wsdl/repeated.wsdl");
        SoapWsdlResource resource = new SoapWsdlResource(wsdl);

        // unbounded 使用 -1 保留，交给数组或 Collection 参数消费。
        assertEquals(1, SoapWsdlResourceAccess.parameterMinOccurs(resource, "notify", 0));
        assertEquals(-1, SoapWsdlResourceAccess.parameterMaxOccurs(resource, "notify", 0));
    }

    @Test
    public void duplicateParameterQNameIsRejectedForWrappedMapping() throws Exception {
        // 准备在同一个 operation 输入包装中重复声明参数 QName 的 SOAP 定义。
        URL wsdl = this.resource("/wsdl/unsupported-duplicate-qname.wsdl");

        // auto 可保留整个 bare 元素；显式 wrapped 无法按 QName 区分这两个参数。
        SoapWsdlResource resource = new SoapWsdlResource(wsdl);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> //
                SoapWsdlResourceAccess.inputWrapper(resource, "combine", "wrapped"));
        assertTrue(failure.getCause().getMessage().contains("duplicate parameter QName"));
    }

    @Test
    public void complexTypeExtensionIncludesBaseAndDerivedParts() throws Exception {
        // 准备通过 complexContent/extension 派生请求类型的 SOAP 定义。
        URL wsdl = this.resource("/wsdl/extension.wsdl");
        SoapWsdlResource resource = new SoapWsdlResource(wsdl);

        // 基类型粒子先于扩展粒子映射为 Java 参数。
        assertArrayEquals(new String[] { "base", "extra" }, //
                SoapWsdlResourceAccess.parameterElements(resource, "notify"));
    }

    @Test
    public void chameleonSchemaIncludeUsesOwnerNamespace() throws Exception {
        // 准备 include 无 targetNamespace Schema 的 chameleon 定义。
        URL wsdl = this.resource("/wsdl/chameleon.wsdl");
        SoapWsdlResource resource = new SoapWsdlResource(wsdl);

        // 被包含组件继承包含方命名空间，局部 qualified 参数也使用该命名空间。
        String namespace = "urn:meteor:test:chameleon";
        assertEquals("{" + namespace + "}notify", SoapWsdlResourceAccess.inputElement(resource, "notify"));
        assertArrayEquals(new String[] { "{" + namespace + "}text" }, //
                SoapWsdlResourceAccess.parameterElements(resource, "notify"));
    }

    @Test
    public void nonHttpSoapTransportIsRejected() {
        // 准备挂载非 HTTP transport 的 SOAP 1.1 binding。
        URL wsdl = this.resource("/wsdl/invalid-transport.wsdl");

        // 验证插件只接受 SOAP 1.1 标准 HTTP transport。
        IOException failure = assertThrows(IOException.class, () -> new SoapWsdlResource(wsdl));
        assertTrue(failure.getMessage().contains("SOAP 1.1 HTTP transport"));
    }

    @Test
    public void multipleSoapPortsAreRejected() {
        // 准备在入口服务中声明两个 SOAP 1.1 端口的 WSDL。
        URL wsdl = this.resource("/wsdl/ambiguous-ports.wsdl");

        // 验证没有显式端口选择能力时拒绝歧义定义。
        IOException failure = assertThrows(IOException.class, () -> new SoapWsdlResource(wsdl));
        assertTrue(failure.getMessage().contains("multiple SOAP service ports"));
    }

    @Test
    public void documentTypeIsRejected() {
        // 准备包含外部实体声明的 WSDL。
        URL wsdl = this.resource("/wsdl/xxe.wsdl");

        // 验证解析阶段拒绝带有 DOCTYPE 的输入。
        assertThrows(IOException.class, () -> new SoapWsdlResource(wsdl));
    }

    @Test
    public void crossOriginSchemaIsRejected() {
        // 准备引用远程 Schema 的 WSDL。
        URL wsdl = this.resource("/wsdl/external-import.wsdl");

        // 验证资源解析不会访问定义根目录之外的远程地址。
        assertThrows(IOException.class, () -> new SoapWsdlResource(wsdl));
    }

    @Test
    public void parentDirectorySchemaIsRejected() {
        // 准备从入口目录向上引用已存在 Schema 的 WSDL。
        URL wsdl = this.resource("/wsdl/nested/traversal.wsdl");

        // 验证资源根目录边界优先于目标文件是否存在。
        assertThrows(IOException.class, () -> new SoapWsdlResource(wsdl));
    }

    @Test
    public void encodedBackslashSchemaIsRejected() {
        // 准备使用编码反斜杠表达父目录跳转的 WSDL。
        URL wsdl = this.resource("/wsdl/encoded-backslash.wsdl");

        // 验证不同操作系统都不会把反斜杠解释为资源目录分隔符。
        IOException failure = assertThrows(IOException.class, () -> new SoapWsdlResource(wsdl));
        assertTrue(failure.getMessage().contains("forward slashes"));
    }

    @Test
    public void schemaRedefineIsRejected() {
        // 准备使用当前适配器尚未实现的 XML Schema redefine 定义。
        URL wsdl = this.resource("/wsdl/redefine.wsdl");

        // 验证不支持的语义在发布准备阶段明确失败。
        IOException failure = assertThrows(IOException.class, () -> new SoapWsdlResource(wsdl));
        assertTrue(failure.getMessage().contains("redefine"));
    }

    @Test
    public void deeplyNestedDefinitionIsRejected() throws Exception {
        // 构造体积很小、但嵌套深度超过限制的本地 WSDL。
        Path wsdl = Files.createTempFile("meteor-deep-wsdl", ".wsdl");
        StringBuilder xml = new StringBuilder("<definitions xmlns=\"http://schemas.xmlsoap.org/wsdl/\">");
        for (int index = 0; index < 130; index++) {
            xml.append("<documentation>");
        }
        for (int index = 0; index < 130; index++) {
            xml.append("</documentation>");
        }
        xml.append("</definitions>");
        Files.writeString(wsdl, xml, StandardCharsets.UTF_8);

        try {
            // 验证深层文档在任何递归转换前被有界检查拒绝。
            IOException failure = assertThrows(IOException.class, () -> new SoapWsdlResource(wsdl.toUri().toURL()));
            assertTrue(failure.getMessage().contains("nesting depth"));
        } finally {
            Files.deleteIfExists(wsdl);
        }
    }

    @Test
    public void missingSchemaIsReported() {
        // 准备引用不存在 Schema 的 WSDL。
        URL wsdl = this.resource("/wsdl/missing-import.wsdl");

        // 验证缺失的契约资源在发布准备阶段即可被发现。
        assertThrows(IOException.class, () -> new SoapWsdlResource(wsdl));
    }

    private URL resource(String path) {
        URL resource = this.getClass().getResource(path);
        assertNotNull("Missing test resource " + path, resource);
        return resource;
    }
}

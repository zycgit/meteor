/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.unit;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import javax.xml.namespace.QName;
import net.hasor.meteor.protocol.soap.definition.SoapBindingStyleAccess;
import net.hasor.meteor.protocol.soap.definition.SoapWsdlResource;
import net.hasor.meteor.protocol.soap.definition.SoapWsdlResourceAccess;
import org.junit.Test;
import static org.junit.Assert.*;

public class SoapBindingStyleTest {
    private static final String XSD_NAMESPACE = "http://www.w3.org/2001/XMLSchema";
    private static final String RPC_NAMESPACE = "urn:meteor:test:styles:rpc:body";

    @Test
    public void documentLiteralScalarInputRemainsBare() throws Exception {
        // 请求元素与 operation 同名，但标量类型不能被当作参数包装层。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("document-bare.wsdl"));
        QName request = new QName("urn:meteor:test:styles:bare", "echo");

        // Body 直接包含该标量元素，并保留消息部件名与 Schema 类型。
        assertEquals("document", SoapBindingStyleAccess.style(resource, "echo"));
        assertEquals("literal", SoapBindingStyleAccess.inputUse(resource, "echo"));
        assertNull(SoapBindingStyleAccess.inputWrapper(resource, "echo"));
        assertEquals(List.of(request), SoapBindingStyleAccess.inputElements(resource, "echo"));
        assertArrayEquals(new String[] { "message" }, SoapBindingStyleAccess.inputPartNames(resource, "echo"));
        assertEquals(List.of(new QName(XSD_NAMESPACE, "string")), SoapBindingStyleAccess.inputTypes(resource, "echo"));
        assertEquals(1, SoapBindingStyleAccess.inputMinOccurs(resource, "echo", 0));
        assertEquals(1, SoapBindingStyleAccess.inputMaxOccurs(resource, "echo", 0));
    }

    @Test
    public void documentLiteralScalarOutputRemainsBare() throws Exception {
        // 响应元素采用 operationResponse 命名，但其内容仍是一个标量。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("document-bare.wsdl"));
        QName response = new QName("urn:meteor:test:styles:bare", "echoResponse");

        // 不凭元素名称虚构响应 wrapper 或丢失返回值类型。
        assertNull(SoapBindingStyleAccess.outputWrapper(resource, "echo"));
        assertEquals("literal", SoapBindingStyleAccess.outputUse(resource, "echo"));
        assertEquals(List.of(response), SoapBindingStyleAccess.outputElements(resource, "echo"));
        assertArrayEquals(new String[] { "result" }, SoapBindingStyleAccess.outputPartNames(resource, "echo"));
        assertEquals(List.of(new QName(XSD_NAMESPACE, "string")), SoapBindingStyleAccess.outputTypes(resource, "echo"));
    }

    @Test
    public void documentLiteralInputMayContainSeveralParts() throws Exception {
        // 每个消息部件引用一个全局元素，Body 不额外包裹 operation 元素。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("document-multipart.wsdl"));
        String namespace = "urn:meteor:test:styles:multipart";
        List<QName> elements = List.of(new QName(namespace, "firstName"), new QName(namespace, "lastName"));

        // 省略 soap:body parts 时，按消息声明顺序包含全部部件。
        assertNull(SoapBindingStyleAccess.inputWrapper(resource, "combine"));
        assertEquals(elements, SoapBindingStyleAccess.inputElements(resource, "combine"));
        assertArrayEquals(new String[] { "first", "last" }, //
                SoapBindingStyleAccess.inputPartNames(resource, "combine"));
    }

    @Test
    public void documentLiteralOutputMayContainSeveralParts() throws Exception {
        // 响应消息同时声明结果文本和长度，不能降为单一返回元素。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("document-multipart.wsdl"));
        String namespace = "urn:meteor:test:styles:multipart";
        List<QName> elements = List.of(new QName(namespace, "fullName"), new QName(namespace, "length"));
        List<QName> types = List.of(new QName(XSD_NAMESPACE, "string"), new QName(XSD_NAMESPACE, "int"));

        // 两个顶层元素与各自类型均保留在响应 Body 中。
        assertNull(SoapBindingStyleAccess.outputWrapper(resource, "combine"));
        assertEquals(elements, SoapBindingStyleAccess.outputElements(resource, "combine"));
        assertEquals(types, SoapBindingStyleAccess.outputTypes(resource, "combine"));
        assertArrayEquals(new String[] { "result", "size" }, //
                SoapBindingStyleAccess.outputPartNames(resource, "combine"));
    }

    @Test
    public void documentLiteralInputMayHaveNoParts() throws Exception {
        // 空 input message 表示 Body 没有业务元素。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("document-empty.wsdl"));

        // 空消息保持无 wrapper、无参数的形态。
        assertNull(SoapBindingStyleAccess.inputWrapper(resource, "ping"));
        assertTrue(SoapBindingStyleAccess.inputElements(resource, "ping").isEmpty());
        assertArrayEquals(new String[0], SoapBindingStyleAccess.inputPartNames(resource, "ping"));
    }

    @Test
    public void documentLiteralEmptyOutputStillDefinesAResponse() throws Exception {
        // WSDL 声明了 output，但该 output message 没有任何 part。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("document-empty.wsdl"));

        // 空响应消息与完全省略 output 的 one-way operation 保持区别。
        assertFalse(SoapWsdlResourceAccess.oneWay(resource, "ping"));
        assertNull(SoapBindingStyleAccess.outputWrapper(resource, "ping"));
        assertTrue(SoapBindingStyleAccess.outputElements(resource, "ping").isEmpty());
        assertArrayEquals(new String[0], SoapBindingStyleAccess.outputPartNames(resource, "ping"));
    }

    @Test
    public void bodyPartsSelectInputWithoutChangingMessageOrder() throws Exception {
        // message 依次声明 left、trace、right，body parts 列表故意写成 right left。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("document-parts.wsdl"));
        String namespace = "urn:meteor:test:styles:parts";
        List<QName> elements = List.of(new QName(namespace, "leftValue"), new QName(namespace, "rightValue"));

        // parts 负责筛选，不改变消息声明的相对顺序，也不把 header 部件当作参数。
        assertEquals(elements, SoapBindingStyleAccess.inputElements(resource, "add"));
        assertArrayEquals(new String[] { "left", "right" }, SoapBindingStyleAccess.inputPartNames(resource, "add"));
    }

    @Test
    public void bodyPartsSelectOutputIndependentlyFromInput() throws Exception {
        // output message 将 trace 放在 result 前面，但响应 body 只选择 result。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("document-parts.wsdl"));
        QName result = new QName("urn:meteor:test:styles:parts", "sum");

        // 响应使用自己的 parts 选择，不复用请求的选择或读取所有 message part。
        assertNull(SoapBindingStyleAccess.outputWrapper(resource, "add"));
        assertEquals(List.of(result), SoapBindingStyleAccess.outputElements(resource, "add"));
        assertArrayEquals(new String[] { "result" }, SoapBindingStyleAccess.outputPartNames(resource, "add"));
    }

    @Test
    public void soap11RpcLiteralUsesOperationWrapperAndTypedAccessors() throws Exception {
        // SOAP 1.1 RPC 参数用 type 声明，由 body namespace 决定 operation wrapper 的命名空间。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("rpc-literal11.wsdl"));
        List<QName> parameters = List.of(new QName("left"), new QName("right"));
        List<QName> types = List.of(new QName(XSD_NAMESPACE, "int"), new QName(XSD_NAMESPACE, "int"));

        // RPC accessor 使用 part 名称且没有 namespace，响应使用 addResponse 包装。
        assertEquals("1.1", SoapWsdlResourceAccess.soapVersion(resource));
        assertEquals("rpc", SoapBindingStyleAccess.style(resource, "add"));
        assertEquals("literal", SoapBindingStyleAccess.inputUse(resource, "add"));
        assertEquals(new QName(RPC_NAMESPACE, "add"), SoapBindingStyleAccess.inputWrapper(resource, "add"));
        assertEquals(parameters, SoapBindingStyleAccess.inputElements(resource, "add"));
        assertEquals(types, SoapBindingStyleAccess.inputTypes(resource, "add"));
        assertEquals(new QName(RPC_NAMESPACE, "addResponse"), SoapBindingStyleAccess.outputWrapper(resource, "add"));
        assertEquals(List.of(new QName("result")), SoapBindingStyleAccess.outputElements(resource, "add"));
    }

    @Test
    public void soap12RpcLiteralUsesOperationWrapperAndTypedAccessors() throws Exception {
        // SOAP 1.2 独立加载 soap12 扩展，不借用 SOAP 1.1 的已解析定义。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("rpc-literal12.wsdl"));
        List<QName> parameters = List.of(new QName("left"), new QName("right"));
        List<QName> types = List.of(new QName(XSD_NAMESPACE, "int"), new QName(XSD_NAMESPACE, "int"));

        // 版本、action 要求、RPC wrapper 和类型参数共同保留。
        assertEquals("1.2", SoapWsdlResourceAccess.soapVersion(resource));
        assertTrue(SoapWsdlResourceAccess.actionRequired(resource, "add"));
        assertEquals("rpc", SoapBindingStyleAccess.style(resource, "add"));
        assertEquals("literal", SoapBindingStyleAccess.inputUse(resource, "add"));
        assertEquals(new QName(RPC_NAMESPACE, "add"), SoapBindingStyleAccess.inputWrapper(resource, "add"));
        assertEquals(parameters, SoapBindingStyleAccess.inputElements(resource, "add"));
        assertEquals(types, SoapBindingStyleAccess.inputTypes(resource, "add"));
        assertEquals(new QName(RPC_NAMESPACE, "addResponse"), SoapBindingStyleAccess.outputWrapper(resource, "add"));
        assertEquals(List.of(new QName("result")), SoapBindingStyleAccess.outputElements(resource, "add"));
    }

    @Test
    public void soap11RpcEncodedPreservesEncodingInBothDirections() throws Exception {
        // SOAP 1.1 RPC/encoded 输入和输出均明确声明 SOAP 1.1 Encoding URI。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("rpc-encoded11.wsdl"));
        String encoding = "http://schemas.xmlsoap.org/soap/encoding/";

        // 编码元数据属于各自 Body，不能丢失或当作 literal 处理。
        assertEquals("1.1", SoapWsdlResourceAccess.soapVersion(resource));
        assertEquals("rpc", SoapBindingStyleAccess.style(resource, "add"));
        assertEquals("encoded", SoapBindingStyleAccess.inputUse(resource, "add"));
        assertEquals("encoded", SoapBindingStyleAccess.outputUse(resource, "add"));
        assertEquals(encoding, SoapBindingStyleAccess.inputEncodingStyle(resource, "add"));
        assertEquals(encoding, SoapBindingStyleAccess.outputEncodingStyle(resource, "add"));
        assertEquals(new QName(RPC_NAMESPACE, "add"), SoapBindingStyleAccess.inputWrapper(resource, "add"));
        assertEquals(new QName(RPC_NAMESPACE, "addResponse"), SoapBindingStyleAccess.outputWrapper(resource, "add"));
        assertEquals(List.of(new QName(XSD_NAMESPACE, "int")), SoapBindingStyleAccess.outputTypes(resource, "add"));
    }

    @Test
    public void soap12RpcEncodedPreservesEncodingInBothDirections() throws Exception {
        // SOAP 1.2 RPC/encoded 使用 SOAP 1.2 Encoding URI。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("rpc-encoded12.wsdl"));
        String encoding = "http://www.w3.org/2003/05/soap-encoding";

        // SOAP 1.2 action 约束与两方向编码信息分别保留。
        assertEquals("1.2", SoapWsdlResourceAccess.soapVersion(resource));
        assertTrue(SoapWsdlResourceAccess.actionRequired(resource, "add"));
        assertEquals("rpc", SoapBindingStyleAccess.style(resource, "add"));
        assertEquals("encoded", SoapBindingStyleAccess.inputUse(resource, "add"));
        assertEquals("encoded", SoapBindingStyleAccess.outputUse(resource, "add"));
        assertEquals(encoding, SoapBindingStyleAccess.inputEncodingStyle(resource, "add"));
        assertEquals(encoding, SoapBindingStyleAccess.outputEncodingStyle(resource, "add"));
        assertEquals(new QName(RPC_NAMESPACE, "add"), SoapBindingStyleAccess.inputWrapper(resource, "add"));
        assertEquals(new QName(RPC_NAMESPACE, "addResponse"), SoapBindingStyleAccess.outputWrapper(resource, "add"));
        assertEquals(List.of(new QName(XSD_NAMESPACE, "int")), SoapBindingStyleAccess.outputTypes(resource, "add"));
    }

    @Test
    public void rpcOperationStyleOverridesDocumentBinding() throws Exception {
        // binding 默认 document，add operation 明确覆盖为 rpc。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("operation-style.wsdl"));

        // 使用 operation 的样式解析 type part 和 RPC wrapper。
        assertEquals("rpc", SoapBindingStyleAccess.style(resource, "add"));
        assertEquals(new QName(RPC_NAMESPACE, "add"), SoapBindingStyleAccess.inputWrapper(resource, "add"));
        assertEquals(List.of(new QName("left"), new QName("right")), //
                SoapBindingStyleAccess.inputElements(resource, "add"));
    }

    @Test
    public void documentOperationStyleOverridesRpcBinding() throws Exception {
        // binding 默认 rpc，echo operation 明确覆盖为 document。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("document-operation-style.wsdl"));
        QName input = new QName("urn:meteor:test:styles:override", "echoRequest");

        // document part 引用的全局元素直接成为 Body 子元素。
        assertEquals("document", SoapBindingStyleAccess.style(resource, "echo"));
        assertNull(SoapBindingStyleAccess.inputWrapper(resource, "echo"));
        assertNull(SoapBindingStyleAccess.outputWrapper(resource, "echo"));
        assertEquals(List.of(input), SoapBindingStyleAccess.inputElements(resource, "echo"));
    }

    @Test
    public void bodyDispatchExaminesEveryTopLevelElement() throws Exception {
        // 三个 operation 共用首元素 key，仅 number 请求有唯一的第二个元素。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("document-dispatch.wsdl"));
        String namespace = "urn:meteor:test:styles:dispatch";
        List<QName> body = List.of(new QName(namespace, "key"), new QName(namespace, "number"));

        // 没有 action 时，完整 Body 序列仍能唯一定位 storeNumber。
        assertEquals("storeNumber", SoapBindingStyleAccess.resolve(resource, body, null));
    }

    @Test
    public void actionDisambiguatesIdenticalBareBodies() throws Exception {
        // storeText 与 appendText 使用完全相同的 Body 元素，只由 action 区分。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("document-dispatch.wsdl"));
        String namespace = "urn:meteor:test:styles:dispatch";
        List<QName> body = List.of(new QName(namespace, "key"), new QName(namespace, "text"));

        // action 与 Body 同时匹配时，选择对应操作。
        assertEquals("appendText", SoapBindingStyleAccess.resolve(resource, body, namespace + ":appendText"));
    }

    @Test
    public void identicalBodiesWithoutActionAreAmbiguous() throws Exception {
        // 同一个裸 Body 对应两个不同操作，调用没有提供 action。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("document-dispatch.wsdl"));
        String namespace = "urn:meteor:test:styles:dispatch";
        List<QName> body = List.of(new QName(namespace, "key"), new QName(namespace, "text"));

        // 明确报告歧义，不能按 WSDL 声明顺序任意选择。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> SoapBindingStyleAccess.resolve(resource, body, null));
        assertTrue(failure.getMessage().contains("Ambiguous SOAP operation"));
    }

    @Test
    public void matchingActionDoesNotAcceptAnotherOperationsBody() throws Exception {
        // action 指向 storeText，但 Body 声明的是 storeNumber 的参数。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("document-dispatch.wsdl"));
        String namespace = "urn:meteor:test:styles:dispatch";
        List<QName> body = List.of(new QName(namespace, "key"), new QName(namespace, "number"));

        // action 不能掩盖完整 Body 形态不符。
        assertNull(SoapBindingStyleAccess.resolve(resource, body, namespace + ":storeText"));
    }

    @Test
    public void bodyDispatchRejectsUnexpectedTrailingElements() throws Exception {
        // 正确的 key、number 后附带一个额外元素。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("document-dispatch.wsdl"));
        String namespace = "urn:meteor:test:styles:dispatch";
        List<QName> body = List.of(new QName(namespace, "key"), //
                new QName(namespace, "number"), new QName(namespace, "extra"));

        // 仅前缀匹配不代表完整消息匹配。
        assertNull(SoapBindingStyleAccess.resolve(resource, body, namespace + ":storeNumber"));
    }

    @Test
    public void emptyBodyCanResolveItsOperationByAction() throws Exception {
        // 空消息没有根 QName，可通过 action 定位其 request-response 操作。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("document-empty.wsdl"));
        String action = "urn:meteor:test:styles:empty:ping";

        // 空 Body 参与正常操作匹配，不需虚构 operation wrapper。
        assertEquals("ping", SoapBindingStyleAccess.resolve(resource, List.of(), action));
    }

    @Test
    public void rpcParameterOrderDefinesTheJavaArgumentOrder() throws Exception {
        // 抽象消息按 left/right 声明，RPC 签名明确要求 right/left。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("rpc-parameter-order.wsdl"));

        // Java 参数映射遵循 RPC 签名，不能交换位置导致非交换运算出错。
        assertArrayEquals(new String[] { "right", "left" }, //
                SoapBindingStyleAccess.inputPartNames(resource, "add"));
        assertEquals(List.of(new QName("", "right"), new QName("", "left")), //
                SoapBindingStyleAccess.inputElements(resource, "add"));
    }

    @Test
    public void soap12RpcBodyRejectsRelativeNamespace() {
        // SOAP 1.2 RPC input body 使用相对 namespace，输出 body 仍为合法绝对 URI。
        URL wsdl = this.resource("rpc-relative-namespace12.wsdl");

        // 资源加载阶段拒绝不能作为 RPC wrapper 命名空间的相对 URI。
        IOException failure = assertThrows(IOException.class, () -> new SoapWsdlResource(wsdl));
        assertTrue(failure.getMessage().contains("absolute URI"));
    }

    @Test
    public void rpcPartRejectsUnknownSchemaType() {
        // left part 引用已声明 namespace 内不存在的 Missing 类型。
        URL wsdl = this.resource("rpc-unknown-type.wsdl");

        // QName 前缀合法也不能替代 XML Schema 类型存在性校验。
        IOException failure = assertThrows(IOException.class, () -> new SoapWsdlResource(wsdl));
        assertTrue(failure.getMessage().contains("unknown XML Schema type"));
    }

    @Test
    public void rpcInputRejectsDuplicateSoapBodies() {
        // 同一个 binding input 中重复声明两个内容相同的 soap:body。
        URL wsdl = this.resource("rpc-duplicate-body.wsdl");

        // 重复绑定必须明确失败，不能静默选择第一个 body。
        IOException failure = assertThrows(IOException.class, () -> new SoapWsdlResource(wsdl));
        assertTrue(failure.getMessage().contains("Duplicate WSDL binding element"));
    }

    @Test
    public void soap11DocumentEncodedUsesNamespacedTypePartsWithoutWrapper() throws Exception {
        // SOAP 1.1 document/encoded 的部件引用 XSD 类型，body namespace 决定顶层 accessor 的 QName。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("document-encoded11.wsdl"));
        String encoding = "http://schemas.xmlsoap.org/soap/encoding/";
        List<QName> parameters = List.of(new QName(RPC_NAMESPACE, "left"), new QName(RPC_NAMESPACE, "right"));
        List<QName> types = List.of(new QName(XSD_NAMESPACE, "int"), new QName(XSD_NAMESPACE, "int"));

        // 请求和响应均直接放置有命名空间的 part，不引入 RPC operation wrapper。
        assertEquals("1.1", SoapWsdlResourceAccess.soapVersion(resource));
        assertEquals("document", SoapBindingStyleAccess.style(resource, "add"));
        assertNull(SoapBindingStyleAccess.inputWrapper(resource, "add"));
        assertNull(SoapBindingStyleAccess.outputWrapper(resource, "add"));
        assertEquals("encoded", SoapBindingStyleAccess.inputUse(resource, "add"));
        assertEquals("encoded", SoapBindingStyleAccess.outputUse(resource, "add"));
        assertEquals(encoding, SoapBindingStyleAccess.inputEncodingStyle(resource, "add"));
        assertEquals(encoding, SoapBindingStyleAccess.outputEncodingStyle(resource, "add"));
        assertEquals(parameters, SoapBindingStyleAccess.inputElements(resource, "add"));
        assertEquals(types, SoapBindingStyleAccess.inputTypes(resource, "add"));
        assertEquals(List.of(new QName(RPC_NAMESPACE, "result")), //
                SoapBindingStyleAccess.outputElements(resource, "add"));
        assertEquals(List.of(new QName(XSD_NAMESPACE, "int")), SoapBindingStyleAccess.outputTypes(resource, "add"));
    }

    @Test
    public void soap12DocumentEncodedInfersEncodingForGlobalElementParts() throws Exception {
        // SOAP 1.2 document/encoded 引用全局元素，两个 body 都省略 encodingStyle。
        SoapWsdlResource resource = new SoapWsdlResource(this.resource("document-encoded12.wsdl"));
        String encoding = "http://www.w3.org/2003/05/soap-encoding";
        QName request = new QName("urn:meteor:test:styles:bare", "echo");
        QName response = new QName("urn:meteor:test:styles:bare", "echoResponse");

        // 保留全局元素的 QName，并将缺省 SOAP 1.2 Encoding 记录到两个方向的 Body 模型。
        assertEquals("1.2", SoapWsdlResourceAccess.soapVersion(resource));
        assertEquals("document", SoapBindingStyleAccess.style(resource, "echo"));
        assertNull(SoapBindingStyleAccess.inputWrapper(resource, "echo"));
        assertNull(SoapBindingStyleAccess.outputWrapper(resource, "echo"));
        assertEquals("encoded", SoapBindingStyleAccess.inputUse(resource, "echo"));
        assertEquals("encoded", SoapBindingStyleAccess.outputUse(resource, "echo"));
        assertEquals(encoding, SoapBindingStyleAccess.inputEncodingStyle(resource, "echo"));
        assertEquals(encoding, SoapBindingStyleAccess.outputEncodingStyle(resource, "echo"));
        assertEquals(List.of(request), SoapBindingStyleAccess.inputElements(resource, "echo"));
        assertEquals(List.of(response), SoapBindingStyleAccess.outputElements(resource, "echo"));
        assertEquals(List.of(new QName(XSD_NAMESPACE, "string")), SoapBindingStyleAccess.inputTypes(resource, "echo"));
        assertEquals(List.of(new QName(XSD_NAMESPACE, "string")), SoapBindingStyleAccess.outputTypes(resource, "echo"));
    }

    @Test
    public void soap12DocumentEncodedRejectsExplicitEncodingStyle() {
        // SOAP 1.2 document/encoded input body 显式声明 encodingStyle，其余定义保持合法。
        URL wsdl = this.resource("document-explicit-encoding12.wsdl");

        // document binding 不允许这个属性，不能因它等于缺省 Encoding URI 而接受。
        IOException failure = assertThrows(IOException.class, () -> new SoapWsdlResource(wsdl));
        assertTrue(failure.getMessage().contains("document binding cannot declare encodingStyle"));
    }

    private URL resource(String name) {
        URL resource = this.getClass().getResource("/wsdl/styles/" + name);
        assertNotNull("Missing WSDL style resource: " + name, resource);
        return resource;
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.xmlrpc.codec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.protocol.xmlrpc.XmlRpcException;
import org.junit.Test;
import static org.junit.Assert.*;

public class XmlRpcWireDocumentTest {
    @Test
    public void requestRoundTripPreservesMethodAndParameterOrder() throws Exception {
        // 一个调用中所有参数都按声明顺序写入 params。
        XmlRpcWireCodec codec = this.codec();
        byte[] request = codec.encodeRequest("sample.service:echo/path", List.of(1, "two", true));

        // 解码结果只描述线格式，不做服务选择。
        XmlRpcCall call = codec.decodeRequest(request);
        assertEquals("sample.service:echo/path", call.methodName());
        assertEquals(List.of(1, "two", true), call.parameters());
    }

    @Test
    public void requestWithoutParamsHasNoParameters() throws Exception {
        // 无参方法可以省略 params 元素。
        String request = "<methodCall><methodName>ping</methodName></methodCall>";
        XmlRpcCall call = this.codec().decodeRequest(this.bytes(request));

        // 省略不表示包含一个空参数。
        assertEquals(List.of(), call.parameters());
    }

    @Test
    public void requestEmptyParamsHasNoParameters() throws Exception {
        // 显式空 params 是无参调用的另一种合法表达。
        String request = "<methodCall><methodName>ping</methodName><params/></methodCall>";
        XmlRpcCall call = this.codec().decodeRequest(this.bytes(request));

        // 空容器不会产生虚假的值。
        assertTrue(call.parameters().isEmpty());
    }

    @Test
    public void decodedParameterListCannotBeChanged() throws Exception {
        // 解码后的调用描述对外暴露只读参数列表。
        XmlRpcWireCodec codec = this.codec();
        XmlRpcCall call = codec.decodeRequest(codec.encodeRequest("echo", List.of("hello")));

        // 防止外部直接改变调用的位置参数。
        assertThrows(UnsupportedOperationException.class, () -> call.parameters().add("other"));
    }

    @Test
    public void faultRoundTripPreservesCodeAndText() throws Exception {
        // faultString 同样按 XML 文本转义。
        XmlRpcWireCodec codec = this.codec();
        byte[] response = codec.encodeFault(23, "bad <argument> & value");

        // 协议 fault 保持专属异常，不变成 XML 解析异常。
        XmlRpcException fault = assertThrows(XmlRpcException.class, () -> codec.decodeResponse(response));
        assertEquals(23, fault.getCode());
        assertEquals("bad <argument> & value", fault.getMessage());
    }

    @Test
    public void commentsAndWhitespaceDoNotChangeStructure() throws Exception {
        // XML 注释和格式化空白可出现在结构节点之间。
        String request = "<?xml version=\"1.0\"?><!--start--><methodCall>\n<methodName>echo</methodName>";
        request += "<params><!--param--><param><value>  <int>7</int>  </value></param></params></methodCall>";
        XmlRpcCall call = this.codec().decodeRequest(this.bytes(request));

        // 类型值两侧空白不变成数据。
        assertEquals(List.of(7), call.parameters());
    }

    @Test
    public void utf16DeclarationControlsInputDecoding() throws Exception {
        // 输入 XML 自己声明编码，解析器读取原始字节。
        String request = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><methodCall><methodName>echo</methodName>";
        request += "<params><param><value>中文</value></param></params></methodCall>";
        XmlRpcCall call = this.codec().decodeRequest(request.getBytes(StandardCharsets.UTF_16));

        // 不提前用 UTF-8 把请求转换为损坏的字符串。
        assertEquals(List.of("中文"), call.parameters());
    }

    @Test
    public void latin1DeclarationControlsInputDecoding() throws Exception {
        // 旧客户端可以携带标准 XML 编码声明。
        String request = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><methodCall><methodName>echo</methodName>";
        request += "<params><param><value>café</value></param></params></methodCall>";
        XmlRpcCall call = this.codec().decodeRequest(request.getBytes(StandardCharsets.ISO_8859_1));

        // 数据字符按文档声明解释。
        assertEquals(List.of("café"), call.parameters());
    }

    @Test
    public void rejectWrongRequestRoot() {
        // 结构不满足 XML-RPC methodCall 契约。
        String xml = "<methodResponse/>";

        // 明确拒绝，不忽略多余信息。
        byte[] body = this.bytes(xml);
        assertThrows(IOException.class, () -> this.codec().decodeRequest(body));
    }

    @Test
    public void rejectMissingMethodName() {
        // 结构不满足 XML-RPC methodCall 契约。
        String xml = "<methodCall><params/></methodCall>";

        // 明确拒绝，不忽略多余信息。
        byte[] body = this.bytes(xml);
        assertThrows(IOException.class, () -> this.codec().decodeRequest(body));
    }

    @Test
    public void rejectEmptyMethodName() {
        // 结构不满足 XML-RPC methodCall 契约。
        String xml = "<methodCall><methodName/></methodCall>";

        // 明确拒绝，不忽略多余信息。
        byte[] body = this.bytes(xml);
        assertThrows(IOException.class, () -> this.codec().decodeRequest(body));
    }

    @Test
    public void rejectInvalidMethodNameCharacters() {
        // 结构不满足 XML-RPC methodCall 契约。
        String xml = "<methodCall><methodName>echo name</methodName></methodCall>";

        // 明确拒绝，不忽略多余信息。
        byte[] body = this.bytes(xml);
        assertThrows(IOException.class, () -> this.codec().decodeRequest(body));
    }

    @Test
    public void rejectUnicodeMethodName() {
        // 结构不满足 XML-RPC methodCall 契约。
        String xml = "<methodCall><methodName>\u65b9\u6cd5</methodName></methodCall>";

        // 明确拒绝，不忽略多余信息。
        byte[] body = this.bytes(xml);
        assertThrows(IOException.class, () -> this.codec().decodeRequest(body));
    }

    @Test
    public void rejectRepeatedMethodName() {
        // 结构不满足 XML-RPC methodCall 契约。
        String xml = "<methodCall><methodName>echo</methodName><methodName>other</methodName></methodCall>";

        // 明确拒绝，不忽略多余信息。
        byte[] body = this.bytes(xml);
        assertThrows(IOException.class, () -> this.codec().decodeRequest(body));
    }

    @Test
    public void rejectRepeatedParams() {
        // 结构不满足 XML-RPC methodCall 契约。
        String xml = "<methodCall><methodName>echo</methodName><params/><params/></methodCall>";

        // 明确拒绝，不忽略多余信息。
        byte[] body = this.bytes(xml);
        assertThrows(IOException.class, () -> this.codec().decodeRequest(body));
    }

    @Test
    public void rejectParamWithoutValue() {
        // 结构不满足 XML-RPC methodCall 契约。
        String xml = "<methodCall><methodName>echo</methodName><params><param/></params></methodCall>";

        // 明确拒绝，不忽略多余信息。
        byte[] body = this.bytes(xml);
        assertThrows(IOException.class, () -> this.codec().decodeRequest(body));
    }

    @Test
    public void rejectParamWithMultipleValues() {
        // 结构不满足 XML-RPC methodCall 契约。
        String values = "<param><value/><value/></param>";
        String xml = "<methodCall><methodName>echo</methodName><params>" + values + "</params></methodCall>";

        // 明确拒绝，不忽略多余信息。
        byte[] body = this.bytes(xml);
        assertThrows(IOException.class, () -> this.codec().decodeRequest(body));
    }

    @Test
    public void rejectUnknownRequestElement() {
        // 结构不满足 XML-RPC methodCall 契约。
        String xml = "<methodCall><methodName>echo</methodName><unexpected/></methodCall>";

        // 明确拒绝，不忽略多余信息。
        byte[] body = this.bytes(xml);
        assertThrows(IOException.class, () -> this.codec().decodeRequest(body));
    }

    @Test
    public void rejectAttribute() {
        // 结构不满足 XML-RPC methodCall 契约。
        String xml = "<methodCall id=\"1\"><methodName>echo</methodName></methodCall>";

        // 明确拒绝，不忽略多余信息。
        byte[] body = this.bytes(xml);
        assertThrows(IOException.class, () -> this.codec().decodeRequest(body));
    }

    @Test
    public void rejectNamespace() {
        // 结构不满足 XML-RPC methodCall 契约。
        String xml = "<methodCall xmlns=\"urn:extension\"><methodName>echo</methodName></methodCall>";

        // 明确拒绝，不忽略多余信息。
        byte[] body = this.bytes(xml);
        assertThrows(IOException.class, () -> this.codec().decodeRequest(body));
    }

    @Test
    public void rejectTypedValueAttribute() {
        // 结构不满足 XML-RPC methodCall 契约。
        String xml = "<methodCall><methodName>echo</methodName>";
        xml += "<params><param><value><int flag=\"x\">1</int></value></param></params></methodCall>";

        // 明确拒绝，不忽略多余信息。
        byte[] body = this.bytes(xml);
        assertThrows(IOException.class, () -> this.codec().decodeRequest(body));
    }

    @Test
    public void rejectTrailingDocument() {
        // 结构不满足 XML-RPC methodCall 契约。
        String xml = "<methodCall><methodName>echo</methodName></methodCall><methodCall/>";

        // 明确拒绝，不忽略多余信息。
        byte[] body = this.bytes(xml);
        assertThrows(IOException.class, () -> this.codec().decodeRequest(body));
    }

    @Test
    public void rejectTrailingText() {
        // 结构不满足 XML-RPC methodCall 契约。
        String xml = "<methodCall><methodName>echo</methodName></methodCall>trailing";

        // 明确拒绝，不忽略多余信息。
        byte[] body = this.bytes(xml);
        assertThrows(IOException.class, () -> this.codec().decodeRequest(body));
    }

    @Test
    public void rejectStructureText() {
        // 结构不满足 XML-RPC methodCall 契约。
        String xml = "<methodCall>text<methodName>echo</methodName></methodCall>";

        // 明确拒绝，不忽略多余信息。
        byte[] body = this.bytes(xml);
        assertThrows(IOException.class, () -> this.codec().decodeRequest(body));
    }

    @Test
    public void rejectIncompleteDocument() {
        // 结构不满足 XML-RPC methodCall 契约。
        String xml = "<methodCall><methodName>echo</methodName>";

        // 明确拒绝，不忽略多余信息。
        byte[] body = this.bytes(xml);
        assertThrows(IOException.class, () -> this.codec().decodeRequest(body));
    }

    @Test
    public void rejectWrongResponseRoot() {
        // 响应信封必须只包含唯一的结果或 fault。
        String xml = "<methodCall/>";
        byte[] body = this.bytes(xml);

        // 无效响应不能作为业务结果返回。
        assertThrows(IOException.class, () -> this.codec().decodeResponse(body));
    }

    @Test
    public void rejectResponseWithoutParams() {
        // 响应信封必须只包含唯一的结果或 fault。
        String xml = "<methodResponse/>";
        byte[] body = this.bytes(xml);

        // 无效响应不能作为业务结果返回。
        assertThrows(IOException.class, () -> this.codec().decodeResponse(body));
    }

    @Test
    public void rejectEmptyResponseParams() {
        // 响应信封必须只包含唯一的结果或 fault。
        String xml = "<methodResponse><params/></methodResponse>";
        byte[] body = this.bytes(xml);

        // 无效响应不能作为业务结果返回。
        assertThrows(IOException.class, () -> this.codec().decodeResponse(body));
    }

    @Test
    public void rejectResponseMultipleParams() {
        // 响应信封必须只包含唯一的结果或 fault。
        String xml = "<methodResponse><params><param><value>one</value></param><";
        xml += "param><value>two</value></param></params></methodResponse>";
        byte[] body = this.bytes(xml);

        // 无效响应不能作为业务结果返回。
        assertThrows(IOException.class, () -> this.codec().decodeResponse(body));
    }

    @Test
    public void rejectBothResponseAndFault() {
        // 响应信封必须只包含唯一的结果或 fault。
        String xml = "<methodResponse><params><param><value>one</value></param></params><fault/></methodResponse>";
        byte[] body = this.bytes(xml);

        // 无效响应不能作为业务结果返回。
        assertThrows(IOException.class, () -> this.codec().decodeResponse(body));
    }

    @Test
    public void rejectNonStructFault() {
        // 响应信封必须只包含唯一的结果或 fault。
        String xml = "<methodResponse><fault><value><int>1</int></value></fault></methodResponse>";
        byte[] body = this.bytes(xml);

        // 无效响应不能作为业务结果返回。
        assertThrows(IOException.class, () -> this.codec().decodeResponse(body));
    }

    @Test
    public void rejectFaultWithStringCode() {
        // faultCode 必须是整数而非看起来像整数的字符串。
        String code = "<member><name>faultCode</name><value>23</value></member>";
        String message = "<member><name>faultString</name><value>bad</value></member>";
        byte[] response = this.fault(code + message);

        // 不隐式转换错误的 fault 类型。
        assertThrows(IOException.class, () -> this.codec().decodeResponse(response));
    }

    @Test
    public void rejectFaultWithNonStringMessage() {
        // faultString 必须是文本。
        String code = "<member><name>faultCode</name><value><int>23</int></value></member>";
        String message = "<member><name>faultString</name><value><int>1</int></value></member>";
        byte[] response = this.fault(code + message);

        // fault 结构错误与合法业务 fault 区分。
        assertThrows(IOException.class, () -> this.codec().decodeResponse(response));
    }

    @Test
    public void rejectFaultMissingMessage() {
        // 原始标准要求 faultCode 与 faultString 都存在。
        String code = "<member><name>faultCode</name><value><int>23</int></value></member>";
        byte[] response = this.fault(code);

        // 不凭空补一个错误信息。
        assertThrows(IOException.class, () -> this.codec().decodeResponse(response));
    }

    @Test
    public void rejectFaultExtraMember() {
        // fault 结构不能携带普通结果或额外扩展字段。
        String code = "<member><name>faultCode</name><value><int>23</int></value></member>";
        String message = "<member><name>faultString</name><value>bad</value></member>";
        String extra = "<member><name>detail</name><value>extra</value></member>";
        byte[] response = this.fault(code + message + extra);

        // 严格区分标准 fault 与自定义封装。
        assertThrows(IOException.class, () -> this.codec().decodeResponse(response));
    }

    @Test
    public void requestEncodingRejectsInvalidMethodName() {
        // 方法名不会通过转义接受标准未定义的字符。
        XmlRpcWireCodec codec = this.codec();

        // 在写入网络之前发现名称问题。
        assertThrows(IOException.class, () -> codec.encodeRequest("bad name", List.of()));
    }

    private byte[] fault(String members) {
        String xml = "<methodResponse><fault><value><struct>" + members;
        xml += "</struct></value></fault></methodResponse>";
        return this.bytes(xml);
    }

    private byte[] bytes(String xml) {
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private XmlRpcWireCodec codec() {
        ProtocolConfig config = ProtocolConfig.newConfig("xmlrpc", "http", Map.of());
        return new XmlRpcWireCodec(config);
    }
}

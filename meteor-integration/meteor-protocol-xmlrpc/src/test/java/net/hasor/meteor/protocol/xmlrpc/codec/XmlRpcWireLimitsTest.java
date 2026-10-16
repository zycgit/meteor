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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.hasor.meteor.connector.ProtocolConfig;
import org.junit.Test;
import static org.junit.Assert.*;

public class XmlRpcWireLimitsTest {
    @Test
    public void rejectDoctype() {
        // 即使 DTD 没有实体引用也不允许进入 XML-RPC 文档。
        String xml = "<!DOCTYPE methodCall><methodCall><methodName>ping</methodName></methodCall>";
        byte[] request = this.bytes(xml);

        // 禁止 DTD 由协议入口统一承担。
        assertThrows(IOException.class, () -> this.codec(Map.of()).decodeRequest(request));
    }

    @Test
    public void rejectExternalEntity() {
        // 解析器不得读取实体指定的本地资源。
        String xml = "<!DOCTYPE methodCall [<!ENTITY secret SYSTEM 'file:///must-not-open'>]>";
        xml += "<methodCall><methodName>echo</methodName><params><param>";
        xml += "<value>&secret;</value></param></params></methodCall>";
        byte[] request = this.bytes(xml);

        // 外部实体在解析阶段失败，不返回实体内容。
        assertThrows(IOException.class, () -> this.codec(Map.of()).decodeRequest(request));
    }

    @Test
    public void rejectInternalEntityExpansion() {
        // 内部 DTD 实体也不能形成展开攻击。
        String xml = "<!DOCTYPE methodCall [<!ENTITY a 'hello'><!ENTITY b '&a;&a;&a;'>]>";
        xml += "<methodCall><methodName>echo</methodName><params><param>";
        xml += "<value>&b;</value></param></params></methodCall>";
        byte[] request = this.bytes(xml);

        // 不因为实体没有访问外部资源而放行。
        assertThrows(IOException.class, () -> this.codec(Map.of()).decodeRequest(request));
    }

    @Test
    public void rejectXml11ControlCharacterExtension() {
        // XML 1.1 允许的部分控制字符不属于插件的 XML 1.0 格式。
        String xml = "<?xml version=\"1.1\"?><methodCall><methodName>echo</methodName><params>";
        xml += "<param><value>&#1;</value></param></params></methodCall>";
        byte[] request = this.bytes(xml);

        // 统一拒绝版本扩展，不让输入通过后又无法按标准输出。
        assertThrows(IOException.class, () -> this.codec(Map.of()).decodeRequest(request));
    }

    @Test
    public void rejectEmptyInput() {
        // 空 HTTP 正文没有 XML-RPC 信封。
        XmlRpcWireCodec codec = this.codec(Map.of());

        // 错误表现为协议解析异常。
        assertThrows(IOException.class, () -> codec.decodeRequest(new byte[0]));
    }

    @Test
    public void rejectOversizedInputBeforeParsing() {
        // 请求字节数已经超过配置上限。
        XmlRpcWireCodec codec = this.codec(Map.of("maxMessageSize", "64"));
        byte[] input = new byte[65];

        // 无需把大正文交给 XML 解析器。
        IOException failure = assertThrows(IOException.class, () -> codec.decodeRequest(input));
        assertTrue(failure.getMessage().contains("maxMessageSize"));
    }

    @Test
    public void outputLimitIncludesXmlEnvelope() {
        // 上限统计完整正文，不能只统计业务字符串。
        XmlRpcWireCodec codec = this.codec(Map.of("maxMessageSize", "128"));
        String value = "x".repeat(128);

        // XML 信封与转义同样消耗消息空间。
        assertThrows(IOException.class, () -> codec.encodeResponse(value));
    }

    @Test
    public void outputLimitCountsUtf8Bytes() {
        // Unicode 文本的字节数可以大于 Java 字符数。
        XmlRpcWireCodec codec = this.codec(Map.of("maxMessageSize", "200"));
        String value = "中".repeat(40);

        // 最终 UTF-8 字节检查防止多字节文本绕过限制。
        assertThrows(IOException.class, () -> codec.encodeResponse(value));
    }

    @Test
    public void outputLimitRejectsLargeBinaryBeforeBase64() {
        // 二进制本身已超过上限，无需再分配更大的 base64 文本。
        XmlRpcWireCodec codec = this.codec(Map.of("maxMessageSize", "128"));
        byte[] value = new byte[129];

        // 发送前明确失败。
        assertThrows(IOException.class, () -> codec.encodeResponse(value));
    }

    @Test
    public void outputLimitCountsBase64Expansion() {
        // 原始字节未超限，但 base64 与 XML 信封会使正文超限。
        XmlRpcWireCodec codec = this.codec(Map.of("maxMessageSize", "200"));
        byte[] value = new byte[100];

        // 不只依据原始数据大小放行。
        assertThrows(IOException.class, () -> codec.encodeResponse(value));
    }

    @Test
    public void depthLimitAcceptsExactBoundary() throws Exception {
        // 顶层数组深度一，数组里的标量深度二。
        XmlRpcWireCodec codec = this.codec(Map.of("maxDepth", "2"));
        byte[] response = codec.encodeResponse(List.of(1));

        // 边界内的结构允许往返。
        assertEquals(List.of(1), codec.decodeResponse(response));
    }

    @Test
    public void depthLimitRejectsNestedInput() throws Exception {
        // 正常编码器产生一个深度三的合法文档。
        byte[] response = this.codec(Map.of()).encodeResponse(List.of(List.of(1)));
        XmlRpcWireCodec limited = this.codec(Map.of("maxDepth", "2"));

        // 接收方按自己的最大嵌套限制拒绝。
        assertThrows(IOException.class, () -> limited.decodeResponse(response));
    }

    @Test
    public void depthLimitRejectsNestedOutput() {
        // 发送端也须限制复合值深度。
        XmlRpcWireCodec codec = this.codec(Map.of("maxDepth", "2"));
        List<?> value = List.of(List.of(1));

        // 不先递归到栈溢出后才失败。
        assertThrows(IOException.class, () -> codec.encodeResponse(value));
    }

    @Test
    public void rejectListCycle() {
        // 列表直接引用自身，无法表达为 XML 树。
        List<Object> value = new ArrayList<>();
        value.add(value);
        XmlRpcWireCodec codec = this.codec(Map.of());

        // 按活动路径检测循环引用。
        IOException failure = assertThrows(IOException.class, () -> codec.encodeResponse(value));
        assertTrue(failure.getMessage().contains("cycles"));
    }

    @Test
    public void rejectMapCycle() {
        // 结构体的间接引用同样不能进入线格式。
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("again", List.of(value));
        XmlRpcWireCodec codec = this.codec(Map.of());

        // 不依赖达到最大深度才发现循环。
        IOException failure = assertThrows(IOException.class, () -> codec.encodeResponse(value));
        assertTrue(failure.getMessage().contains("cycles"));
    }

    @Test
    public void repeatedReferenceWithoutCycleIsAllowed() throws Exception {
        // 同一个子结构出现在两个位置，不构成祖先循环。
        List<?> shared = List.of("value");
        List<?> value = List.of(shared, shared);
        XmlRpcWireCodec codec = this.codec(Map.of());
        byte[] response = codec.encodeResponse(value);

        // 每个位置分别生成值，不要求对象身份协议。
        assertEquals(value, codec.decodeResponse(response));
    }

    @Test
    public void rejectXmlControlCharacterOutput() {
        // XML 1.0 无法用字符引用合法表达 NUL。
        XmlRpcWireCodec codec = this.codec(Map.of());
        String value = "before" + (char) 0 + "after";

        // 不生成解析器无法读取的正文。
        assertThrows(IOException.class, () -> codec.encodeResponse(value));
    }

    @Test
    public void rejectUnpairedSurrogateOutput() {
        // 不完整代理项不能静默替换为问号。
        XmlRpcWireCodec codec = this.codec(Map.of());
        String value = String.valueOf((char) 0xD800);

        // 编码在 UTF-8 替换非法字符之前失败。
        assertThrows(IOException.class, () -> codec.encodeResponse(value));
    }

    @Test
    public void rejectXmlNonCharacterOutput() {
        // BMP 中 XML 1.0 明确禁止的非字符不可输出。
        XmlRpcWireCodec codec = this.codec(Map.of());
        String value = String.valueOf((char) 0xFFFF);

        // 校验遵循 XML 的字符范围。
        assertThrows(IOException.class, () -> codec.encodeResponse(value));
    }

    @Test
    public void largeBase64UsesBoundedLinearDecoding() throws Exception {
        // 较大的合法二进制值不应触发正则递归或栈溢出。
        byte[] value = new byte[128 * 1024];
        XmlRpcWireCodec codec = this.codec(Map.of());
        byte[] response = codec.encodeResponse(value);

        // 完整二进制往返，保留消息上限保护。
        assertArrayEquals(value, (byte[]) codec.decodeResponse(response));
    }

    @Test
    public void rejectZeroMessageLimit() {
        // 消息大小必须为正数。
        Map<String, String> options = Map.of("maxMessageSize", "0");

        // 配置阶段暴露参数错误。
        assertThrows(IllegalArgumentException.class, () -> this.codec(options));
    }

    @Test
    public void rejectNegativeDepthLimit() {
        // 深度上限必须为正数。
        Map<String, String> options = Map.of("maxDepth", "-1");

        // 配置阶段暴露参数错误。
        assertThrows(IllegalArgumentException.class, () -> this.codec(options));
    }

    @Test
    public void rejectNonNumericLimit() {
        // 配置项不接受隐式字符串转换。
        Map<String, String> options = Map.of("maxDepth", "deep");

        // 错误信息保留具体参数名。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> this.codec(options));
        assertTrue(failure.getMessage().contains("maxDepth"));
    }

    @Test
    public void rejectUnknownNilFlag() {
        // nil 开关不接受错误拼写被静默当作 false。
        Map<String, String> options = Map.of("allowNil", "enabled");

        // 配置阶段报告输入问题。
        assertThrows(IllegalArgumentException.class, () -> this.codec(options));
    }

    @Test
    public void rejectNullFaultMessageWithNilEnabled() {
        // nil 扩展只影响普通值，不改变标准 fault 的必填字符串。
        XmlRpcWireCodec codec = this.codec(Map.of("allowNil", "true"));

        // 不输出自己的解码器也会拒绝的 fault。
        assertThrows(IOException.class, () -> codec.encodeFault(1, null));
    }

    private byte[] bytes(String xml) {
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private XmlRpcWireCodec codec(Map<String, String> options) {
        ProtocolConfig config = ProtocolConfig.newConfig("xmlrpc", "http", options);
        return new XmlRpcWireCodec(config);
    }
}

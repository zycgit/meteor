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
import java.time.Instant;
import java.util.*;
import net.hasor.meteor.connector.ProtocolConfig;
import org.junit.Test;
import static org.junit.Assert.*;

public class XmlRpcWireValuesTest {
    @Test
    public void intMaximum() throws Exception {
        // 准备标准值报文，解码后保留对应的 Java 值类型。
        String xml = "<int>2147483647</int>";
        Object value = this.decode(xml);

        // 校验这一种值的协议解释。
        assertEquals(Integer.MAX_VALUE, value);
    }

    @Test
    public void intMinimum() throws Exception {
        // 准备标准值报文，解码后保留对应的 Java 值类型。
        String xml = "<int>-2147483648</int>";
        Object value = this.decode(xml);

        // 校验这一种值的协议解释。
        assertEquals(Integer.MIN_VALUE, value);
    }

    @Test
    public void i4Alias() throws Exception {
        // 准备标准值报文，解码后保留对应的 Java 值类型。
        String xml = "<i4>42</i4>";
        Object value = this.decode(xml);

        // 校验这一种值的协议解释。
        assertEquals(42, value);
    }

    @Test
    public void integerLeadingPlus() throws Exception {
        // 准备标准值报文，解码后保留对应的 Java 值类型。
        String xml = "<int>+12</int>";
        Object value = this.decode(xml);

        // 校验这一种值的协议解释。
        assertEquals(12, value);
    }

    @Test
    public void integerLeadingZero() throws Exception {
        // 准备标准值报文，解码后保留对应的 Java 值类型。
        String xml = "<int>00012</int>";
        Object value = this.decode(xml);

        // 校验这一种值的协议解释。
        assertEquals(12, value);
    }

    @Test
    public void trueBoolean() throws Exception {
        // 准备标准值报文，解码后保留对应的 Java 值类型。
        String xml = "<boolean>1</boolean>";
        Object value = this.decode(xml);

        // 校验这一种值的协议解释。
        assertEquals(true, value);
    }

    @Test
    public void falseBoolean() throws Exception {
        // 准备标准值报文，解码后保留对应的 Java 值类型。
        String xml = "<boolean>0</boolean>";
        Object value = this.decode(xml);

        // 校验这一种值的协议解释。
        assertEquals(false, value);
    }

    @Test
    public void doubleDecimal() throws Exception {
        // 准备标准值报文，解码后保留对应的 Java 值类型。
        String xml = "<double>-12.25</double>";
        Object value = this.decode(xml);

        // 校验这一种值的协议解释。
        assertEquals(-12.25d, value);
    }

    @Test
    public void doubleExponentCompatibility() throws Exception {
        // 准备标准值报文，解码后保留对应的 Java 值类型。
        String xml = "<double>1.25e3</double>";
        Object value = this.decode(xml);

        // 校验这一种值的协议解释。
        assertEquals(1250.0d, value);
    }

    @Test
    public void emptyString() throws Exception {
        // 准备标准值报文，解码后保留对应的 Java 值类型。
        String xml = "<string/>";
        Object value = this.decode(xml);

        // 校验这一种值的协议解释。
        assertEquals("", value);
    }

    @Test
    public void implicitString() throws Exception {
        // 准备标准值报文，解码后保留对应的 Java 值类型。
        String xml = "  hello  ";
        Object value = this.decode(xml);

        // 校验这一种值的协议解释。
        assertEquals("  hello  ", value);
    }

    @Test
    public void emptyImplicitString() throws Exception {
        // 准备标准值报文，解码后保留对应的 Java 值类型。
        String xml = "";
        Object value = this.decode(xml);

        // 校验这一种值的协议解释。
        assertEquals("", value);
    }

    @Test
    public void escapedString() throws Exception {
        // 准备标准值报文，解码后保留对应的 Java 值类型。
        String xml = "<string>&lt;&amp;&gt;</string>";
        Object value = this.decode(xml);

        // 校验这一种值的协议解释。
        assertEquals("<&>", value);
    }

    @Test
    public void cdataString() throws Exception {
        // 准备标准值报文，解码后保留对应的 Java 值类型。
        String xml = "<string><![CDATA[<raw>]]></string>";
        Object value = this.decode(xml);

        // 校验这一种值的协议解释。
        assertEquals("<raw>", value);
    }

    @Test
    public void emptyArray() throws Exception {
        // 准备标准值报文，解码后保留对应的 Java 值类型。
        String xml = "<array><data/></array>";
        Object value = this.decode(xml);

        // 校验这一种值的协议解释。
        assertEquals(List.of(), value);
    }

    @Test
    public void emptyStruct() throws Exception {
        // 准备标准值报文，解码后保留对应的 Java 值类型。
        String xml = "<struct/>";
        Object value = this.decode(xml);

        // 校验这一种值的协议解释。
        assertEquals(Map.of(), value);
    }

    @Test
    public void rejectIntegerOverflow() {
        // 非法值必须在进入 Java 服务参数绑定之前失败。
        String xml = "<int>2147483648</int>";

        // 不把错误值静默转换成合法结果。
        assertThrows(IOException.class, () -> this.decode(xml));
    }

    @Test
    public void rejectIntegerUnderflow() {
        // 非法值必须在进入 Java 服务参数绑定之前失败。
        String xml = "<int>-2147483649</int>";

        // 不把错误值静默转换成合法结果。
        assertThrows(IOException.class, () -> this.decode(xml));
    }

    @Test
    public void rejectIntegerWhitespace() {
        // 非法值必须在进入 Java 服务参数绑定之前失败。
        String xml = "<int> 1</int>";

        // 不把错误值静默转换成合法结果。
        assertThrows(IOException.class, () -> this.decode(xml));
    }

    @Test
    public void rejectIntegerDecimal() {
        // 非法值必须在进入 Java 服务参数绑定之前失败。
        String xml = "<int>1.0</int>";

        // 不把错误值静默转换成合法结果。
        assertThrows(IOException.class, () -> this.decode(xml));
    }

    @Test
    public void rejectBooleanWord() {
        // 非法值必须在进入 Java 服务参数绑定之前失败。
        String xml = "<boolean>true</boolean>";

        // 不把错误值静默转换成合法结果。
        assertThrows(IOException.class, () -> this.decode(xml));
    }

    @Test
    public void rejectBooleanOtherNumber() {
        // 非法值必须在进入 Java 服务参数绑定之前失败。
        String xml = "<boolean>2</boolean>";

        // 不把错误值静默转换成合法结果。
        assertThrows(IOException.class, () -> this.decode(xml));
    }

    @Test
    public void rejectDoubleNan() {
        // 非法值必须在进入 Java 服务参数绑定之前失败。
        String xml = "<double>NaN</double>";

        // 不把错误值静默转换成合法结果。
        assertThrows(IOException.class, () -> this.decode(xml));
    }

    @Test
    public void rejectDoubleInfinity() {
        // 非法值必须在进入 Java 服务参数绑定之前失败。
        String xml = "<double>1e999</double>";

        // 不把错误值静默转换成合法结果。
        assertThrows(IOException.class, () -> this.decode(xml));
    }

    @Test
    public void rejectDoubleHexadecimal() {
        // 非法值必须在进入 Java 服务参数绑定之前失败。
        String xml = "<double>0x1.0p1</double>";

        // 不把错误值静默转换成合法结果。
        assertThrows(IOException.class, () -> this.decode(xml));
    }

    @Test
    public void rejectDoubleSuffix() {
        // 非法值必须在进入 Java 服务参数绑定之前失败。
        String xml = "<double>1.0d</double>";

        // 不把错误值静默转换成合法结果。
        assertThrows(IOException.class, () -> this.decode(xml));
    }

    @Test
    public void rejectInvalidDate() {
        // 非法值必须在进入 Java 服务参数绑定之前失败。
        String xml = "<dateTime.iso8601>20260230T12:00:00</dateTime.iso8601>";

        // 不把错误值静默转换成合法结果。
        assertThrows(IOException.class, () -> this.decode(xml));
    }

    @Test
    public void rejectDateOffset() {
        // 非法值必须在进入 Java 服务参数绑定之前失败。
        String xml = "<dateTime.iso8601>20261016T12:00:00Z</dateTime.iso8601>";

        // 不把错误值静默转换成合法结果。
        assertThrows(IOException.class, () -> this.decode(xml));
    }

    @Test
    public void rejectBase64InvalidCharacter() {
        // 非法值必须在进入 Java 服务参数绑定之前失败。
        String xml = "<base64>YW!j</base64>";

        // 不把错误值静默转换成合法结果。
        assertThrows(IOException.class, () -> this.decode(xml));
    }

    @Test
    public void rejectBase64MissingPadding() {
        // 非法值必须在进入 Java 服务参数绑定之前失败。
        String xml = "<base64>YQ</base64>";

        // 不把错误值静默转换成合法结果。
        assertThrows(IOException.class, () -> this.decode(xml));
    }

    @Test
    public void rejectI8Extension() {
        // 非法值必须在进入 Java 服务参数绑定之前失败。
        String xml = "<i8>1</i8>";

        // 不把错误值静默转换成合法结果。
        assertThrows(IOException.class, () -> this.decode(xml));
    }

    @Test
    public void rejectNilByDefault() {
        // 非法值必须在进入 Java 服务参数绑定之前失败。
        String xml = "<nil/>";

        // 不把错误值静默转换成合法结果。
        assertThrows(IOException.class, () -> this.decode(xml));
    }

    @Test
    public void rejectScalarChildren() {
        // 非法值必须在进入 Java 服务参数绑定之前失败。
        String xml = "<string><int>1</int></string>";

        // 不把错误值静默转换成合法结果。
        assertThrows(IOException.class, () -> this.decode(xml));
    }

    @Test
    public void rejectValueMixedText() {
        // 非法值必须在进入 Java 服务参数绑定之前失败。
        String xml = "prefix<int>1</int>";

        // 不把错误值静默转换成合法结果。
        assertThrows(IOException.class, () -> this.decode(xml));
    }

    @Test
    public void rejectMultipleTypedChildren() {
        // 非法值必须在进入 Java 服务参数绑定之前失败。
        String xml = "<int>1</int><int>2</int>";

        // 不把错误值静默转换成合法结果。
        assertThrows(IOException.class, () -> this.decode(xml));
    }

    @Test
    public void rejectArrayMissingData() {
        // 非法值必须在进入 Java 服务参数绑定之前失败。
        String xml = "<array><value><int>1</int></value></array>";

        // 不把错误值静默转换成合法结果。
        assertThrows(IOException.class, () -> this.decode(xml));
    }

    @Test
    public void rejectStructWrongMemberOrder() {
        // 非法值必须在进入 Java 服务参数绑定之前失败。
        String xml = "<struct><member><value><int>1</int></value><name>a</name></member></struct>";

        // 不把错误值静默转换成合法结果。
        assertThrows(IOException.class, () -> this.decode(xml));
    }

    @Test
    public void compactDateUsesUtc() throws Exception {
        // XML-RPC 的无偏移日期按本插件约定解释为 UTC。
        Object value = this.decode("<dateTime.iso8601>20261016T12:34:56</dateTime.iso8601>");

        // 不依赖测试机器的默认时区。
        assertEquals(Date.from(Instant.parse("2026-10-16T12:34:56Z")), value);
    }

    @Test
    public void base64AcceptsXmlWhitespace() throws Exception {
        // 换行与缩进可以出现在 base64 正文中。
        Object value = this.decode("<base64> YW\nJj\tZA== </base64>");

        // 仅 XML 空白被忽略，结果仍然是原始字节。
        assertArrayEquals(new byte[] { 97, 98, 99, 100 }, (byte[]) value);
    }

    @Test
    public void emptyBase64ProducesEmptyBytes() throws Exception {
        // 空二进制值是一个有效值。
        Object value = this.decode("<base64/>");

        // 与空字符串的类型不同。
        assertArrayEquals(new byte[0], (byte[]) value);
    }

    @Test
    public void nestedCompoundValuesPreserveOrder() throws Exception {
        // 一个数组可以同时包含结构体和标量。
        String struct = "<struct><member><name>first</name><value><int>1</int></value></member>";
        struct += "<member><name>second</name><value><boolean>1</boolean></value></member></struct>";
        String xml = "<array><data><value>" + struct + "</value><value>tail</value></data></array>";
        List<?> values = (List<?>) this.decode(xml);

        // 数组顺序和结构体成员顺序均保持在解码对象上。
        Map<?, ?> record = (Map<?, ?>) values.get(0);
        assertEquals(List.of("first", "second"), new ArrayList<>(record.keySet()));
        assertEquals("tail", values.get(1));
    }

    @Test
    public void duplicateStructMemberRejected() {
        // 相同名字出现两次会产生歧义，不能采取后者覆盖。
        String member = "<member><name>same</name><value><int>1</int></value></member>";
        String xml = "<struct>" + member + member + "</struct>";

        // 即便值相同也拒绝重复键。
        assertThrows(IOException.class, () -> this.decode(xml));
    }

    @Test
    public void nilRequiresExplicitOption() throws Exception {
        // 显式允许 nil 后，null 可以作为普通值往返。
        XmlRpcWireCodec codec = this.codec(Map.of("allowNil", "true"));
        byte[] response = codec.encodeResponse(null);

        // 空值不会被转换为空字符串。
        assertNull(codec.decodeResponse(response));
    }

    @Test
    public void nilCannotContainText() {
        // 开启 nil 扩展仍然要求元素为空。
        XmlRpcWireCodec codec = this.codec(Map.of("allowNil", "true"));
        byte[] response = this.response("<nil>value</nil>");

        // 不丢弃非法内容。
        assertThrows(IOException.class, () -> codec.decodeResponse(response));
    }

    @Test
    public void stringRoundTripPreservesUnicodeAndCarriageReturn() throws Exception {
        // XML 特殊字符、补充平面字符和 CR 均须保持文本原值。
        String text = "中文<&>\r\n\t😀";
        XmlRpcWireCodec codec = this.codec(Map.of());
        byte[] response = codec.encodeResponse(text);

        // CR 使用字符引用避免 XML 的行尾归一化改变数据。
        assertEquals(text, codec.decodeResponse(response));
    }

    @Test
    public void binaryRoundTripPreservesAllByteValues() throws Exception {
        // 二进制内容不能经过字符串字符集转换。
        byte[] binary = new byte[] { 0, 1, -1, -128, 127 };
        XmlRpcWireCodec codec = this.codec(Map.of());
        byte[] response = codec.encodeResponse(binary);

        // 编解码后每个字节相同。
        assertArrayEquals(binary, (byte[]) codec.decodeResponse(response));
    }

    @Test
    public void dateRoundTripUsesSecondPrecision() throws Exception {
        // 线格式只携带秒，毫秒精度按协议省略。
        Date date = Date.from(Instant.parse("2026-10-16T12:34:56.789Z"));
        XmlRpcWireCodec codec = this.codec(Map.of());
        byte[] response = codec.encodeResponse(date);

        // 固定 UTC 后仍然遵守协议的秒级精度。
        assertEquals(Date.from(Instant.parse("2026-10-16T12:34:56Z")), codec.decodeResponse(response));
    }

    @Test
    public void doubleEncodingUsesPlainDecimal() throws Exception {
        // 避免向只实现原始规范的客户端输出指数写法。
        XmlRpcWireCodec codec = this.codec(Map.of());
        byte[] response = codec.encodeResponse(1.0e-10);
        String xml = new String(response, StandardCharsets.UTF_8);

        // 输出为十进制正文，同时数值能往返。
        assertTrue(xml.contains("<double>0.00000000010</double>"));
        assertEquals(1.0e-10, codec.decodeResponse(response));
    }

    @Test
    public void negativeZeroDoubleRoundTrip() throws Exception {
        // IEEE 负零不能在格式化时变成整数或正零。
        XmlRpcWireCodec codec = this.codec(Map.of());
        byte[] response = codec.encodeResponse(-0.0d);

        // Double.equals 区分正负零。
        assertEquals(-0.0d, codec.decodeResponse(response));
    }

    @Test
    public void structEncodingPreservesMemberNames() throws Exception {
        // 结构体名称按普通 XML 文本转义。
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("<&>\r", List.of(7, true, "text"));
        XmlRpcWireCodec codec = this.codec(Map.of());
        byte[] response = codec.encodeResponse(value);

        // 名字及异构数组内容均往返。
        assertEquals(value, codec.decodeResponse(response));
    }

    @Test
    public void boundedLongEncodesStandardInt() throws Exception {
        // Java Long 在标准范围内仍使用 XML-RPC int。
        XmlRpcWireCodec codec = this.codec(Map.of());
        byte[] response = codec.encodeResponse(7L);

        // 不输出非标准 i8 标签。
        assertEquals(7, codec.decodeResponse(response));
    }

    @Test
    public void rejectLongOutsideStandardRange() {
        // 不通过截断或 i8 扩展静默改变整数语义。
        XmlRpcWireCodec codec = this.codec(Map.of());

        // 范围错误在发送前被调用方看到。
        assertThrows(IOException.class, () -> codec.encodeResponse(Long.MAX_VALUE));
    }

    @Test
    public void rejectInfiniteDoubleEncoding() {
        // 非有限浮点值不属于标准 XML-RPC double。
        XmlRpcWireCodec codec = this.codec(Map.of());

        // 不能把它输出成字符串或非法 XML 数字。
        assertThrows(IOException.class, () -> codec.encodeResponse(Double.POSITIVE_INFINITY));
    }

    @Test
    public void rejectNullEncodingByDefault() {
        // 默认遵守原始 XML-RPC 类型集合。
        XmlRpcWireCodec codec = this.codec(Map.of());

        // 调用者需要明确开启 nil 扩展。
        assertThrows(IOException.class, () -> codec.encodeResponse(null));
    }

    @Test
    public void rejectNonStringStructKey() {
        // 结构体成员名称必须是字符串。
        XmlRpcWireCodec codec = this.codec(Map.of());

        // 不使用 toString 将不合法键隐式变成线格式。
        assertThrows(IOException.class, () -> codec.encodeResponse(Map.of(1, "value")));
    }

    @Test
    public void rejectUnsupportedObjectEncoding() {
        // Java 对象绑定由上层 codec 负责，wire 不猜测对象表示。
        XmlRpcWireCodec codec = this.codec(Map.of());

        // 原始 Object 不能进入线编码。
        assertThrows(IOException.class, () -> codec.encodeResponse(new Object()));
    }

    private Object decode(String value) throws IOException {
        return this.codec(Map.of()).decodeResponse(this.response(value));
    }

    private byte[] response(String value) {
        String xml = "<methodResponse><params><param><value>" + value;
        xml += "</value></param></params></methodResponse>";
        return xml.getBytes(StandardCharsets.UTF_8);
    }

    private XmlRpcWireCodec codec(Map<String, String> options) {
        ProtocolConfig config = ProtocolConfig.newConfig("xmlrpc", "http", options);
        return new XmlRpcWireCodec(config);
    }
}

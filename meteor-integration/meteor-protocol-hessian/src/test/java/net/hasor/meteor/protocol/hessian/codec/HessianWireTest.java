/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hessian.codec;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import com.caucho.hessian.io.Hessian2Output;
import com.caucho.hessian.io.HessianOutput;
import com.caucho.hessian.io.HessianRemote;
import com.caucho.hessian.io.HessianServiceException;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.protocol.hessian.codec.HessianCodecTestFixture.BusinessException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class HessianWireTest {
    private HessianCodecTestFixture fixture;

    @Before
    public void prepareMetadata() throws Exception {
        this.fixture = new HessianCodecTestFixture();
    }

    @After
    public void closeContext() throws Exception {
        this.fixture.close();
    }

    @Test
    public void hessianOneCallSelectsOneResponse() throws Exception {
        // Hessian 1 请求头声明服务端使用同一版本回应。
        byte[] body = HessianCodecTestFixture.call(1, "ping");
        assertEquals(1, this.fixture.codec.responseVersion(body));
    }

    @Test
    public void legacyCallCanRequestHessianTwoResponse() throws Exception {
        // Caucho 的 c2 请求仍采用 Hessian 1 参数格式，但要求 Hessian 2 响应。
        byte[] body = HessianCodecTestFixture.call(1, "ping");
        body[1] = 2;
        assertEquals(2, this.fixture.codec.responseVersion(body));
        assertEquals("ping", this.fixture.decode(body).getTargetMethod());
    }

    @Test
    public void historicalStartCallWithHeaderKeepsHessianOneSemantics() throws Exception {
        // 官方无参 startCall 写出 c 0 1，扩展头位于方法名之前。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        HessianOutput output = new HessianOutput(bytes);
        output.startCall();
        output.writeHeader("trace");
        output.writeString("trace-42");
        output.writeMethod("subtract");
        output.writeInt(7);
        output.writeInt(2);
        output.completeCall();
        output.flush();
        byte[] body = bytes.toByteArray();

        // 历史版本头仍选择 Hessian 1 响应，头部不能被错当作方法参数。
        assertArrayEquals(new byte[] { 'c', 0, 1 }, Arrays.copyOf(body, 3));
        assertEquals(1, this.fixture.codec.responseVersion(body));
        RequestPayload request = this.fixture.decode(body);
        assertEquals("subtract", request.getTargetMethod());
        assertEquals(Arrays.asList(7, 2), request.getParameterValues());
    }

    @Test
    public void hessianTwoCallSelectsTwoResponse() throws Exception {
        // Hessian 2 的 H2C 信封携带明确版本。
        byte[] body = HessianCodecTestFixture.call(2, "ping");
        assertEquals(2, this.fixture.codec.responseVersion(body));
    }

    @Test
    public void defaultOutboundCallUsesHessianTwo() throws Exception {
        // 默认发送格式与当前标准 Hessian 2 对齐。
        RequestPayload request = this.fixture.request("ping", new Class<?>[0]);
        byte[] body = this.fixture.codec.encodeRequest(request);
        assertArrayEquals(new byte[] { 'H', 2, 0 }, Arrays.copyOf(body, 3));
    }

    @Test
    public void configuredOutboundCallUsesHessianOne() throws Exception {
        // 对旧服务端可以显式选择 Hessian 1。
        HessianCodec codec = this.codec(Map.of("version", "1"));
        RequestPayload request = this.fixture.request("ping", new Class<?>[0]);
        byte[] body = codec.encodeRequest(request);

        // 版本切换不改变服务与方法绑定。
        assertArrayEquals(new byte[] { 'c', 1, 0 }, Arrays.copyOf(body, 3));
        assertEquals("ping", this.fixture.decode(body).getTargetMethod());
    }

    @Test
    public void hessianOneReplyIsReadableByCaucho() throws Throwable {
        // 插件编码返回值，独立 Caucho 输入负责解析。
        byte[] body = this.fixture.codec.encodeResponse(HessianCodecTestFixture.response("pong"), 1);
        assertEquals("pong", HessianCodecTestFixture.readReply(1, body, String.class));
    }

    @Test
    public void hessianTwoReplyIsReadableByCaucho() throws Throwable {
        // Hessian 2 响应包含版本头和完整结果。
        byte[] body = this.fixture.codec.encodeResponse(HessianCodecTestFixture.response("pong"), 2);
        assertEquals("pong", HessianCodecTestFixture.readReply(2, body, String.class));
    }

    @Test
    public void consumerAcceptsHessianOneReply() throws Throwable {
        // 消费者按返回报文的版本读取，不能假定出站配置决定对端响应。
        RequestPayload request = this.fixture.request("ping", new Class<?>[0]);
        byte[] body = HessianCodecTestFixture.reply(1, "pong");
        assertEquals("pong", this.fixture.codec.decodeResponse(request, body).getReturnData());
    }

    @Test
    public void hessianOneFaultKeepsApplicationDetail() throws Throwable {
        // 错误响应携带真实业务异常，而非普通字符串结果。
        BusinessException failure = new BusinessException(9, "denied");
        byte[] body = this.fixture.codec.encodeError(failure, 1);
        RequestPayload request = this.fixture.request("ping", new Class<?>[0]);

        // 独立故障信封在客户端恢复原始异常类型和字段。
        BusinessException decoded = assertThrows(BusinessException.class, //
                () -> this.fixture.codec.decodeResponse(request, body));
        assertEquals(9, decoded.getCode());
        assertEquals("denied", decoded.getMessage());
    }

    @Test
    public void declaredIoExceptionRemainsApplicationFault() throws Throwable {
        // 业务方法声明 IOException，它不是协议解析错误。
        IOException failure = new IOException("storage unavailable");
        RequestPayload request = this.fixture.request("readFile", new Class<?>[0]);
        byte[] body = this.fixture.codec.encodeError(failure, 1);

        // Hessian 1 fault detail 恢复原始 checked 异常及信息。
        IOException decoded = assertThrows(IOException.class, //
                () -> this.fixture.codec.decodeResponse(request, body));
        assertEquals(IOException.class, decoded.getClass());
        assertEquals("storage unavailable", decoded.getMessage());
    }

    @Test
    public void declaredFileNotFoundExceptionKeepsItsSubtype() throws Throwable {
        // IOException 子类作为业务异常同样保留具体原型。
        FileNotFoundException failure = new FileNotFoundException("missing.txt");
        RequestPayload request = this.fixture.request("openFile", new Class<?>[0]);
        byte[] body = this.fixture.codec.encodeError(failure, 2);

        // Hessian 2 解码不能将业务异常改成 ProtocolException。
        FileNotFoundException decoded = assertThrows(FileNotFoundException.class, //
                () -> this.fixture.codec.decodeResponse(request, body));
        assertEquals("missing.txt", decoded.getMessage());
    }

    @Test
    public void explicitProtocolFaultUsesItsDeclaredCode() throws Throwable {
        // 协议层显式包装的错误才使用 ProtocolException 线错误码。
        HessianFaultException failure = new HessianFaultException("ProtocolException", "Malformed call");
        byte[] body = this.fixture.codec.encodeError(failure, 2);

        // 官方客户端获得标准故障，而不是业务 IOException detail。
        HessianServiceException decoded = assertThrows(HessianServiceException.class, //
                () -> HessianCodecTestFixture.readReply(2, body, Object.class));
        assertEquals("ProtocolException", decoded.getCode());
        assertEquals("Malformed call", decoded.getMessage());
    }

    @Test
    public void unknownServiceUsesStandardFaultCode() throws Throwable {
        // 请求路径对应的服务不存在。
        byte[] request = HessianCodecTestFixture.call(2, "ping");
        Exception failure = assertThrows(Exception.class, //
                () -> this.fixture.codec.decodeRequest(request, "/rpc/missing", //
                        this.fixture.protocol.mountIndex().get("/rpc")));

        // 对端按 Hessian 标准故障读取服务缺失信息。
        byte[] body = this.fixture.codec.encodeError(failure, 2);
        HessianServiceException fault = assertThrows(HessianServiceException.class, //
                () -> HessianCodecTestFixture.readReply(2, body, Object.class));
        assertEquals("NoSuchObjectException", fault.getCode());
    }

    @Test
    public void unknownMethodUsesStandardFaultCode() throws Throwable {
        // 方法缺失与服务缺失有不同的线协议错误码。
        byte[] request = HessianCodecTestFixture.call(1, "missing");
        Exception failure = assertThrows(Exception.class, () -> this.fixture.decode(request));
        byte[] body = this.fixture.codec.encodeError(failure, 1);

        // 使用官方客户端故障解析验证结果。
        HessianServiceException fault = assertThrows(HessianServiceException.class, //
                () -> HessianCodecTestFixture.readReply(1, body, Object.class));
        assertEquals("NoSuchMethodException", fault.getCode());
    }

    @Test
    public void emptyCallIsRejected() {
        // 空 HTTP 正文不能构成 Hessian 请求。
        assertThrows(IOException.class, () -> this.fixture.decode(new byte[0]));
    }

    @Test
    public void truncatedVersionHeaderIsRejected() {
        // 只收到版本前缀时必须拒绝，不能读取默认零值。
        byte[] body = { 'H', 2 };
        assertThrows(IOException.class, () -> this.fixture.codec.responseVersion(body));
    }

    @Test
    public void unsupportedVersionIsRejected() throws Exception {
        // 不按 Hessian 2 处理未知主版本。
        byte[] body = HessianCodecTestFixture.call(2, "ping");
        body[1] = 3;
        assertThrows(IOException.class, () -> this.fixture.decode(body));
    }

    @Test
    public void unknownEnvelopeTagIsRejected() {
        // 非调用前缀不能借参数解码路径进入服务。
        byte[] body = { 'X', 2, 0, 'C' };
        assertThrows(IOException.class, () -> this.fixture.decode(body));
    }

    @Test
    public void responseCannotBeUsedAsRequest() throws Exception {
        // 合法响应也不是合法调用信封。
        byte[] body = HessianCodecTestFixture.reply(2, "pong");
        assertThrows(IOException.class, () -> this.fixture.decode(body));
    }

    @Test
    public void hessianOneCallRequiresTerminator() throws Exception {
        // Hessian 1 的最终 z 不能被 EOF 替代。
        byte[] body = HessianCodecTestFixture.call(1, "ping");
        byte[] truncated = Arrays.copyOf(body, body.length - 1);
        assertThrows(IOException.class, () -> this.fixture.decode(truncated));
    }

    @Test
    public void hessianOneCallRejectsTrailingBytes() throws Exception {
        // 一次 HTTP 交换只能携带一个调用。
        byte[] body = this.append(HessianCodecTestFixture.call(1, "ping"), (byte) 'N');
        assertThrows(IOException.class, () -> this.fixture.decode(body));
    }

    @Test
    public void hessianTwoCallRejectsTrailingBytes() throws Exception {
        // Hessian 2 参数结束后也不能拼接另一值。
        byte[] body = this.append(HessianCodecTestFixture.call(2, "ping"), (byte) 'N');
        assertThrows(IOException.class, () -> this.fixture.decode(body));
    }

    @Test
    public void hessianOneCallRejectsMissingArgument() throws Exception {
        // Java 方法要求两个参数，报文只发送一个。
        byte[] body = HessianCodecTestFixture.call(1, "subtract", 7);
        assertThrows(Exception.class, () -> this.fixture.decode(body));
    }

    @Test
    public void hessianTwoCallRejectsExtraArgument() throws Exception {
        // 参数个数必须与已发布方法签名一致。
        byte[] body = HessianCodecTestFixture.call(2, "subtract", 7, 2, 1);
        assertThrows(Exception.class, () -> this.fixture.decode(body));
    }

    @Test
    public void hessianTwoCallRejectsTruncatedArgument() throws Exception {
        // 长字符串正文被截断时必须终止解析。
        byte[] body = HessianCodecTestFixture.call(2, "echo", "complete value");
        byte[] truncated = Arrays.copyOf(body, body.length - 2);
        assertThrows(IOException.class, () -> this.fixture.decode(truncated));
    }

    @Test
    public void hessianOneReplyRequiresTerminator() throws Throwable {
        // 消费者同样检查 Hessian 1 响应的结束符。
        byte[] body = HessianCodecTestFixture.reply(1, "pong");
        byte[] truncated = Arrays.copyOf(body, body.length - 1);
        RequestPayload request = this.fixture.request("ping", new Class<?>[0]);
        assertThrows(IOException.class, () -> this.fixture.codec.decodeResponse(request, truncated));
    }

    @Test
    public void hessianOneReplyRejectsTrailingBytes() throws Throwable {
        // 完整返回值后不允许额外线数据。
        byte[] body = this.append(HessianCodecTestFixture.reply(1, "pong"), (byte) 'N');
        RequestPayload request = this.fixture.request("ping", new Class<?>[0]);
        assertThrows(IOException.class, () -> this.fixture.codec.decodeResponse(request, body));
    }

    @Test
    public void hessianTwoReplyRejectsTrailingBytes() throws Throwable {
        // Hessian 2 的 EOF 也是响应边界的一部分。
        byte[] body = this.append(HessianCodecTestFixture.reply(2, "pong"), (byte) 'N');
        RequestPayload request = this.fixture.request("ping", new Class<?>[0]);
        assertThrows(IOException.class, () -> this.fixture.codec.decodeResponse(request, body));
    }

    @Test
    public void emptyReplyIsRejected() throws Throwable {
        // 空响应不能被当作合法 null 业务值。
        RequestPayload request = this.fixture.request("ping", new Class<?>[0]);
        assertThrows(IOException.class, () -> this.fixture.codec.decodeResponse(request, new byte[0]));
    }

    @Test
    public void callCannotBeUsedAsReply() throws Throwable {
        // 消费者禁止把调用信封当作返回值读取。
        RequestPayload request = this.fixture.request("ping", new Class<?>[0]);
        byte[] body = HessianCodecTestFixture.call(2, "ping");
        assertThrows(IOException.class, () -> this.fixture.codec.decodeResponse(request, body));
    }

    @Test
    public void requestAtConfiguredSizeLimitIsAccepted() throws Exception {
        // 边界等于完整报文长度，应该允许读取。
        byte[] body = HessianCodecTestFixture.call(2, "ping");
        HessianCodec codec = this.codec(Map.of("maxMessageSize", String.valueOf(body.length)));
        RequestPayload request = codec.decodeRequest(body, "/rpc/math", //
                this.fixture.protocol.mountIndex().get("/rpc"));
        assertEquals("ping", request.getTargetMethod());
    }

    @Test
    public void requestBeyondConfiguredSizeLimitIsRejected() throws Exception {
        // 在反序列化之前检查完整报文大小。
        byte[] body = HessianCodecTestFixture.call(2, "ping");
        HessianCodec codec = this.codec(Map.of("maxMessageSize", String.valueOf(body.length - 1)));
        assertThrows(IOException.class, () -> codec.decodeRequest(body, "/rpc/math", //
                this.fixture.protocol.mountIndex().get("/rpc")));
    }

    @Test
    public void responseBeyondConfiguredSizeLimitIsRejected() throws Throwable {
        // 对端返回正文同样遵守接收上限。
        byte[] body = HessianCodecTestFixture.reply(2, "pong");
        HessianCodec codec = this.codec(Map.of("maxMessageSize", String.valueOf(body.length - 1)));
        RequestPayload request = this.fixture.request("ping", new Class<?>[0]);
        assertThrows(IOException.class, () -> codec.decodeResponse(request, body));
    }

    @Test
    public void oversizedOutboundRequestIsRejected() {
        // 发送路径不能创建超出配置上限的请求正文。
        HessianCodec codec = this.codec(Map.of("maxMessageSize", "32"));
        RequestPayload request = this.fixture.request("echo", new Class<?>[] { String.class }, "x".repeat(128));
        assertThrows(IOException.class, () -> codec.encodeRequest(request));
    }

    @Test
    public void oversizedOutboundResponseIsRejected() {
        // 服务结果写出前受相同大小约束。
        HessianCodec codec = this.codec(Map.of("maxMessageSize", "32"));
        assertThrows(IOException.class, //
                () -> codec.encodeResponse(HessianCodecTestFixture.response("x".repeat(128)), 2));
    }

    @Test
    public void defaultLimitRejectsMoreThanFourMebibytes() {
        // 默认配置也有明确上限，先按报文长度拒绝。
        byte[] body = new byte[4 * 1024 * 1024 + 1];
        assertThrows(IOException.class, () -> this.fixture.decode(body));
    }

    @Test
    public void nonPositiveSizeLimitIsRejectedAtConstruction() {
        // 无效上限应在配置阶段报错，而非等到首次发送。
        assertThrows(IllegalArgumentException.class, () -> this.codec(Map.of("maxMessageSize", "0")));
    }

    @Test
    public void unsupportedOutboundVersionIsRejectedAtConstruction() {
        // 版本配置只能选择已有的 Hessian 线格式。
        assertThrows(IllegalArgumentException.class, () -> this.codec(Map.of("version", "3")));
    }

    @Test
    public void remoteReferenceParameterIsRejected() throws Exception {
        // Hessian 的可调用远程对象引用不属于 Meteor 支持范围。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        HessianOutput output = new HessianOutput(bytes);
        output.startCall("echo", 1);
        output.writeRemote(String.class.getName(), "http://remote.example/service");
        output.completeCall();
        output.flush();

        // 不能隐式创建远程代理或继续访问外部地址。
        assertThrows(IOException.class, () -> this.fixture.decode(bytes.toByteArray()));
    }

    @Test
    public void remoteReferenceResponseIsRejected() throws Throwable {
        // 对端返回 remote 标记，也必须在消费者侧拒绝。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        HessianOutput output = new HessianOutput(bytes);
        output.startReply();
        output.writeRemote(String.class.getName(), "http://remote.example/service");
        output.completeReply();
        output.flush();
        RequestPayload request = this.fixture.request("ping", new Class<?>[0]);

        // 接收 remote 标记不会转换成正常 Java 返回值。
        assertThrows(IOException.class, () -> this.fixture.codec.decodeResponse(request, bytes.toByteArray()));
    }

    @Test
    public void hessianTwoTypedRemoteParameterIsRejected() throws Exception {
        // 官方 writeObject 将 HessianRemote 编码为带类型的对象，不使用旧 remote 标记。
        HessianRemote remote = new HessianRemote(String.class.getName(), "http://remote.example/service");
        byte[] body = HessianCodecTestFixture.call(2, "object", remote);

        // 即使方法接受 Object，也不能接收可调用远程对象。
        assertThrows(IOException.class, () -> this.fixture.decode(body));
    }

    @Test
    public void hessianTwoTypedRemoteResponseIsRejected() throws Throwable {
        // 对端通过原生 Hessian 2 对象编码返回远程引用。
        HessianRemote remote = new HessianRemote(String.class.getName(), "http://remote.example/service");
        byte[] body = HessianCodecTestFixture.reply(2, remote);
        RequestPayload request = this.fixture.request("object", new Class<?>[] { Object.class }, "value");

        // Object 返回值也必须遵守远程对象边界。
        assertThrows(IOException.class, () -> this.fixture.codec.decodeResponse(request, body));
    }

    @Test
    public void outboundTypedRemoteParameterIsRejected() {
        // 调用方主动传递 HessianRemote，不能把它作为普通 DTO 写出。
        HessianRemote remote = new HessianRemote(String.class.getName(), "http://remote.example/service");
        RequestPayload request = this.fixture.request("object", new Class<?>[] { Object.class }, remote);
        assertThrows(IOException.class, () -> this.fixture.codec.encodeRequest(request));
    }

    @Test
    public void inboundMapCannotHideTypedRemoteValue() throws Exception {
        // 普通 Map 的嵌套成员也由原生工厂逐个创建。
        HessianRemote remote = new HessianRemote(String.class.getName(), "http://remote.example/service");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("nested", remote);
        byte[] body = HessianCodecTestFixture.call(2, "struct", values);

        // 集合嵌套不能绕过反序列化边界。
        assertThrows(IOException.class, () -> this.fixture.decode(body));
    }

    @Test
    public void outboundMapCannotHideTypedRemoteValue() {
        // 容器本身可序列化，其远程引用成员仍必须单独拒绝。
        HessianRemote remote = new HessianRemote(String.class.getName(), "http://remote.example/service");
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("nested", remote);
        RequestPayload request = this.fixture.request("struct", new Class<?>[] { Map.class }, values);

        // 递归序列化入口不能因根节点是 Map 而跳过检查。
        assertThrows(IOException.class, () -> this.fixture.codec.encodeRequest(request));
    }

    @Test
    public void deeplyNestedArrayTypeIsRejectedBeforeNativeRecursion() throws Exception {
        // 很短的空列表报文，也可能通过类型名触发原生反序列化器递归。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Hessian2Output output = new Hessian2Output(bytes);
        output.writeVersion();
        output.startCall("object", 1);
        output.writeListBegin(0, "[".repeat(65) + "int");
        output.completeCall();
        output.flush();
        byte[] body = bytes.toByteArray();

        // 边界针对类型嵌套深度，不依赖报文大小限制来阻止递归。
        assertTrue(body.length < 128);
        assertThrows(IOException.class, () -> this.fixture.decode(body));
    }

    private HessianCodec codec(Map<String, String> options) {
        Map<String, String> configured = new LinkedHashMap<>(options);
        configured.put("contextPath", "/rpc");
        ProtocolConfig protocol = ProtocolConfig.newConfig("hessian", "hessian", configured);
        return new HessianCodec(protocol, this.fixture.context);
    }

    private byte[] append(byte[] source, byte value) {
        byte[] body = Arrays.copyOf(source, source.length + 1);
        body[source.length] = value;
        return body;
    }
}

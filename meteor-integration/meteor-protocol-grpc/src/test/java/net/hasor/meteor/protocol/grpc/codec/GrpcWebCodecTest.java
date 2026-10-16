/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.codec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import org.junit.Test;
import static org.junit.Assert.*;

public class GrpcWebCodecTest {
    @Test
    public void binaryDefaultUsesProto() throws Exception {
        // 只转换 HTTP 封装，序列化后缀继续交给原生协议选择。
        GrpcWebCodec codec = this.codec(Map.of());
        String contentType = codec.grpcContentType("application/grpc-web");

        // 保持同一序列化协议的 native 类型。
        assertEquals("application/grpc+proto", contentType);
    }

    @Test
    public void textDefaultUsesProto() throws Exception {
        // 只转换 HTTP 封装，序列化后缀继续交给原生协议选择。
        GrpcWebCodec codec = this.codec(Map.of());
        String contentType = codec.grpcContentType("application/grpc-web-text");

        // 保持同一序列化协议的 native 类型。
        assertEquals("application/grpc+proto", contentType);
    }

    @Test
    public void jsonContentTypePreserved() throws Exception {
        // 只转换 HTTP 封装，序列化后缀继续交给原生协议选择。
        GrpcWebCodec codec = this.codec(Map.of());
        String contentType = codec.grpcContentType("application/grpc-web+json");

        // 保持同一序列化协议的 native 类型。
        assertEquals("application/grpc+json", contentType);
    }

    @Test
    public void customSerializerSuffixPreserved() throws Exception {
        // 只转换 HTTP 封装，序列化后缀继续交给原生协议选择。
        GrpcWebCodec codec = this.codec(Map.of());
        String contentType = codec.grpcContentType("application/grpc-web-text+custom-v1");

        // 保持同一序列化协议的 native 类型。
        assertEquals("application/grpc+custom-v1", contentType);
    }

    @Test
    public void contentTypeIgnoresCaseAndParameters() throws Exception {
        // 只转换 HTTP 封装，序列化后缀继续交给原生协议选择。
        GrpcWebCodec codec = this.codec(Map.of());
        String contentType = codec.grpcContentType("Application/GRPC-Web+Proto; charset=utf-8");

        // 保持同一序列化协议的 native 类型。
        assertEquals("application/grpc+proto", contentType);
    }

    @Test
    public void nativeDefaultProducesExplicitWebProto() throws Exception {
        // 未带后缀的 native gRPC 默认采用 Protobuf。
        String contentType = this.codec(Map.of()).webContentType("application/grpc", false);

        // 发送方明确带上默认格式。
        assertEquals("application/grpc-web+proto", contentType);
    }

    @Test
    public void nativeJsonProducesWebTextJson() throws Exception {
        // Base64 外层不改变 JSON 消息格式。
        String contentType = this.codec(Map.of()).webContentType("application/grpc+json", true);

        // HTTP 类型同时表达 text 封装与 JSON 序列化。
        assertEquals("application/grpc-web-text+json", contentType);
    }

    @Test
    public void textTypeDetectionExcludesNativeGrpc() {
        // 原生 gRPC 必须继续由原有会话处理。
        assertFalse(GrpcWebCodec.accepts("application/grpc+proto"));
        assertFalse(GrpcWebCodec.isText("application/grpc+proto"));
    }

    @Test
    public void textTypeDetectionRecognizesSuffix() {
        // text 类型检测允许格式后缀及 MIME 参数。
        String contentType = "application/grpc-web-text+json; charset=utf-8";

        // 同时识别 Web 格式与 Base64 外层。
        assertTrue(GrpcWebCodec.accepts(contentType));
        assertTrue(GrpcWebCodec.isText(contentType));
    }

    @Test
    public void contentTypeRejectsPrefixLookalike() {
        // 相似前缀不代表支持的媒体类型。
        GrpcWebCodec codec = this.codec(Map.of());

        // 不能让任意后缀绕过协议选择。
        assertThrows(IOException.class, () -> codec.grpcContentType("application/grpc-websocket"));
    }

    @Test
    public void contentTypeRejectsEmptySuffix() {
        // 加号后必须指定真实的格式名。
        GrpcWebCodec codec = this.codec(Map.of());

        // 格式错误不会静默降级到 Proto。
        assertThrows(IOException.class, () -> codec.grpcContentType("application/grpc-web+"));
    }

    @Test
    public void reverseContentTypeRejectsAlreadyWebType() {
        // 反向转换的输入必须是原生 gRPC 类型。
        GrpcWebCodec codec = this.codec(Map.of());

        // 不重复包装或吞掉错误类型。
        assertThrows(IOException.class, () -> codec.webContentType("application/grpc-web+proto", false));
    }

    @Test
    public void binaryRequestPreservesNativeFrame() throws Exception {
        // gRPC-Web 二进制请求与 native 消息帧相同。
        GrpcWebCodec codec = this.codec(Map.of());
        byte[] nativeFrame = this.frame(0, "hello".getBytes(StandardCharsets.UTF_8));
        byte[] wire = codec.encodeRequest(nativeFrame, false);

        // 转换不重新序列化消息正文。
        assertArrayEquals(nativeFrame, wire);
        assertArrayEquals(nativeFrame, codec.decodeRequest(wire, false));
    }

    @Test
    public void textRequestRoundTrip() throws Exception {
        // text 模式只对完整帧添加 Base64 外层。
        GrpcWebCodec codec = this.codec(Map.of());
        byte[] nativeFrame = this.frame(0, new byte[] { 1, 2, 3, 4 });
        byte[] wire = codec.encodeRequest(nativeFrame, true);

        // 解码后仍然是原始五字节前缀和正文。
        assertArrayEquals(Base64.getEncoder().encode(nativeFrame), wire);
        assertArrayEquals(nativeFrame, codec.decodeRequest(wire, true));
    }

    @Test
    public void compressedDataFlagPassesThrough() throws Exception {
        // data 压缩由既有 gRPC codec 处理，Web 层只保存其标记。
        byte[] nativeFrame = this.frame(1, new byte[] { 1, 2, 3 });
        GrpcWebCodec codec = this.codec(Map.of());
        byte[] wire = codec.encodeRequest(nativeFrame, false);

        // 不把压缩数据解码成另一份消息。
        assertArrayEquals(nativeFrame, codec.decodeRequest(wire, false));
    }

    @Test
    public void emptyMessageStillHasOneFrame() throws Exception {
        // 零长度消息与缺少消息不是同一情况。
        byte[] nativeFrame = this.frame(0, new byte[0]);
        GrpcWebCodec codec = this.codec(Map.of());

        // 五字节空帧合法。
        assertArrayEquals(nativeFrame, codec.decodeRequest(nativeFrame, false));
    }

    @Test
    public void responseCarriesDataAndRepeatedTrailers() throws Exception {
        // 同名应用元数据需要保留顺序，状态字段只出现一次。
        Map<String, List<String>> trailers = new LinkedHashMap<>();
        trailers.put("grpc-status", List.of("0"));
        trailers.put("trace-tag", List.of("first", "second"));
        byte[] frame = this.frame(0, new byte[] { 7 });
        GrpcWebCodec codec = this.codec(Map.of());
        byte[] wire = codec.encodeResponse(frame, trailers, false);

        // 解出的 native 正文不包含 Web trailer 帧。
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        Map<String, List<String>> actual = codec.decodeResponse(this.input(wire), data, false, "identity");
        assertArrayEquals(frame, data.toByteArray());
        assertEquals(trailers, actual);
    }

    @Test
    public void textResponseUsesIndependentPaddedFrames() throws Exception {
        // 每次 flush 可以结束一个 Base64 片段，片段 padding 不终止整个响应。
        byte[] frames = this.join(this.frame(0, new byte[] { 1 }), this.frame(0, new byte[] { 2, 3 }));
        GrpcWebCodec codec = this.codec(Map.of());
        byte[] wire = codec.encodeResponse(frames, Map.of("grpc-status", List.of("0")), true);

        // 连续独立片段解码后保持两个原生消息。
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        Map<String, List<String>> trailers = codec.decodeResponse(this.input(wire), data, true, "identity");
        assertArrayEquals(frames, data.toByteArray());
        assertEquals(List.of("0"), trailers.get("grpc-status"));
    }

    @Test
    public void trailerOnlyBodyIsValid() throws Exception {
        // 业务失败可只有状态尾帧，不包含消息。
        GrpcWebCodec codec = this.codec(Map.of());
        byte[] wire = codec.encodeResponse(new byte[0], Map.of("grpc-status", List.of("7")), false);
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // 错误码交由原生 gRPC 错误映射，wire 不提前抛业务异常。
        Map<String, List<String>> trailers = codec.decodeResponse(this.input(wire), data, false, "identity");
        assertEquals(0, data.size());
        assertEquals(List.of("7"), trailers.get("grpc-status"));
    }

    @Test
    public void encodedTrailerNamesBecomeLowercase() throws Exception {
        // HTTP 头字典可以包含混合大小写，Web 尾帧要求小写。
        Map<String, List<String>> trailers = Map.of("Grpc-Status", List.of("0"), "Trace-Id", List.of("A"));
        GrpcWebCodec codec = this.codec(Map.of());
        byte[] wire = codec.encodeResponse(new byte[0], trailers, false);
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // 所有尾字段输出为规范名称。
        Map<String, List<String>> actual = codec.decodeResponse(this.input(wire), data, false, "identity");
        assertEquals(List.of("A"), actual.get("trace-id"));
        assertEquals(List.of("0"), actual.get("grpc-status"));
    }

    @Test
    public void gzipTrailerDecodesUsingGrpcEncoding() throws Exception {
        // 压缩尾帧使用同一 grpc-encoding 选择算法。
        byte[] metadata = "grpc-status: 0\r\ntrace-id: gzip\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] wire = this.frame(0x81, this.gzip(metadata));
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // 解压后的字段与普通尾帧一致。
        Map<String, List<String>> actual = this.codec(Map.of()).decodeResponse(this.input(wire), data, false, "gzip");
        assertEquals(List.of("gzip"), actual.get("trace-id"));
    }

    @Test
    public void trailerBlockMayOmitFinalCrLf() throws Exception {
        // 尾字段块没有 HTTP 报头终止空行，最后一行可直接结束。
        byte[] wire = this.trailer("grpc-status: 0");
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // EOF 与有效末帧一起完成消息。
        Map<String, List<String>> actual = this.codec(Map.of()).decodeResponse(this.input(wire), data, false, null);
        assertEquals(List.of("0"), actual.get("grpc-status"));
    }

    @Test
    public void trailerValueTrimsOnlyHttpOws() throws Exception {
        // 头字段允许冒号后及末尾的 SP/HTAB。
        byte[] wire = this.trailer("grpc-status:\t 0 \t\r\n");
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // OWS 不成为业务元数据值。
        Map<String, List<String>> actual = this.codec(Map.of()).decodeResponse(this.input(wire), data, false, null);
        assertEquals(List.of("0"), actual.get("grpc-status"));
    }

    @Test
    public void trailerSupplierRunsAfterDataEof() throws Exception {
        // 流式响应尾部直到 native 正文结束后才完整可见。
        byte[] frame = this.frame(0, new byte[] { 1 });
        ByteArrayInputStream input = this.input(frame);
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        AtomicBoolean called = new AtomicBoolean();

        // 取尾部时数据帧已交付并且源流已消费完毕。
        this.codec(Map.of()).encodeResponse(input, wire, false, () -> {
            assertEquals(0, input.available());
            assertArrayEquals(frame, wire.toByteArray());
            called.set(true);
            return Map.of("grpc-status", List.of("0"));
        });
        assertTrue(called.get());
    }

    @Test
    public void responseDataIsForwardedBeforeReadingNextFrame() throws Exception {
        // 第二帧读取前首帧应已写给消费方，不能聚合整个响应。
        byte[] first = this.frame(0, new byte[] { 1 });
        byte[] wire = this.join(first, this.trailer("grpc-status: 0\r\n"));
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        ByteArrayInputStream input = new ByteArrayInputStream(wire) {
            @Override
            public synchronized int read() {
                if (this.pos == first.length) {
                    assertArrayEquals(first, data.toByteArray());
                }
                return super.read();
            }
        };

        // 正文增量交付，同时最终状态仍经过校验。
        this.codec(Map.of()).decodeResponse(input, data, false, "identity");
        assertArrayEquals(first, data.toByteArray());
    }

    @Test
    public void rejectMissingRequestFrame() {
        // 请求必须只有一个完整 data 帧。
        byte[] wire = new byte[0];
        GrpcWebCodec codec = this.codec(Map.of());
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // 请求校验失败时不向原生会话暴露部分正文。
        assertThrows(IOException.class, () -> codec.decodeRequest(this.input(wire), data, false));
        assertEquals(0, data.size());
    }

    @Test
    public void rejectRequestTrailer() {
        // 请求必须只有一个完整 data 帧。
        byte[] wire = this.trailer("grpc-status: 0\r\n");
        GrpcWebCodec codec = this.codec(Map.of());
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // 请求校验失败时不向原生会话暴露部分正文。
        assertThrows(IOException.class, () -> codec.decodeRequest(this.input(wire), data, false));
        assertEquals(0, data.size());
    }

    @Test
    public void rejectRequestInvalidFlag() {
        // 请求必须只有一个完整 data 帧。
        byte[] wire = this.frame(2, new byte[0]);
        GrpcWebCodec codec = this.codec(Map.of());
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // 请求校验失败时不向原生会话暴露部分正文。
        assertThrows(IOException.class, () -> codec.decodeRequest(this.input(wire), data, false));
        assertEquals(0, data.size());
    }

    @Test
    public void rejectRequestTruncatedPrefix() {
        // 请求必须只有一个完整 data 帧。
        byte[] wire = new byte[] { 0, 0, 0 };
        GrpcWebCodec codec = this.codec(Map.of());
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // 请求校验失败时不向原生会话暴露部分正文。
        assertThrows(IOException.class, () -> codec.decodeRequest(this.input(wire), data, false));
        assertEquals(0, data.size());
    }

    @Test
    public void rejectRequestTruncatedPayload() {
        // 请求必须只有一个完整 data 帧。
        byte[] wire = new byte[] { 0, 0, 0, 0, 2, 1 };
        GrpcWebCodec codec = this.codec(Map.of());
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // 请求校验失败时不向原生会话暴露部分正文。
        assertThrows(IOException.class, () -> codec.decodeRequest(this.input(wire), data, false));
        assertEquals(0, data.size());
    }

    @Test
    public void rejectMultipleRequestFrames() {
        // 请求必须只有一个完整 data 帧。
        byte[] wire = this.join(this.frame(0, new byte[0]), this.frame(0, new byte[0]));
        GrpcWebCodec codec = this.codec(Map.of());
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // 请求校验失败时不向原生会话暴露部分正文。
        assertThrows(IOException.class, () -> codec.decodeRequest(this.input(wire), data, false));
        assertEquals(0, data.size());
    }

    @Test
    public void rejectTrailingRequestByte() {
        // 请求必须只有一个完整 data 帧。
        byte[] wire = this.join(this.frame(0, new byte[0]), new byte[] { 1 });
        GrpcWebCodec codec = this.codec(Map.of());
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // 请求校验失败时不向原生会话暴露部分正文。
        assertThrows(IOException.class, () -> codec.decodeRequest(this.input(wire), data, false));
        assertEquals(0, data.size());
    }

    @Test
    public void rejectMissingTrailerFrame() {
        // 尾帧必须是唯一终止帧，字段满足 gRPC 状态契约。
        byte[] wire = this.frame(0, new byte[] { 1 });
        GrpcWebCodec codec = this.codec(Map.of());
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // 无效末状态不能被当作成功响应。
        assertThrows(IOException.class, () -> codec.decodeResponse(this.input(wire), data, false, "identity"));
    }

    @Test
    public void rejectDuplicateTrailerFrame() {
        // 尾帧必须是唯一终止帧，字段满足 gRPC 状态契约。
        byte[] wire = this.join(this.trailer("grpc-status: 0"), this.trailer("grpc-status: 0"));
        GrpcWebCodec codec = this.codec(Map.of());
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // 无效末状态不能被当作成功响应。
        assertThrows(IOException.class, () -> codec.decodeResponse(this.input(wire), data, false, "identity"));
    }

    @Test
    public void rejectDataAfterTrailer() {
        // 尾帧必须是唯一终止帧，字段满足 gRPC 状态契约。
        byte[] wire = this.join(this.trailer("grpc-status: 0"), this.frame(0, new byte[0]));
        GrpcWebCodec codec = this.codec(Map.of());
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // 无效末状态不能被当作成功响应。
        assertThrows(IOException.class, () -> codec.decodeResponse(this.input(wire), data, false, "identity"));
    }

    @Test
    public void rejectTrailerInvalidFlag() {
        // 尾帧必须是唯一终止帧，字段满足 gRPC 状态契约。
        byte[] wire = this.frame(0x82, new byte[0]);
        GrpcWebCodec codec = this.codec(Map.of());
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // 无效末状态不能被当作成功响应。
        assertThrows(IOException.class, () -> codec.decodeResponse(this.input(wire), data, false, "identity"));
    }

    @Test
    public void rejectMissingTrailerStatus() {
        // 尾帧必须是唯一终止帧，字段满足 gRPC 状态契约。
        byte[] wire = this.trailer("trace-id: abc");
        GrpcWebCodec codec = this.codec(Map.of());
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // 无效末状态不能被当作成功响应。
        assertThrows(IOException.class, () -> codec.decodeResponse(this.input(wire), data, false, "identity"));
    }

    @Test
    public void rejectDuplicateTrailerStatus() {
        // 尾帧必须是唯一终止帧，字段满足 gRPC 状态契约。
        byte[] wire = this.trailer("grpc-status: 0\r\ngrpc-status: 0");
        GrpcWebCodec codec = this.codec(Map.of());
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // 无效末状态不能被当作成功响应。
        assertThrows(IOException.class, () -> codec.decodeResponse(this.input(wire), data, false, "identity"));
    }

    @Test
    public void rejectTrailerStatusOutOfRange() {
        // 尾帧必须是唯一终止帧，字段满足 gRPC 状态契约。
        byte[] wire = this.trailer("grpc-status: 17");
        GrpcWebCodec codec = this.codec(Map.of());
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // 无效末状态不能被当作成功响应。
        assertThrows(IOException.class, () -> codec.decodeResponse(this.input(wire), data, false, "identity"));
    }

    @Test
    public void rejectTrailerNegativeStatus() {
        // 尾帧必须是唯一终止帧，字段满足 gRPC 状态契约。
        byte[] wire = this.trailer("grpc-status: -1");
        GrpcWebCodec codec = this.codec(Map.of());
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // 无效末状态不能被当作成功响应。
        assertThrows(IOException.class, () -> codec.decodeResponse(this.input(wire), data, false, "identity"));
    }

    @Test
    public void rejectTrailerStatusOverflow() {
        // 尾帧必须是唯一终止帧，字段满足 gRPC 状态契约。
        byte[] wire = this.trailer("grpc-status: 9999999999999");
        GrpcWebCodec codec = this.codec(Map.of());
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // 无效末状态不能被当作成功响应。
        assertThrows(IOException.class, () -> codec.decodeResponse(this.input(wire), data, false, "identity"));
    }

    @Test
    public void rejectUppercaseTrailerName() {
        // 尾帧必须是唯一终止帧，字段满足 gRPC 状态契约。
        byte[] wire = this.trailer("Grpc-Status: 0");
        GrpcWebCodec codec = this.codec(Map.of());
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // 无效末状态不能被当作成功响应。
        assertThrows(IOException.class, () -> codec.decodeResponse(this.input(wire), data, false, "identity"));
    }

    @Test
    public void rejectTrailerMissingColon() {
        // 尾帧必须是唯一终止帧，字段满足 gRPC 状态契约。
        byte[] wire = this.trailer("grpc-status 0");
        GrpcWebCodec codec = this.codec(Map.of());
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // 无效末状态不能被当作成功响应。
        assertThrows(IOException.class, () -> codec.decodeResponse(this.input(wire), data, false, "identity"));
    }

    @Test
    public void rejectTrailerBlankLine() {
        // 尾帧必须是唯一终止帧，字段满足 gRPC 状态契约。
        byte[] wire = this.trailer("grpc-status: 0\r\n\r\n");
        GrpcWebCodec codec = this.codec(Map.of());
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // 无效末状态不能被当作成功响应。
        assertThrows(IOException.class, () -> codec.decodeResponse(this.input(wire), data, false, "identity"));
    }

    @Test
    public void rejectTrailerNulValue() {
        // 尾帧必须是唯一终止帧，字段满足 gRPC 状态契约。
        byte[] wire = this.trailer("grpc-status: 0" + (char) 0);
        GrpcWebCodec codec = this.codec(Map.of());
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // 无效末状态不能被当作成功响应。
        assertThrows(IOException.class, () -> codec.decodeResponse(this.input(wire), data, false, "identity"));
    }

    @Test
    public void rejectTrailerBareLineFeed() {
        // 尾帧必须是唯一终止帧，字段满足 gRPC 状态契约。
        byte[] wire = this.trailer("grpc-status: 0\n");
        GrpcWebCodec codec = this.codec(Map.of());
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // 无效末状态不能被当作成功响应。
        assertThrows(IOException.class, () -> codec.decodeResponse(this.input(wire), data, false, "identity"));
    }

    @Test
    public void rejectDuplicateGrpcMessage() {
        // 尾帧必须是唯一终止帧，字段满足 gRPC 状态契约。
        byte[] wire = this.trailer("grpc-status: 7\r\ngrpc-message: a\r\ngrpc-message: b");
        GrpcWebCodec codec = this.codec(Map.of());
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // 无效末状态不能被当作成功响应。
        assertThrows(IOException.class, () -> codec.decodeResponse(this.input(wire), data, false, "identity"));
    }

    @Test
    public void rejectDuplicateStatusDetails() {
        // 尾帧必须是唯一终止帧，字段满足 gRPC 状态契约。
        byte[] wire = this.trailer("grpc-status: 7\r\ngrpc-status-details-bin: AA==\r\ngrpc-status-details-bin: AQ==");
        GrpcWebCodec codec = this.codec(Map.of());
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // 无效末状态不能被当作成功响应。
        assertThrows(IOException.class, () -> codec.decodeResponse(this.input(wire), data, false, "identity"));
    }

    @Test
    public void compressedTrailerRequiresSupportedEncoding() {
        // 压缩尾帧不能在 identity 下静默解析。
        byte[] wire = this.frame(0x81, new byte[0]);
        GrpcWebCodec codec = this.codec(Map.of());
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // 与原生 gRPC 一致报告不支持的压缩算法。
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> codec.decodeResponse(this.input(wire), data, false, "identity"));
        assertEquals(12, failure.getCode());
    }

    @Test
    public void rejectOversizedUnsignedFrameLength() {
        // 帧长度为无符号32位，不能溢出为负数后绕过限制。
        byte[] wire = new byte[] { 0, -1, -1, -1, -1 };
        GrpcWebCodec codec = this.codec(Map.of());

        // 在分配正文缓冲区之前拒绝。
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> codec.decodeRequest(wire, false));
        assertEquals(8, failure.getCode());
    }

    @Test
    public void rejectDataOverMessageLimit() {
        // data 使用独立 maxMessageSize 限制。
        byte[] wire = this.frame(0, new byte[4]);
        GrpcWebCodec codec = this.codec(Map.of("maxMessageSize", "3"));

        // 大消息明确映射 RESOURCE_EXHAUSTED。
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> codec.decodeRequest(wire, false));
        assertEquals(8, failure.getCode());
    }

    @Test
    public void rejectTrailerOverMetadataLimit() {
        // metadata 与 data 分别设上限。
        byte[] wire = this.trailer("grpc-status: 0\r\n");
        GrpcWebCodec codec = this.codec(Map.of("maxMetadataSize", "8"));
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // 尾帧长度不会只按消息大小放行。
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> codec.decodeResponse(this.input(wire), data, false, "identity"));
        assertEquals(8, failure.getCode());
    }

    @Test
    public void rejectCompressedTrailerExpansionOverLimit() throws Exception {
        // gzip 输入很小，展开后仍然必须遵守 metadata 上限。
        String metadata = "grpc-status: 0\r\ntrace-id: " + "x".repeat(256);
        byte[] wire = this.frame(0x81, this.gzip(metadata.getBytes(StandardCharsets.US_ASCII)));
        GrpcWebCodec codec = this.codec(Map.of("maxMetadataSize", "128"));
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        // 大小限制统计解压结果，防止压缩炸弹。
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> codec.decodeResponse(this.input(wire), data, false, "gzip"));
        assertEquals(8, failure.getCode());
    }

    @Test
    public void rejectOutputMetadataOverLimit() {
        // 发送端同样统计字段名、分隔符和正文总长度。
        GrpcWebCodec codec = this.codec(Map.of("maxMetadataSize", "8"));
        Map<String, List<String>> trailers = Map.of("grpc-status", List.of("0"));

        // 不生成对端会因过大而拒绝的尾帧。
        assertThrows(GrpcStatusException.class, () -> codec.encodeResponse(new byte[0], trailers, false));
    }

    @Test
    public void rejectOutputHeaderInjection() {
        // 自定义元数据不能向尾帧注入新字段。
        Map<String, List<String>> trailers = Map.of("grpc-status", List.of("0"), "trace-id", List.of("x\r\ny: z"));
        GrpcWebCodec codec = this.codec(Map.of());

        // CR/LF 在写出之前被拒绝。
        assertThrows(IOException.class, () -> codec.encodeResponse(new byte[0], trailers, false));
    }

    @Test
    public void rejectOutputStatusCaseCollision() {
        // 大小写不同的字段名规范化后仍然是同一个状态字段。
        Map<String, List<String>> trailers = new LinkedHashMap<>();
        trailers.put("grpc-status", List.of("0"));
        trailers.put("Grpc-Status", List.of("7"));
        GrpcWebCodec codec = this.codec(Map.of());

        // 不选择其中任意一个掩盖歧义。
        assertThrows(IOException.class, () -> codec.encodeResponse(new byte[0], trailers, false));
    }

    @Test
    public void rejectInvalidLimitConfiguration() {
        // 大小上限必须能以安全的 Java 缓冲区长度表达。
        Map<String, String> options = Map.of("maxMetadataSize", "0");

        // 配置错误在会话开始之前暴露。
        assertThrows(IllegalArgumentException.class, () -> this.codec(options));
    }

    @Test
    public void rejectNonNumericLimitConfiguration() {
        // 不把错误拼写退回默认上限。
        Map<String, String> options = Map.of("maxMessageSize", "large");

        // 使用者得到包含选项名的参数错误。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> this.codec(options));
        assertTrue(failure.getMessage().contains("maxMessageSize"));
    }

    private GrpcWebCodec codec(Map<String, String> options) {
        return new GrpcWebCodec(ProtocolConfig.newConfig("grpc", "http", options));
    }

    private ByteArrayInputStream input(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    private byte[] frame(int flag, byte[] payload) {
        return ByteBuffer.allocate(5 + payload.length).put((byte) flag).putInt(payload.length).put(payload).array();
    }

    private byte[] trailer(String metadata) {
        return this.frame(0x80, metadata.getBytes(StandardCharsets.US_ASCII));
    }

    private byte[] join(byte[] first, byte[] second) {
        return ByteBuffer.allocate(first.length + second.length).put(first).put(second).array();
    }

    private byte[] gzip(byte[] bytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(bytes);
        }
        return output.toByteArray();
    }
}

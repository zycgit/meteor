/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.connect.codec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;
import net.hasor.meteor.protocol.connect.ConnectStatusException;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectFrameCodecTest {
    @Test
    public void rejectsNonPositiveMessageLimit() {
        // 配置的最大消息长度必须为正数。
        assertThrows(IllegalArgumentException.class, () -> new ConnectFrameCodec(0));
    }

    @Test
    public void plainMessageUsesZeroFlagAndBigEndianLength() throws Exception {
        // 生成普通消息并检查五字节信封的线格式。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = codec.frame(new byte[] { 10, 20, 30 }, "identity");
        assertArrayEquals(new byte[] { 0, 0, 0, 0, 3, 10, 20, 30 }, encoded);
    }

    @Test
    public void gzipMessageUsesCompressionFlag() throws Exception {
        // 压缩消息使用独立 gzip 正文及标志位 1。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = codec.frame(this.bytes("compressed message"), "gzip");
        assertEquals(1, encoded[0]);
        assertEquals(encoded.length - 5, ByteBuffer.wrap(encoded, 1, 4).getInt());
    }

    @Test
    public void readsUncompressedMessage() throws Exception {
        // 直接构造对端报文，不使用被测编码器准备输入。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] message = this.bytes("message");
        byte[] encoded = this.envelope(0, message);

        // 普通信封只提供数据，不携带结束状态。
        ConnectEnvelope decoded = codec.read(new ByteArrayInputStream(encoded), "identity");
        assertArrayEquals(message, decoded.message());
        assertFalse(decoded.end());
        assertNull(decoded.failure());
        assertTrue(decoded.metadata().isEmpty());
    }

    @Test
    public void readsCompressedMessage() throws Exception {
        // 用 JDK gzip 构造压缩消息。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] message = this.bytes("gzip message");
        byte[] encoded = this.envelope(1, this.gzip(message));

        // 解压后的内容交给上层业务处理。
        ConnectEnvelope decoded = codec.read(new ByteArrayInputStream(encoded), "gzip");
        assertArrayEquals(message, decoded.message());
        assertFalse(decoded.end());
    }

    @Test
    public void readsSuccessfulEndEnvelope() throws Exception {
        // 成功结束消息使用标志位 2，正文为空 JSON 对象。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = this.envelope(2, this.bytes("{}"));
        ConnectEnvelope decoded = codec.read(new ByteArrayInputStream(encoded), "identity");
        assertTrue(decoded.end());
        assertNull(decoded.message());
        assertNull(decoded.failure());
        assertTrue(decoded.metadata().isEmpty());
    }

    @Test
    public void readsCompressedEndEnvelope() throws Exception {
        // 标志位 3 同时表示压缩和流结束。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = this.envelope(3, this.gzip(this.bytes("{}")));
        ConnectEnvelope decoded = codec.read(new ByteArrayInputStream(encoded), "gzip");
        assertTrue(decoded.end());
        assertNull(decoded.failure());
    }

    @Test
    public void endEncoderUsesUncompressedEndFlag() throws Exception {
        // 结束编码不受前一条消息的压缩上下文影响。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = codec.end(null, Map.of());
        assertArrayEquals(new byte[] { 2, 0, 0, 0, 2, '{', '}' }, encoded);
    }

    @Test
    public void readsEndFailureWithMetadata() throws Exception {
        // 错误结束报文同时携带应用错误码及重复尾部元数据。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        String error = "\"error\":{\"code\":\"not_found\",\"message\":\"missing\"}";
        String metadata = "\"metadata\":{\"x-trace\":[\"first\",\"second\"]}";
        String document = "{" + error + "," + metadata + "}";
        ConnectEnvelope decoded = codec.read(new ByteArrayInputStream(this.envelope(2, this.bytes(document))), null);

        // 错误和结束事件共享同一组尾部元数据。
        assertEquals("not_found", decoded.failure().getCode());
        assertEquals("missing", decoded.failure().getDescription());
        assertEquals(List.of("first", "second"), decoded.metadata().get("x-trace"));
        assertEquals(decoded.metadata(), decoded.failure().getMetadata());
    }

    @Test
    public void endEncoderRetainsErrorDetails() throws Exception {
        // 业务异常详情写入结束消息的标准 error 字段。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        Map<String, Object> detail = Map.of("type", "example.Detail", "value", "AQI=");
        ConnectStatusException failure = new ConnectStatusException("internal", "failed", List.of(detail), Map.of());

        // 编码后的结束消息保留可由对端读取的类型与二进制数据。
        byte[] encoded = codec.end(failure, Map.of());
        ConnectEnvelope decoded = codec.read(new ByteArrayInputStream(encoded), null);
        Map<String, Object> decodedDetail = decoded.failure().getDetails().get(0);
        assertEquals("example.Detail", decodedDetail.get("type"));
        assertEquals("AQI", decodedDetail.get("value"));
    }

    @Test
    public void cleanEofHasNoEnvelope() throws Exception {
        // 没有读取到任何前缀字节时，EOF 表示无后续信封。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        assertNull(codec.read(new ByteArrayInputStream(new byte[0]), null));
    }

    @Test
    public void emptyMessageIsDifferentFromEof() throws Exception {
        // 长度为零的合法消息仍然产生一个数据事件。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        ConnectEnvelope decoded = codec.read(new ByteArrayInputStream(this.envelope(0, new byte[0])), null);
        assertNotNull(decoded);
        assertArrayEquals(new byte[0], decoded.message());
        assertFalse(decoded.end());
    }

    @Test
    public void readsOneEnvelopeWithoutConsumingTheNext() throws Exception {
        // 连续消息共享输入流，单次解码只能消费当前信封。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] first = this.envelope(0, this.bytes("first"));
        byte[] second = this.envelope(0, this.bytes("second"));
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        encoded.write(first);
        encoded.write(second);
        ByteArrayInputStream input = new ByteArrayInputStream(encoded.toByteArray());

        // 两个读取结果按线上顺序返回。
        assertArrayEquals(this.bytes("first"), codec.read(input, null).message());
        assertArrayEquals(this.bytes("second"), codec.read(input, null).message());
        assertNull(codec.read(input, null));
    }

    @Test
    public void decodingDoesNotCloseBorrowedInput() throws Exception {
        // 网络输入流的生命周期仍属于传输层。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        AtomicBoolean closed = new AtomicBoolean();
        ByteArrayInputStream input = new ByteArrayInputStream(this.envelope(0, this.bytes("borrowed"))) {
            @Override
            public void close() {
                closed.set(true);
            }
        };

        // 消息解析完成后输入仍能被调用方继续持有。
        codec.read(input, null);
        assertFalse(closed.get());
    }

    @Test
    public void truncatedPayloadDoesNotCloseBorrowedInput() {
        // 错误报文也不能反向关闭借用的网络输入。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        AtomicBoolean closed = new AtomicBoolean();
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[] { 0, 0, 0, 0, 2, 1 }) {
            @Override
            public void close() {
                closed.set(true);
            }
        };

        // 解码失败只向拥有者报告异常。
        assertThrows(IOException.class, () -> codec.read(input, null));
        assertFalse(closed.get());
    }

    @Test
    public void rejectsReservedEnvelopeFlag() {
        // 尚未定义的标志位不能被当成普通消息。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = this.envelope(4, new byte[0]);
        assertThrows(IOException.class, () -> codec.read(new ByteArrayInputStream(encoded), null));
    }

    @Test
    public void rejectsAllBitsEnvelopeFlag() {
        // 带符号字节转换后也必须校验高位标志。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = this.envelope(255, new byte[0]);
        assertThrows(IOException.class, () -> codec.read(new ByteArrayInputStream(encoded), null));
    }

    @Test
    public void rejectsTruncatedPayload() {
        // 声明的消息长度大于实际可读正文。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = new byte[] { 0, 0, 0, 0, 3, 1, 2 };
        assertThrows(IOException.class, () -> codec.read(new ByteArrayInputStream(encoded), null));
    }

    @Test
    public void acceptsMessageExactlyAtLimit() throws Exception {
        // 消息上限是包含端点的边界。
        ConnectFrameCodec codec = new ConnectFrameCodec(3);
        byte[] message = new byte[] { 1, 2, 3 };
        ConnectEnvelope decoded = codec.read(new ByteArrayInputStream(this.envelope(0, message)), null);
        assertArrayEquals(message, decoded.message());
    }

    @Test
    public void rejectsDeclaredLengthBeforeAllocatingPayload() {
        // 超长声明后不提供正文，仍应先报资源上限错误。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = new byte[] { 0, 0, 0, 4, 1 };
        ConnectStatusException failure = assertThrows(ConnectStatusException.class, () -> {
            codec.read(new ByteArrayInputStream(encoded), null);
        });
        assertEquals("resource_exhausted", failure.getCode());
    }

    @Test
    public void rejectsUnsignedLengthBomb() {
        // 0xffffffff 不能转换为负数后绕过消息上限。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = new byte[] { 0, -1, -1, -1, -1 };
        ConnectStatusException failure = assertThrows(ConnectStatusException.class, () -> {
            codec.read(new ByteArrayInputStream(encoded), null);
        });
        assertEquals("resource_exhausted", failure.getCode());
    }

    @Test
    public void rejectsOversizedOutgoingMessage() {
        // 发送前同样限制未压缩消息的大小。
        ConnectFrameCodec codec = new ConnectFrameCodec(3);
        ConnectStatusException failure = assertThrows(ConnectStatusException.class, () -> {
            codec.frame(new byte[4], null);
        });
        assertEquals("resource_exhausted", failure.getCode());
    }

    @Test
    public void rejectsGzipExpansionBeyondMessageLimit() throws Exception {
        // 小压缩体膨胀成超限消息，不能按压缩长度放行。
        ConnectFrameCodec codec = new ConnectFrameCodec(64);
        byte[] encoded = this.envelope(1, this.gzip(new byte[1024]));
        ConnectStatusException failure = assertThrows(ConnectStatusException.class, () -> {
            codec.read(new ByteArrayInputStream(encoded), "gzip");
        });
        assertEquals("resource_exhausted", failure.getCode());
    }

    @Test
    public void rejectsCompressedDataWithoutEncoding() {
        // 压缩标志必须与压缩算法头对应。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = this.envelope(1, this.bytes("not gzip"));
        assertThrows(IOException.class, () -> codec.read(new ByteArrayInputStream(encoded), null));
    }

    @Test
    public void rejectsCompressedEndWithoutEncoding() {
        // 结束信封压缩时也不能省略编码信息。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = this.envelope(3, this.bytes("{}"));
        assertThrows(IOException.class, () -> codec.read(new ByteArrayInputStream(encoded), "identity"));
    }

    @Test
    public void rejectsUnknownOutgoingCompression() {
        // 不能为未支持的算法构造误导性压缩标志。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        ConnectStatusException failure = assertThrows(ConnectStatusException.class, () -> {
            codec.frame(this.bytes("message"), "deflate");
        });
        assertEquals("unimplemented", failure.getCode());
    }

    @Test
    public void rejectsUnknownIncomingCompression() {
        // 对端声明未知压缩算法时明确报错。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = this.envelope(1, this.bytes("compressed"));
        ConnectStatusException failure = assertThrows(ConnectStatusException.class, () -> {
            codec.read(new ByteArrayInputStream(encoded), "deflate");
        });
        assertEquals("unimplemented", failure.getCode());
    }

    @Test
    public void rejectsInvalidGzipHeader() {
        // 压缩正文必须符合 gzip 格式。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = this.envelope(1, this.bytes("not gzip"));
        assertThrows(IOException.class, () -> codec.read(new ByteArrayInputStream(encoded), "gzip"));
    }

    @Test
    public void rejectsTruncatedGzipTrailer() throws Exception {
        // 校验消息尾部，不能把不完整压缩流当成有效正文。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] gzip = this.gzip(this.bytes("compressed"));
        byte[] truncated = Arrays.copyOf(gzip, gzip.length - 4);
        byte[] encoded = this.envelope(1, truncated);
        assertThrows(IOException.class, () -> codec.read(new ByteArrayInputStream(encoded), "gzip"));
    }

    @Test
    public void identityCompressionKeepsBytes() throws Exception {
        // 未选择压缩时不复制和改写消息正文。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] message = this.bytes("unchanged");
        assertSame(message, codec.compress(message, "identity"));
    }

    @Test
    public void emptyEncodingUsesIdentity() throws Exception {
        // 未设置编码按 identity 处理。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] message = this.bytes("unchanged");
        assertSame(message, codec.decompress(message, ""));
    }

    @Test
    public void gzipNameIgnoresCase() throws Exception {
        // 算法名称沿用 HTTP 头的大小写无关语义。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] message = this.bytes("compressed");
        byte[] compressed = codec.compress(message, "GZIP");
        assertArrayEquals(message, codec.decompress(compressed, "gZiP"));
    }

    @Test
    public void endEncoderRejectsOversizedMetadata() {
        // 结束正文与普通消息受同一大小上限约束。
        ConnectFrameCodec codec = new ConnectFrameCodec(16);
        Map<String, List<String>> metadata = Map.of("x-trace", List.of("very-long-trailing-metadata"));
        ConnectStatusException failure = assertThrows(ConnectStatusException.class, () -> codec.end(null, metadata));
        assertEquals("resource_exhausted", failure.getCode());
    }

    @Test
    public void errorEncoderRejectsOversizedDetails() {
        // 普通调用错误正文也不能绕过配置上限。
        ConnectFrameCodec codec = new ConnectFrameCodec(16);
        ConnectStatusException failure = new ConnectStatusException("internal", "a very long error", null, null);
        ConnectStatusException rejected = assertThrows(ConnectStatusException.class, () -> codec.error(failure));
        assertEquals("resource_exhausted", rejected.getCode());
    }

    @Test
    public void errorDecoderRejectsOversizedDocument() {
        // 在解析 JSON 前先检查收到的错误正文长度。
        ConnectFrameCodec codec = new ConnectFrameCodec(16);
        byte[] document = this.bytes("{\"code\":\"internal\"}");
        ConnectStatusException failure = assertThrows(ConnectStatusException.class, () -> {
            codec.readError(document, null);
        });
        assertEquals("resource_exhausted", failure.getCode());
    }

    @Test
    public void errorEncoderRetainsUnicodeDescription() throws Exception {
        // 错误消息采用 JSON UTF-8 直接承载 Unicode。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        ConnectStatusException failure = new ConnectStatusException("invalid_argument", "参数错误", null, null);
        ConnectStatusException decoded = codec.readError(codec.error(failure), Map.of());
        assertEquals("参数错误", decoded.getDescription());
    }

    @Test
    public void absentErrorDescriptionBecomesEmpty() throws Exception {
        // message 是可选字段，省略后保留空描述。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        ConnectStatusException failure = codec.readError(this.bytes("{\"code\":\"unknown\"}"), Map.of());
        assertEquals("", failure.getDescription());
    }

    @Test
    public void errorDecoderPreservesSuppliedResponseMetadata() throws Exception {
        // 普通调用从 HTTP 响应头取得错误元数据。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        Map<String, List<String>> metadata = Map.of("x-trace", List.of("first", "second"));
        ConnectStatusException failure = codec.readError(this.bytes("{\"code\":\"unknown\"}"), metadata);
        assertEquals(metadata, failure.getMetadata());
    }

    @Test
    public void rejectsPrefixTruncatedAfter1Bytes() {
        // 只收到 1 字节前缀时，不能视为完整长度字段。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = new byte[1];
        assertThrows(IOException.class, () -> codec.read(new ByteArrayInputStream(encoded), null));
    }

    @Test
    public void rejectsPrefixTruncatedAfter2Bytes() {
        // 只收到 2 字节前缀时，不能视为完整长度字段。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = new byte[2];
        assertThrows(IOException.class, () -> codec.read(new ByteArrayInputStream(encoded), null));
    }

    @Test
    public void rejectsPrefixTruncatedAfter3Bytes() {
        // 只收到 3 字节前缀时，不能视为完整长度字段。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = new byte[3];
        assertThrows(IOException.class, () -> codec.read(new ByteArrayInputStream(encoded), null));
    }

    @Test
    public void rejectsPrefixTruncatedAfter4Bytes() {
        // 只收到 4 字节前缀时，不能视为完整长度字段。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = new byte[4];
        assertThrows(IOException.class, () -> codec.read(new ByteArrayInputStream(encoded), null));
    }

    @Test
    public void rejectsMalformedErrorJson() {
        // 不符合 Connect 错误结构的正文不能降级成成功响应。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] document = this.bytes("{");
        assertThrows(IOException.class, () -> codec.readError(document, Map.of()));
    }

    @Test
    public void rejectsErrorArray() {
        // 不符合 Connect 错误结构的正文不能降级成成功响应。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] document = this.bytes("[]");
        assertThrows(IOException.class, () -> codec.readError(document, Map.of()));
    }

    @Test
    public void rejectsNullErrorDocument() {
        // 不符合 Connect 错误结构的正文不能降级成成功响应。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] document = this.bytes("null");
        assertThrows(IOException.class, () -> codec.readError(document, Map.of()));
    }

    @Test
    public void rejectsEmptyErrorDocument() {
        // 不符合 Connect 错误结构的正文不能降级成成功响应。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] document = this.bytes("");
        assertThrows(IOException.class, () -> codec.readError(document, Map.of()));
    }

    @Test
    public void rejectsErrorWithoutCode() {
        // 不符合 Connect 错误结构的正文不能降级成成功响应。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] document = this.bytes("{}");
        assertThrows(IOException.class, () -> codec.readError(document, Map.of()));
    }

    @Test
    public void rejectsNonTextualErrorCode() {
        // 不符合 Connect 错误结构的正文不能降级成成功响应。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] document = this.bytes("{\"code\":1}");
        assertThrows(IOException.class, () -> codec.readError(document, Map.of()));
    }

    @Test
    public void rejectsUnknownErrorCode() {
        // 不符合 Connect 错误结构的正文不能降级成成功响应。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] document = this.bytes("{\"code\":\"custom_error\"}");
        assertThrows(IOException.class, () -> codec.readError(document, Map.of()));
    }

    @Test
    public void rejectsNonTextualErrorMessage() {
        // 不符合 Connect 错误结构的正文不能降级成成功响应。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] document = this.bytes("{\"code\":\"unknown\",\"message\":4}");
        assertThrows(IOException.class, () -> codec.readError(document, Map.of()));
    }

    @Test
    public void rejectsNonArrayDetails() {
        // 不符合 Connect 错误结构的正文不能降级成成功响应。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] document = this.bytes("{\"code\":\"unknown\",\"details\":{}}");
        assertThrows(IOException.class, () -> codec.readError(document, Map.of()));
    }

    @Test
    public void rejectsDetailWithoutType() {
        // 不符合 Connect 错误结构的正文不能降级成成功响应。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] document = this.bytes("{\"code\":\"unknown\",\"details\":[{\"value\":\"AQI=\"}]}");
        assertThrows(IOException.class, () -> codec.readError(document, Map.of()));
    }

    @Test
    public void rejectsDetailWithEmptyType() {
        // 不符合 Connect 错误结构的正文不能降级成成功响应。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] document = this.bytes("{\"code\":\"unknown\",\"details\":[{\"type\":\"\",\"value\":\"AQI=\"}]}");
        assertThrows(IOException.class, () -> codec.readError(document, Map.of()));
    }

    @Test
    public void rejectsDetailWithoutValue() {
        // 不符合 Connect 错误结构的正文不能降级成成功响应。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] document = this.bytes("{\"code\":\"unknown\",\"details\":[{\"type\":\"example.Detail\"}]}");
        assertThrows(IOException.class, () -> codec.readError(document, Map.of()));
    }

    @Test
    public void rejectsNonTextualDetailValue() {
        // 不符合 Connect 错误结构的正文不能降级成成功响应。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] document = this.bytes("{\"code\":\"unknown\",\"details\":[{\"type\":\"example.Detail\",\"value\":1}]}");
        assertThrows(IOException.class, () -> codec.readError(document, Map.of()));
    }

    @Test
    public void rejectsInvalidBase64DetailValue() {
        // 不符合 Connect 错误结构的正文不能降级成成功响应。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        String detail = "{\"type\":\"example.Detail\",\"value\":\"**\"}";
        byte[] document = this.bytes("{\"code\":\"unknown\",\"details\":[" + detail + "]}");
        assertThrows(IOException.class, () -> codec.readError(document, Map.of()));
    }

    @Test
    public void rejectsTrailingErrorJson() {
        // 不符合 Connect 错误结构的正文不能降级成成功响应。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] document = this.bytes("{\"code\":\"unknown\"} {}");
        assertThrows(IOException.class, () -> codec.readError(document, Map.of()));
    }

    @Test
    public void rejectsDuplicateErrorCode() {
        // 不符合 Connect 错误结构的正文不能降级成成功响应。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] document = this.bytes("{\"code\":\"internal\",\"code\":\"unknown\"}");
        assertThrows(IOException.class, () -> codec.readError(document, Map.of()));
    }

    @Test
    public void rejectsEndArray() {
        // 结束事件必须携带合法 JSON 结构及尾部元数据。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = this.envelope(2, this.bytes("[]"));
        assertThrows(IOException.class, () -> codec.read(new ByteArrayInputStream(encoded), null));
    }

    @Test
    public void rejectsNullEndDocument() {
        // 结束事件必须携带合法 JSON 结构及尾部元数据。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = this.envelope(2, this.bytes("null"));
        assertThrows(IOException.class, () -> codec.read(new ByteArrayInputStream(encoded), null));
    }

    @Test
    public void rejectsEndWithMalformedJson() {
        // 结束事件必须携带合法 JSON 结构及尾部元数据。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = this.envelope(2, this.bytes("{"));
        assertThrows(IOException.class, () -> codec.read(new ByteArrayInputStream(encoded), null));
    }

    @Test
    public void rejectsEndWithTrailingJson() {
        // 结束事件必须携带合法 JSON 结构及尾部元数据。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = this.envelope(2, this.bytes("{} {}"));
        assertThrows(IOException.class, () -> codec.read(new ByteArrayInputStream(encoded), null));
    }

    @Test
    public void rejectsEndWithInvalidError() {
        // 结束事件必须携带合法 JSON 结构及尾部元数据。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = this.envelope(2, this.bytes("{\"error\":{}}"));
        assertThrows(IOException.class, () -> codec.read(new ByteArrayInputStream(encoded), null));
    }

    @Test
    public void rejectsNonObjectTrailingMetadata() {
        // 结束事件必须携带合法 JSON 结构及尾部元数据。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = this.envelope(2, this.bytes("{\"metadata\":[]}"));
        assertThrows(IOException.class, () -> codec.read(new ByteArrayInputStream(encoded), null));
    }

    @Test
    public void rejectsNonArrayTrailingMetadataValues() {
        // 结束事件必须携带合法 JSON 结构及尾部元数据。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = this.envelope(2, this.bytes("{\"metadata\":{\"x-trace\":\"one\"}}"));
        assertThrows(IOException.class, () -> codec.read(new ByteArrayInputStream(encoded), null));
    }

    @Test
    public void rejectsNonTextualTrailingMetadataValue() {
        // 结束事件必须携带合法 JSON 结构及尾部元数据。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = this.envelope(2, this.bytes("{\"metadata\":{\"x-trace\":[1]}}"));
        assertThrows(IOException.class, () -> codec.read(new ByteArrayInputStream(encoded), null));
    }

    @Test
    public void rejectsReservedTrailingMetadataName() {
        // 结束事件必须携带合法 JSON 结构及尾部元数据。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = this.envelope(2, this.bytes("{\"metadata\":{\"connect-trace\":[\"one\"]}}"));
        assertThrows(IOException.class, () -> codec.read(new ByteArrayInputStream(encoded), null));
    }

    @Test
    public void rejectsInvalidTrailingMetadataName() {
        // 结束事件必须携带合法 JSON 结构及尾部元数据。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = this.envelope(2, this.bytes("{\"metadata\":{\"bad name\":[\"one\"]}}"));
        assertThrows(IOException.class, () -> codec.read(new ByteArrayInputStream(encoded), null));
    }

    @Test
    public void rejectsEmptyAsciiMetadata() {
        // 结束事件必须携带合法 JSON 结构及尾部元数据。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = this.envelope(2, this.bytes("{\"metadata\":{\"x-trace\":[\"\"]}}"));
        assertThrows(IOException.class, () -> codec.read(new ByteArrayInputStream(encoded), null));
    }

    @Test
    public void rejectsNonAsciiMetadata() {
        // 结束事件必须携带合法 JSON 结构及尾部元数据。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = this.envelope(2, this.bytes("{\"metadata\":{\"x-trace\":[\"中文\"]}}"));
        assertThrows(IOException.class, () -> codec.read(new ByteArrayInputStream(encoded), null));
    }

    @Test
    public void rejectsMetadataNewline() {
        // 结束事件必须携带合法 JSON 结构及尾部元数据。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = this.envelope(2, this.bytes("{\"metadata\":{\"x-trace\":[\"line\\nline\"]}}"));
        assertThrows(IOException.class, () -> codec.read(new ByteArrayInputStream(encoded), null));
    }

    @Test
    public void rejectsInvalidBinaryMetadata() {
        // 结束事件必须携带合法 JSON 结构及尾部元数据。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = this.envelope(2, this.bytes("{\"metadata\":{\"data-bin\":[\"**\"]}}"));
        assertThrows(IOException.class, () -> codec.read(new ByteArrayInputStream(encoded), null));
    }

    @Test
    public void compressedDataCannotHaveEmptyWireBody() {
        // 即使消息内容为空，压缩信封也必须包含合法 gzip 数据。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = this.envelope(1, new byte[0]);
        assertThrows(IOException.class, () -> codec.read(new ByteArrayInputStream(encoded), "gzip"));
    }

    @Test
    public void compressedEndCannotHaveEmptyWireBody() {
        // 压缩结束信封不能用零字节正文绕过 JSON 解析。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = this.envelope(3, new byte[0]);
        assertThrows(IOException.class, () -> codec.read(new ByteArrayInputStream(encoded), "gzip"));
    }

    @Test
    public void emptyUnaryGzipBodyStaysEmpty() throws Exception {
        // 普通调用的空压缩正文与带压缩标志的流信封分开处理。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        assertArrayEquals(new byte[0], codec.decompress(new byte[0], "gzip"));
    }

    @Test
    public void uncompressedMessageIgnoresResponseCompressionSelection() throws Exception {
        // 协商了 gzip 后，对端仍可逐条发送未压缩消息。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] message = this.bytes("uncompressed");
        ConnectEnvelope decoded = codec.read(new ByteArrayInputStream(this.envelope(0, message)), "gzip");
        assertArrayEquals(message, decoded.message());
    }

    @Test
    public void trailingMetadataNameIsNormalized() throws Exception {
        // 元数据名称不区分大小写，同名字段合并保留所有值。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        String document = "{\"metadata\":{\"X-Trace\":[\"first\"],\"x-trace\":[\"second\"]}}";
        byte[] encoded = this.envelope(2, this.bytes(document));
        ConnectEnvelope decoded = codec.read(new ByteArrayInputStream(encoded), null);
        assertEquals(List.of("first", "second"), decoded.metadata().get("x-trace"));
    }

    @Test
    public void binaryMetadataAcceptsUnpaddedBase64() throws Exception {
        // 二进制元数据允许省略 Base64 的尾部填充。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = this.envelope(2, this.bytes("{\"metadata\":{\"data-bin\":[\"AQI\"]}}"));
        ConnectEnvelope decoded = codec.read(new ByteArrayInputStream(encoded), null);
        assertEquals(List.of("AQI"), decoded.metadata().get("data-bin"));
    }

    @Test
    public void unknownErrorDetailFieldsArePreserved() throws Exception {
        // 附加的展开详情可供理解相应类型的对端继续使用。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        String detail = "{\"type\":\"example.Detail\",\"value\":\"AQI=\",\"debug\":\"visible\"}";
        String document = "{\"code\":\"internal\",\"details\":[" + detail + "]}";
        ConnectStatusException failure = codec.readError(this.bytes(document), Map.of());
        assertEquals("visible", failure.getDetails().get(0).get("debug"));
    }

    @Test
    public void errorWithoutDescriptionOmitsMessageField() throws Exception {
        // 可选描述为空时，生成最小标准错误正文。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        ConnectStatusException failure = new ConnectStatusException("unknown", "", null, null);
        String encoded = new String(codec.error(failure), StandardCharsets.UTF_8);
        assertEquals("{\"code\":\"unknown\"}", encoded);
    }

    @Test
    public void endCombinesFailureAndExplicitMetadata() throws Exception {
        // 错误自带的尾部值与显式结束元数据按重复字段合并。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        Map<String, List<String>> failureMetadata = Map.of("X-Trace", List.of("first"));
        ConnectStatusException failure = new ConnectStatusException("internal", "failed", null, failureMetadata);
        byte[] encoded = codec.end(failure, Map.of("x-trace", List.of("second")));

        // 大小写不同的同名字段归一后仍保留两端的值。
        ConnectEnvelope decoded = codec.read(new ByteArrayInputStream(encoded), null);
        assertEquals(List.of("first", "second"), decoded.metadata().get("x-trace"));
    }

    @Test
    public void endNormalizesBinaryMetadataPadding() throws Exception {
        // 发送时采用 Connect 约定的无填充 Base64。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        byte[] encoded = codec.end(null, Map.of("data-bin", List.of("AQI=")));
        ConnectEnvelope decoded = codec.read(new ByteArrayInputStream(encoded), null);
        assertEquals(List.of("AQI"), decoded.metadata().get("data-bin"));
    }

    @Test
    public void outgoingErrorRejectsInvalidDetailType() {
        // 应用自行构造的详情也必须包含明确类型。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        Map<String, Object> detail = Map.of("type", "", "value", "AQI=");
        ConnectStatusException failure = new ConnectStatusException("internal", "failed", List.of(detail), null);
        assertThrows(IOException.class, () -> codec.error(failure));
    }

    @Test
    public void outgoingErrorRejectsInvalidDetailValue() {
        // 应用详情不能发送无效的二进制数据。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        Map<String, Object> detail = Map.of("type", "example.Detail", "value", "**");
        ConnectStatusException failure = new ConnectStatusException("internal", "failed", List.of(detail), null);
        assertThrows(IOException.class, () -> codec.error(failure));
    }

    @Test
    public void outgoingErrorRejectsReservedMetadata() {
        // 应用错误元数据不能冒用 Connect 保留字段。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        Map<String, List<String>> metadata = Map.of("connect-trace", List.of("value"));
        ConnectStatusException failure = new ConnectStatusException("internal", "failed", null, metadata);
        assertThrows(IOException.class, () -> codec.error(failure));
    }

    @Test
    public void outgoingEndRejectsInvalidMetadataName() {
        // 结束编码前拒绝不合法的 HTTP 元数据名。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        Map<String, List<String>> metadata = Map.of("bad name", List.of("value"));
        assertThrows(IOException.class, () -> codec.end(null, metadata));
    }

    @Test
    public void outgoingEndRejectsNonAsciiMetadataValue() {
        // 普通元数据只能携带可打印 ASCII。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        Map<String, List<String>> metadata = Map.of("x-trace", List.of("中文"));
        assertThrows(IOException.class, () -> codec.end(null, metadata));
    }

    @Test
    public void outgoingEndRejectsInvalidBinaryMetadataValue() {
        // 二进制字段在输出前验证 Base64 语法。
        ConnectFrameCodec codec = new ConnectFrameCodec(1024);
        Map<String, List<String>> metadata = Map.of("data-bin", List.of("**"));
        assertThrows(IOException.class, () -> codec.end(null, metadata));
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] envelope(int flags, byte[] message) {
        return ByteBuffer.allocate(5 + message.length).put((byte) flags).putInt(message.length).put(message).array();
    }

    private byte[] gzip(byte[] message) throws IOException {
        // 独立使用 JDK 压缩实现生成测试输入。
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(message);
        }
        return output.toByteArray();
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.triple.codec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TripleFrameCodecTest {
    @Test
    public void rejectsZeroMessageLimit() {
        // 零长度上限不能作为有效配置。
        assertThrows(IllegalArgumentException.class, () -> new TripleFrameCodec(0));
    }

    @Test
    public void rejectsNegativeMessageLimit() {
        // 负数上限不能绕过消息大小校验。
        assertThrows(IllegalArgumentException.class, () -> new TripleFrameCodec(-1));
    }

    @Test
    public void plainFrameContainsBigEndianLength() throws Exception {
        // 普通帧以零压缩标志及四字节大端长度开头。
        TripleFrameCodec codec = new TripleFrameCodec(1024);
        byte[] frame = codec.frame(new byte[] { 10, 20, 30 }, null);
        assertArrayEquals(new byte[] { 0, 0, 0, 0, 3, 10, 20, 30 }, frame);
    }

    @Test
    public void gzipFrameContainsCompressedWireLength() throws Exception {
        // 帧头记录压缩后的线协议长度。
        TripleFrameCodec codec = new TripleFrameCodec(1024);
        byte[] message = this.bytes("compressed message");
        byte[] frame = codec.frame(message, "gzip");

        // 用 JDK 解压正文，独立确认内容和标志。
        assertEquals(1, frame[0]);
        assertEquals(frame.length - 5, ByteBuffer.wrap(frame, 1, 4).getInt());
        byte[] compressed = Arrays.copyOfRange(frame, 5, frame.length);
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            assertArrayEquals(message, gzip.readAllBytes());
        }
    }

    @Test
    public void cleanEofReturnsNoMessage() throws Exception {
        // 帧边界上的 EOF 表示流结束。
        TripleFrameCodec codec = new TripleFrameCodec(1024);
        assertNull(codec.readMessage(new ByteArrayInputStream(new byte[0]), null));
    }

    @Test
    public void zeroLengthFrameIsARealMessage() throws Exception {
        // 零字节消息不能与 EOF 混淆。
        TripleFrameCodec codec = new TripleFrameCodec(1024);
        byte[] frame = this.frame(0, new byte[0]);
        assertArrayEquals(new byte[0], codec.readMessage(new ByteArrayInputStream(frame), null));
    }

    @Test
    public void readerLeavesFollowingFrameAvailable() throws Exception {
        // 两条消息共享同一个输入流。
        TripleFrameCodec codec = new TripleFrameCodec(1024);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(this.frame(0, this.bytes("first")));
        output.write(this.frame(0, this.bytes("second")));
        ByteArrayInputStream input = new ByteArrayInputStream(output.toByteArray());

        // 每次只消费当前帧，保持下一帧和结束事件的顺序。
        assertArrayEquals(this.bytes("first"), codec.readMessage(input, null));
        assertArrayEquals(this.bytes("second"), codec.readMessage(input, null));
        assertNull(codec.readMessage(input, null));
    }

    @Test
    public void unaryDecoderAcceptsExactlyOneMessage() throws Exception {
        // 普通调用的正文恰好包含一个消息帧。
        TripleFrameCodec codec = new TripleFrameCodec(1024);
        byte[] message = this.bytes("unary");
        assertArrayEquals(message, codec.unframe(this.frame(0, message), null));
    }

    @Test
    public void unaryDecoderRejectsEmptyBody() {
        // 缺少消息帧不能解释成成功的空消息。
        TripleFrameCodec codec = new TripleFrameCodec(1024);
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> codec.unframe(new byte[0], null));
        assertEquals(13, failure.getCode());
    }

    @Test
    public void unaryDecoderRejectsMultipleMessages() throws Exception {
        // 普通调用不能悄悄忽略多余的帧。
        TripleFrameCodec codec = new TripleFrameCodec(1024);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(this.frame(0, this.bytes("first")));
        output.write(this.frame(0, this.bytes("second")));
        byte[] body = output.toByteArray();

        // 多帧正文报告协议内部错误。
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> codec.unframe(body, null));
        assertEquals(13, failure.getCode());
    }

    @Test
    public void rejectsPrefixTruncatedAfterFlag() {
        // 只有标志位还不能构成完整帧头。
        TripleFrameCodec codec = new TripleFrameCodec(1024);
        byte[] frame = new byte[] { 0 };
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> {
            codec.readMessage(new ByteArrayInputStream(frame), null);
        });
        assertEquals(13, failure.getCode());
    }

    @Test
    public void rejectsPrefixTruncatedAfterTwoBytes() {
        // 长度字段仅到达一个字节时报告半帧。
        TripleFrameCodec codec = new TripleFrameCodec(1024);
        byte[] frame = new byte[] { 0, 0 };
        assertThrows(GrpcStatusException.class, () -> codec.readMessage(new ByteArrayInputStream(frame), null));
    }

    @Test
    public void rejectsPrefixTruncatedAfterThreeBytes() {
        // 长度字段仅到达两个字节时报告半帧。
        TripleFrameCodec codec = new TripleFrameCodec(1024);
        byte[] frame = new byte[] { 0, 0, 0 };
        assertThrows(GrpcStatusException.class, () -> codec.readMessage(new ByteArrayInputStream(frame), null));
    }

    @Test
    public void rejectsPrefixTruncatedAfterFourBytes() {
        // 长度字段缺少最后一个字节时不能继续解码正文。
        TripleFrameCodec codec = new TripleFrameCodec(1024);
        byte[] frame = new byte[] { 0, 0, 0, 0 };
        assertThrows(GrpcStatusException.class, () -> codec.readMessage(new ByteArrayInputStream(frame), null));
    }

    @Test
    public void rejectsTruncatedPayload() {
        // 声明三字节正文，实际只到达两字节。
        TripleFrameCodec codec = new TripleFrameCodec(1024);
        byte[] frame = new byte[] { 0, 0, 0, 0, 3, 1, 2 };
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> {
            codec.readMessage(new ByteArrayInputStream(frame), null);
        });
        assertEquals(13, failure.getCode());
    }

    @Test
    public void rejectsReservedFlag() {
        // Triple 消息没有结束信封标志，标志位 2 不合法。
        TripleFrameCodec codec = new TripleFrameCodec(1024);
        byte[] frame = this.frame(2, new byte[0]);
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> {
            codec.readMessage(new ByteArrayInputStream(frame), null);
        });
        assertEquals(13, failure.getCode());
    }

    @Test
    public void rejectsAllBitsFlag() {
        // 无符号字节的高位标志同样不能被接受。
        TripleFrameCodec codec = new TripleFrameCodec(1024);
        byte[] frame = this.frame(255, new byte[0]);
        assertThrows(GrpcStatusException.class, () -> codec.readMessage(new ByteArrayInputStream(frame), null));
    }

    @Test
    public void acceptsIncomingMessageExactlyAtLimit() throws Exception {
        // 正文恰好等于上限时保持可读。
        TripleFrameCodec codec = new TripleFrameCodec(3);
        byte[] message = new byte[] { 1, 2, 3 };
        byte[] frame = this.frame(0, message);
        assertArrayEquals(message, codec.readMessage(new ByteArrayInputStream(frame), null));
    }

    @Test
    public void rejectsOversizedDeclarationBeforeReadingBody() {
        // 没有正文时也先校验声明长度，避免超限分配。
        TripleFrameCodec codec = new TripleFrameCodec(3);
        byte[] frame = new byte[] { 0, 0, 0, 0, 4 };
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> {
            codec.readMessage(new ByteArrayInputStream(frame), null);
        });
        assertEquals(8, failure.getCode());
    }

    @Test
    public void rejectsUnsignedLengthBomb() {
        // 0xffffffff 必须按无符号长度处理，不能绕过大小上限。
        TripleFrameCodec codec = new TripleFrameCodec(1024);
        byte[] frame = new byte[] { 0, -1, -1, -1, -1 };
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> {
            codec.readMessage(new ByteArrayInputStream(frame), null);
        });
        assertEquals(8, failure.getCode());
    }

    @Test
    public void acceptsOutgoingMessageExactlyAtLimit() throws Exception {
        // 发送端上限约束正文，不包含五字节帧头。
        TripleFrameCodec codec = new TripleFrameCodec(3);
        byte[] encoded = codec.frame(new byte[] { 1, 2, 3 }, null);
        assertEquals(8, encoded.length);
    }

    @Test
    public void rejectsOversizedOutgoingMessage() {
        // 编码前即拒绝过大的业务消息。
        TripleFrameCodec codec = new TripleFrameCodec(3);
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> codec.frame(new byte[4], null));
        assertEquals(8, failure.getCode());
    }

    @Test
    public void checksStandaloneMessageAtLimit() {
        // HTTP 普通正文复用同一个包含端点的上限。
        TripleFrameCodec codec = new TripleFrameCodec(3);
        codec.check(new byte[3]);
    }

    @Test
    public void rejectsOversizedStandaloneMessage() {
        // HTTP 正文也不能绕过公共消息上限。
        TripleFrameCodec codec = new TripleFrameCodec(3);
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> codec.check(new byte[4]));
        assertEquals(8, failure.getCode());
    }

    @Test
    public void nullCompressionKeepsOriginalBytes() throws Exception {
        // 未设置压缩算法时保留原始正文。
        TripleFrameCodec codec = new TripleFrameCodec(1024);
        byte[] message = this.bytes("identity");
        assertSame(message, codec.compress(message, null));
    }

    @Test
    public void emptyCompressionKeepsOriginalBytes() throws Exception {
        // 空编码值与 identity 相同。
        TripleFrameCodec codec = new TripleFrameCodec(1024);
        byte[] message = this.bytes("identity");
        assertSame(message, codec.decompress(message, ""));
    }

    @Test
    public void identityNameIgnoresCase() throws Exception {
        // identity 的大小写不改变算法选择。
        TripleFrameCodec codec = new TripleFrameCodec(1024);
        byte[] message = this.bytes("identity");
        assertSame(message, codec.compress(message, "IdEnTiTy"));
    }

    @Test
    public void gzipNameIgnoresCase() throws Exception {
        // HTTP 编码名称按大小写不敏感的语义处理。
        TripleFrameCodec codec = new TripleFrameCodec(1024);
        byte[] message = this.bytes("gzip");
        byte[] compressed = codec.compress(message, "GZIP");
        assertArrayEquals(message, codec.decompress(compressed, "gZiP"));
    }

    @Test
    public void rejectsOutgoingCompressedBodyAboveLimit() {
        // gzip 包装开销也计入线协议消息上限。
        TripleFrameCodec codec = new TripleFrameCodec(1);
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> {
            codec.compress(new byte[1], "gzip");
        });
        assertEquals(8, failure.getCode());
    }

    @Test
    public void rejectsIncomingCompressedBodyAboveLimit() throws Exception {
        // 解压前先限制压缩正文，不能只检查最终正文大小。
        TripleFrameCodec codec = new TripleFrameCodec(1);
        byte[] compressed = this.gzip(new byte[1]);
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> {
            codec.decompress(compressed, "gzip");
        });
        assertEquals(8, failure.getCode());
    }

    @Test
    public void rejectsGzipExpansionAboveLimit() throws Exception {
        // 小体积压缩包解压为超限正文时停止读取。
        TripleFrameCodec codec = new TripleFrameCodec(64);
        byte[] frame = this.frame(1, this.gzip(new byte[1024]));
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> {
            codec.readMessage(new ByteArrayInputStream(frame), "gzip");
        });
        assertEquals(8, failure.getCode());
    }

    @Test
    public void acceptsUncompressedMessageAfterGzipNegotiation() throws Exception {
        // 协商 gzip 后，对端仍可逐条发送未压缩的消息。
        TripleFrameCodec codec = new TripleFrameCodec(1024);
        byte[] message = this.bytes("plain");
        byte[] frame = this.frame(0, message);
        assertArrayEquals(message, codec.readMessage(new ByteArrayInputStream(frame), "gzip"));
    }

    @Test
    public void rejectsCompressedFlagWithoutEncoding() {
        // 压缩标志必须有对应的 grpc-encoding。
        TripleFrameCodec codec = new TripleFrameCodec(1024);
        byte[] frame = this.frame(1, this.bytes("compressed"));
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> {
            codec.readMessage(new ByteArrayInputStream(frame), null);
        });
        assertEquals(13, failure.getCode());
    }

    @Test
    public void rejectsUnsupportedOutgoingEncoding() {
        // 未支持的算法不能生成误导性的压缩消息。
        TripleFrameCodec codec = new TripleFrameCodec(1024);
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> {
            codec.frame(this.bytes("message"), "deflate");
        });
        assertEquals(12, failure.getCode());
    }

    @Test
    public void rejectsUnsupportedIncomingEncoding() {
        // 对端声明未知算法时明确报告未实现。
        TripleFrameCodec codec = new TripleFrameCodec(1024);
        byte[] frame = this.frame(1, this.bytes("compressed"));
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> {
            codec.readMessage(new ByteArrayInputStream(frame), "deflate");
        });
        assertEquals(12, failure.getCode());
    }

    @Test
    public void rejectsMalformedGzipBody() {
        // 无效压缩头不能降级成原始字节处理。
        TripleFrameCodec codec = new TripleFrameCodec(1024);
        byte[] compressed = this.bytes("not gzip");
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> {
            codec.decompress(compressed, "gzip");
        });
        assertEquals(13, failure.getCode());
        assertTrue(failure.getCause() instanceof IOException);
    }

    @Test
    public void rejectsTruncatedGzipTrailer() throws Exception {
        // 校验完整 gzip 尾部，避免截断正文被当作成功。
        TripleFrameCodec codec = new TripleFrameCodec(1024);
        byte[] gzip = this.gzip(this.bytes("compressed"));
        byte[] truncated = Arrays.copyOf(gzip, gzip.length - 4);
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> {
            codec.decompress(truncated, "gzip");
        });
        assertEquals(13, failure.getCode());
    }

    @Test
    public void decodingDoesNotCloseBorrowedInput() throws Exception {
        // 输入流所有权保留在调用方。
        TripleFrameCodec codec = new TripleFrameCodec(1024);
        AtomicBoolean closed = new AtomicBoolean();
        ByteArrayInputStream input = new ByteArrayInputStream(this.frame(0, this.bytes("borrowed"))) {
            @Override
            public void close() {
                closed.set(true);
            }
        };

        // 解码结束不能关闭网络层借出的输入流。
        codec.readMessage(input, null);
        assertFalse(closed.get());
    }

    @Test
    public void failedDecodingDoesNotCloseBorrowedInput() {
        // 错误路径同样不能反向关闭借用的输入流。
        TripleFrameCodec codec = new TripleFrameCodec(1024);
        AtomicBoolean closed = new AtomicBoolean();
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[] { 0, 0, 0, 0, 2, 1 }) {
            @Override
            public void close() {
                closed.set(true);
            }
        };

        // 生命周期由调用者处理，编解码器只报告失败。
        assertThrows(GrpcStatusException.class, () -> codec.readMessage(input, null));
        assertFalse(closed.get());
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] frame(int flag, byte[] body) {
        return ByteBuffer.allocate(body.length + 5).put((byte) flag).putInt(body.length).put(body).array();
    }

    private byte[] gzip(byte[] message) throws IOException {
        // 用独立的 JDK 编码器准备线上压缩正文。
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(message);
        }
        return output.toByteArray();
    }
}

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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import net.hasor.meteor.connector.ProtocolConfig;
import org.junit.Test;
import static org.junit.Assert.*;

public class GrpcWebBase64InputStreamTest {
    @Test
    public void consecutivePaddedSegmentsContinueUntilEof() throws Exception {
        // 三段分别包含一个、两个、三个字节，padding 不是整体 EOF。
        GrpcWebBase64InputStream input = this.input("YQ==YmM=ZGVm");

        // 所有片段按顺序组合成一个字节流。
        assertArrayEquals("abcdef".getBytes(StandardCharsets.US_ASCII), input.readAllBytes());
    }

    @Test
    public void segmentsMaySplitInsideGrpcPrefixAndPayload() throws Exception {
        // runtime flush 可以在帧头或正文任意字节后生成 padding。
        byte[] message = new byte[] { 0, 0, 0, 0, 4, 10, 20, 30, 40 };
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        for (int index = 0; index < message.length; index++) {
            byte[] segment = new byte[] { message[index] };
            wire.write(Base64.getEncoder().encode(segment));
        }
        ProtocolConfig config = ProtocolConfig.newConfig("grpc", "http", Map.of());
        GrpcWebCodec codec = new GrpcWebCodec(config);

        // 在 gRPC 帧解析之前重建连续二进制流。
        assertArrayEquals(message, codec.decodeRequest(wire.toByteArray(), true));
    }

    @Test
    public void responseSegmentsNeedNotMatchMessageBoundaries() throws Exception {
        // 两个 data 帧及尾帧先组合，再按与帧无关的块长度独立编码。
        ProtocolConfig config = ProtocolConfig.newConfig("grpc", "http", Map.of());
        GrpcWebCodec codec = new GrpcWebCodec(config);
        byte[] message = new byte[] { 0, 0, 0, 0, 1, 7 };
        byte[] body = codec.encodeResponse(message, Map.of("grpc-status", List.of("0")), false);
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        for (int start = 0; start < body.length; start += 7) {
            int end = Math.min(body.length, start + 7);
            wire.write(Base64.getEncoder().encode(Arrays.copyOfRange(body, start, end)));
        }

        // 任意 padding 边界不影响最终状态及消息字节。
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        ByteArrayInputStream input = new ByteArrayInputStream(wire.toByteArray());
        Map<String, List<String>> trailers = codec.decodeResponse(input, data, true, "identity");
        assertArrayEquals(message, data.toByteArray());
        assertEquals(List.of("0"), trailers.get("grpc-status"));
    }

    @Test
    public void oneByteReadsPreserveAllByteValues() throws Exception {
        // 输入输出可以按单个字节交付，不能假定调用者提供整块缓冲区。
        byte[] source = new byte[256];
        for (int index = 0; index < source.length; index++) {
            source[index] = (byte) index;
        }
        byte[] encoded = Base64.getEncoder().encode(source);
        GrpcWebBase64InputStream input = new GrpcWebBase64InputStream(new ByteArrayInputStream(encoded));
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            decoded.write(value);
        }

        // 有符号 Java byte 和无符号流读取之间不丢位。
        assertArrayEquals(source, decoded.toByteArray());
    }

    @Test
    public void quartetCanCrossEveryTransportChunk() throws Exception {
        // 底层每次只交付一个字节，quartet 和 padding 都跨读取边界。
        byte[] encoded = "YQ==YmM=ZGVm".getBytes(StandardCharsets.US_ASCII);
        ByteArrayInputStream chunks = new ByteArrayInputStream(encoded) {
            @Override
            public synchronized int read(byte[] bytes, int offset, int length) {
                return super.read(bytes, offset, Math.min(length, 1));
            }
        };
        GrpcWebBase64InputStream input = new GrpcWebBase64InputStream(chunks);

        // 传输分片与 Base64 片段都不改变连续字节语义。
        assertArrayEquals("abcdef".getBytes(StandardCharsets.US_ASCII), input.readAllBytes());
    }

    @Test
    public void shortDestinationReadsKeepPartialDecodedQuartet() throws Exception {
        // 每次只读取两个字节，单个 quartet 的剩余字节要留到下次。
        GrpcWebBase64InputStream input = this.input("YWJjZGVm");
        byte[] destination = new byte[2];
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        int count;
        while ((count = input.read(destination, 0, destination.length)) != -1) {
            decoded.write(destination, 0, count);
        }

        // 不因目标缓冲区小于三个字节而丢数据。
        assertEquals("abcdef", decoded.toString(StandardCharsets.US_ASCII));
    }

    @Test
    public void zeroLengthReadDoesNotConsumeSource() throws Exception {
        // InputStream 的零长度读取必须立即返回零。
        GrpcWebBase64InputStream input = this.input("YQ==");
        byte[] destination = new byte[1];
        int count = input.read(destination, 0, 0);

        // 后续仍然读取完整的第一个字节。
        assertEquals(0, count);
        assertEquals('a', input.read());
        assertEquals(-1, input.read());
    }

    @Test
    public void emptyTextBodyIsEof() throws Exception {
        // 没有 quartet 时输入流自身返回 EOF，是否允许由信封层判断。
        GrpcWebBase64InputStream input = this.input("");

        // 不生成任何虚假的零字节。
        assertEquals(-1, input.read());
    }

    @Test
    public void rejectTruncatedQuartet() {
        // 末尾缺少字符或 padding 不能推测补齐。
        GrpcWebBase64InputStream input = this.input("YQ=");

        // 断流表现为错误，不表现为合法 EOF。
        assertThrows(IOException.class, input::readAllBytes);
    }

    @Test
    public void rejectInvalidAlphabet() {
        // text 模式只接受标准 Base64 字母表。
        GrpcWebBase64InputStream input = this.input("YQ?=");

        // 非法字符不被 MIME 宽松解码器忽略。
        assertThrows(IOException.class, input::readAllBytes);
    }

    @Test
    public void rejectPaddingAtStart() {
        // padding 只能位于 quartet 的末一或末两位。
        GrpcWebBase64InputStream input = this.input("=Q==");

        // 不能生成伪造的已解码字节。
        assertThrows(IOException.class, input::readAllBytes);
    }

    @Test
    public void rejectSinglePaddingAfterThirdPadding() {
        // 第三位 padding 时第四位也必须 padding。
        GrpcWebBase64InputStream input = this.input("YQ=A");

        // 每段都遵守完整 Base64 quartet 语法。
        assertThrows(IOException.class, input::readAllBytes);
    }

    @Test
    public void rejectNonZeroUnusedBitsForOneByte() {
        // 两个 padding 时第二个字母的低四位必须为零。
        GrpcWebBase64InputStream input = this.input("YR==");

        // 不接受同一内容的非规范位模式。
        assertThrows(IOException.class, input::readAllBytes);
    }

    @Test
    public void rejectNonZeroUnusedBitsForTwoBytes() {
        // 一个 padding 时第三个字母的低两位必须为零。
        GrpcWebBase64InputStream input = this.input("YWJ=");

        // 分段仍然使用标准 Base64 编码规则。
        assertThrows(IOException.class, input::readAllBytes);
    }

    @Test
    public void rejectTrailingIncompleteSegmentAfterPadding() {
        // 首段正确结束后还有半段数据，不能把它当作无关尾随内容。
        GrpcWebBase64InputStream input = this.input("YQ==Yg");

        // 整体 HTTP 正文必须完整结束。
        assertThrows(IOException.class, input::readAllBytes);
    }

    @Test
    public void rejectWhitespaceInsideTextBody() {
        // gRPC-Web 不是 MIME Base64，正文不允许插入任意空白。
        GrpcWebBase64InputStream input = this.input("Y Q=");

        // 不静默删除可能损坏帧边界的输入。
        assertThrows(IOException.class, input::readAllBytes);
    }

    private GrpcWebBase64InputStream input(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        return new GrpcWebBase64InputStream(new ByteArrayInputStream(bytes));
    }
}

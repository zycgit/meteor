/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.codec;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.hasor.example.protocol.thrift.generated.InteropService;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.*;
import org.apache.thrift.transport.TMemoryBuffer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

public class ThriftFrameDecoderTest {
    @Test
    public void framedBinaryAcceptsEverySingleByteFragment() throws Exception {
        // 长度头和正文均逐字节到达，模拟最细粒度网络分片。
        byte[] framed = this.frame(this.ping("binary", 11));
        ThriftFrameDecoder decoder = this.decoder(true, 1024);
        List<ThriftMessage> messages = new ArrayList<>();

        // 完整帧到达前没有消息，最后一字节触发唯一消息。
        for (int index = 0; index < framed.length - 1; index++) {
            assertTrue(decoder.feed(new byte[] { framed[index] }).isEmpty());
        }
        messages.addAll(decoder.feed(new byte[] { framed[framed.length - 1] }));
        assertEquals(1, messages.size());
        assertEquals("binary", messages.get(0).encoding());
        assertEquals(11, messages.get(0).sequenceId());
    }

    @Test
    public void framedCompactWaitsForWholePayload() throws Exception {
        // 帧头完整，但 Compact 正文尚缺少最后一个字节。
        byte[] framed = this.frame(this.ping("compact", 12));
        ThriftFrameDecoder decoder = this.decoder(true, 1024);
        byte[] prefix = Arrays.copyOf(framed, framed.length - 1);
        assertTrue(decoder.feed(prefix).isEmpty());

        // 补齐正文后保留 Compact 识别结果。
        List<ThriftMessage> messages = decoder.feed(new byte[] { framed[framed.length - 1] });
        assertEquals(1, messages.size());
        assertEquals("compact", messages.get(0).encoding());
        assertEquals(12, messages.get(0).sequenceId());
    }

    @Test
    public void framedJsonWaitsForWholePayload() throws Exception {
        // JSON 帧在最外层右括号之前被分片。
        byte[] framed = this.frame(this.ping("json", 13));
        ThriftFrameDecoder decoder = this.decoder(true, 1024);
        assertTrue(decoder.feed(Arrays.copyOf(framed, framed.length - 1)).isEmpty());

        // 补齐后一条 JSON 消息被准确交付。
        List<ThriftMessage> messages = decoder.feed(new byte[] { framed[framed.length - 1] });
        assertEquals(1, messages.size());
        assertEquals("json", messages.get(0).encoding());
        assertEquals(13, messages.get(0).sequenceId());
    }

    @Test
    public void framedDecodesCoalescedMessagesInOrder() throws Exception {
        // 同一次接收含有两条完整帧。
        byte[] first = this.frame(this.ping("binary", 14));
        byte[] second = this.frame(this.ping("binary", 15));
        ThriftFrameDecoder decoder = this.decoder(true, 1024);

        // 两条消息按网络先后顺序输出。
        List<ThriftMessage> messages = decoder.feed(this.join(first, second));
        assertEquals(2, messages.size());
        assertEquals(14, messages.get(0).sequenceId());
        assertEquals(15, messages.get(1).sequenceId());
    }

    @Test
    public void framedRetainsPartialNextHeader() throws Exception {
        // 首帧之后只到达下一帧长度头的一半。
        byte[] first = this.frame(this.ping("binary", 16));
        byte[] second = this.frame(this.ping("binary", 17));
        ThriftFrameDecoder decoder = this.decoder(true, 1024);
        List<ThriftMessage> initial = decoder.feed(this.join(first, Arrays.copyOf(second, 2)));
        assertEquals(1, initial.size());
        assertEquals(16, initial.get(0).sequenceId());

        // 后续输入与保留的长度头拼接，正常恢复第二条消息。
        List<ThriftMessage> remaining = decoder.feed(Arrays.copyOfRange(second, 2, second.length));
        assertEquals(1, remaining.size());
        assertEquals(17, remaining.get(0).sequenceId());
    }

    @Test
    public void unframedBinaryAcceptsEverySingleByteFragment() throws Exception {
        // Unframed Binary 依靠消息结构识别边界。
        byte[] data = this.ping("binary", 21);
        ThriftFrameDecoder decoder = this.decoder(false, 1024);

        // 所有不完整前缀均被保留，最终只输出一条完整消息。
        for (int index = 0; index < data.length - 1; index++) {
            assertTrue(decoder.feed(new byte[] { data[index] }).isEmpty());
        }
        List<ThriftMessage> messages = decoder.feed(new byte[] { data[data.length - 1] });
        assertEquals(1, messages.size());
        assertEquals(21, messages.get(0).sequenceId());
    }

    @Test
    public void unframedCompactAcceptsEverySingleByteFragment() throws Exception {
        // Compact 的变长消息头和空正文均逐字节到达。
        byte[] data = this.ping("compact", 22);
        ThriftFrameDecoder decoder = this.decoder(false, 1024);

        // 不会把变长整数前缀错误地当作完整消息。
        for (int index = 0; index < data.length - 1; index++) {
            assertTrue(decoder.feed(new byte[] { data[index] }).isEmpty());
        }
        List<ThriftMessage> messages = decoder.feed(new byte[] { data[data.length - 1] });
        assertEquals(1, messages.size());
        assertEquals("compact", messages.get(0).encoding());
        assertEquals(22, messages.get(0).sequenceId());
    }

    @Test
    public void unframedJsonAcceptsEverySingleByteFragment() throws Exception {
        // JSON 无长度头，最外层消息数组定义完整性。
        byte[] data = this.ping("json", 23);
        ThriftFrameDecoder decoder = this.decoder(false, 1024);

        // 右括号到达之前持续等待，之后仅输出一次。
        for (int index = 0; index < data.length - 1; index++) {
            assertTrue(decoder.feed(new byte[] { data[index] }).isEmpty());
        }
        List<ThriftMessage> messages = decoder.feed(new byte[] { data[data.length - 1] });
        assertEquals(1, messages.size());
        assertEquals("json", messages.get(0).encoding());
        assertEquals(23, messages.get(0).sequenceId());
    }

    @Test
    public void unframedBinaryDecodesCoalescedMessagesInOrder() throws Exception {
        // 两条 Binary 消息直接粘包。
        byte[] data = this.join(this.ping("binary", 24), this.ping("binary", 25));
        ThriftFrameDecoder decoder = this.decoder(false, 1024);

        // 消息结构决定每条边界，并保留原有顺序。
        List<ThriftMessage> messages = decoder.feed(data);
        assertEquals(2, messages.size());
        assertEquals(24, messages.get(0).sequenceId());
        assertEquals(25, messages.get(1).sequenceId());
    }

    @Test
    public void unframedCompactDecodesCoalescedMessagesInOrder() throws Exception {
        // 两条 Compact 消息直接粘包。
        byte[] data = this.join(this.ping("compact", 26), this.ping("compact", 27));
        ThriftFrameDecoder decoder = this.decoder(false, 1024);

        // 第二条的协议头不会被第一条消息消费。
        List<ThriftMessage> messages = decoder.feed(data);
        assertEquals(2, messages.size());
        assertEquals(26, messages.get(0).sequenceId());
        assertEquals(27, messages.get(1).sequenceId());
    }

    @Test
    public void unframedJsonDecodesCoalescedMessagesInOrder() throws Exception {
        // 两条 JSON 消息没有额外分隔符。
        byte[] data = this.join(this.ping("json", 28), this.ping("json", 29));
        ThriftFrameDecoder decoder = this.decoder(false, 1024);

        // 连续数组仍被拆成两条独立消息。
        List<ThriftMessage> messages = decoder.feed(data);
        assertEquals(2, messages.size());
        assertEquals(28, messages.get(0).sequenceId());
        assertEquals(29, messages.get(1).sequenceId());
    }

    @Test
    public void unframedRetainsPartialNextMessage() throws Exception {
        // 完整首条消息之后只有第二条消息的一部分。
        byte[] first = this.ping("binary", 30);
        byte[] second = this.ping("binary", 31);
        int split = second.length / 2;
        ThriftFrameDecoder decoder = this.decoder(false, 1024);
        List<ThriftMessage> initial = decoder.feed(this.join(first, Arrays.copyOf(second, split)));
        assertEquals(1, initial.size());
        assertEquals(30, initial.get(0).sequenceId());

        // 保留的半条消息与下一次输入合并。
        List<ThriftMessage> remaining = decoder.feed(Arrays.copyOfRange(second, split, second.length));
        assertEquals(1, remaining.size());
        assertEquals(31, remaining.get(0).sequenceId());
    }

    @Test
    public void framedRejectsZeroLength() {
        // 零长度帧没有合法的 Thrift 消息。
        ThriftFrameDecoder decoder = this.decoder(true, 1024);

        // 完整长度头到达时立即报错。
        assertThrows(TException.class, () -> decoder.feed(new byte[4]));
    }

    @Test
    public void framedRejectsNegativeLength() {
        // 有符号帧长度为负数。
        byte[] header = ByteBuffer.allocate(4).putInt(-1).array();
        ThriftFrameDecoder decoder = this.decoder(true, 1024);

        // 拒绝分配正文缓冲区。
        assertThrows(TException.class, () -> decoder.feed(header));
    }

    @Test
    public void framedRejectsLengthAboveConfiguredLimit() {
        // 对端只发送一个超过上限的长度声明。
        byte[] header = ByteBuffer.allocate(4).putInt(1025).array();
        ThriftFrameDecoder decoder = this.decoder(true, 1024);

        // 不需要等待超大正文就能拒绝该帧。
        assertThrows(TException.class, () -> decoder.feed(header));
    }

    @Test
    public void framedRejectsTrailingBytesInsideFrame() throws Exception {
        // 一个声明的帧内包含完整消息和多余垃圾。
        byte[] message = this.ping("binary", 32);
        byte[] payload = Arrays.copyOf(message, message.length + 1);
        payload[message.length] = 0x7f;
        ThriftFrameDecoder decoder = this.decoder(true, 1024);

        // 帧边界内仍要求恰好一条合法消息。
        assertThrows(TException.class, () -> decoder.feed(this.frame(payload)));
    }

    @Test
    public void framedAcceptsMessageAtExactConfiguredLimit() throws Exception {
        // 最大大小约束针对正文，不包含四字节帧头。
        byte[] message = this.ping("binary", 33);
        ThriftFrameDecoder decoder = this.decoder(true, message.length);

        // 等于边界的完整消息可以交付。
        List<ThriftMessage> messages = decoder.feed(this.frame(message));
        assertEquals(1, messages.size());
        assertEquals(33, messages.get(0).sequenceId());
    }

    @Test
    public void unframedAcceptsMessageAtExactConfiguredLimit() throws Exception {
        // 无帧模式的累计缓冲区恰好容纳完整消息。
        byte[] message = this.ping("binary", 34);
        ThriftFrameDecoder decoder = this.decoder(false, message.length);

        // 完整消息先被消费，不能被累计大小检查误判为超限。
        List<ThriftMessage> messages = decoder.feed(message);
        assertEquals(1, messages.size());
        assertEquals(34, messages.get(0).sequenceId());
    }

    @Test
    public void unframedRejectsIncompleteMessageAtConfiguredLimit() throws Exception {
        // 合法消息的最后一个字节落在累计上限之外。
        byte[] message = this.ping("binary", 35);
        ThriftFrameDecoder decoder = this.decoder(false, message.length - 1);

        // 达到上限仍未完成时立即失败。
        assertThrows(TException.class, () -> decoder.feed(message));
    }

    @Test
    public void framedResetDiscardsPartialFrame() throws Exception {
        // 当前连接只收到一部分长度头，随后重置连接状态。
        byte[] abandoned = this.frame(this.ping("binary", 36));
        ThriftFrameDecoder decoder = this.decoder(true, 1024);
        assertTrue(decoder.feed(Arrays.copyOf(abandoned, 2)).isEmpty());
        decoder.reset();

        // 新连接消息不受旧长度头污染。
        List<ThriftMessage> messages = decoder.feed(this.frame(this.ping("binary", 37)));
        assertEquals(1, messages.size());
        assertEquals(37, messages.get(0).sequenceId());
    }

    @Test
    public void unframedResetDiscardsPartialMessage() throws Exception {
        // 无帧模式保留了旧连接的部分消息。
        byte[] abandoned = this.ping("compact", 38);
        ThriftFrameDecoder decoder = this.decoder(false, 1024);
        assertTrue(decoder.feed(Arrays.copyOf(abandoned, abandoned.length - 1)).isEmpty());
        decoder.reset();

        // 重置后可以使用新消息恢复解码。
        List<ThriftMessage> messages = decoder.feed(this.ping("compact", 39));
        assertEquals(1, messages.size());
        assertEquals(39, messages.get(0).sequenceId());
    }

    @Test
    @Timeout(value = 5000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void unframedBinaryDecodesLargeContainerFromSingleByteFragments() throws Exception {
        // 约 64KB 的容器以单字节到达，已读元素不应在每次接收时重新扫描。
        ThriftWireCodec wire = new ThriftWireCodec(128 * 1024, 40000, 32);
        InteropService.echoList_args arguments = new InteropService.echoList_args(Collections.nCopies(13000, "a"));
        byte[] data = wire.encode("binary", "echoList", TMessageType.CALL, 51, arguments);
        assertTrue(data.length >= 60 * 1024 && data.length <= 70 * 1024);
        ThriftFrameDecoder decoder = new ThriftFrameDecoder(wire, false, 128 * 1024);

        // 有界完成整个消息，并验证只交付一次且字节完整。
        List<ThriftMessage> messages = new ArrayList<>();
        for (byte current : data) {
            messages.addAll(decoder.feed(new byte[] { current }));
        }
        assertEquals(1, messages.size());
        assertEquals(51, messages.get(0).sequenceId());
        assertArrayEquals(data, messages.get(0).data());
    }

    @Test
    @Timeout(value = 5000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void unframedCompactDecodesLargeContainerFromSingleByteFragments() throws Exception {
        // Compact 容器约 64KB，覆盖大量短字符串元素的分片扫描。
        ThriftWireCodec wire = new ThriftWireCodec(128 * 1024, 40000, 32);
        InteropService.echoList_args arguments = new InteropService.echoList_args(Collections.nCopies(32000, "a"));
        byte[] data = wire.encode("compact", "echoList", TMessageType.CALL, 52, arguments);
        assertTrue(data.length >= 60 * 1024 && data.length <= 70 * 1024);
        ThriftFrameDecoder decoder = new ThriftFrameDecoder(wire, false, 128 * 1024);

        // 每次只提供一个字节，最终仍只交付原始完整消息。
        List<ThriftMessage> messages = new ArrayList<>();
        for (byte current : data) {
            messages.addAll(decoder.feed(new byte[] { current }));
        }
        assertEquals(1, messages.size());
        assertEquals(52, messages.get(0).sequenceId());
        assertArrayEquals(data, messages.get(0).data());
    }

    @Test
    @Timeout(value = 5000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void unframedJsonDecodesLargeContainerFromSingleByteFragments() throws Exception {
        // JSON 容器约 64KB，数组边界检测也要保留已经扫描的进度。
        ThriftWireCodec wire = new ThriftWireCodec(128 * 1024, 40000, 32);
        InteropService.echoList_args arguments = new InteropService.echoList_args(Collections.nCopies(16000, "a"));
        byte[] data = wire.encode("json", "echoList", TMessageType.CALL, 53, arguments);
        assertTrue(data.length >= 60 * 1024 && data.length <= 70 * 1024);
        ThriftFrameDecoder decoder = new ThriftFrameDecoder(wire, false, 128 * 1024);

        // 最外层消息闭合后只交付一次，避免把有界测试变成单纯测速。
        List<ThriftMessage> messages = new ArrayList<>();
        for (byte current : data) {
            messages.addAll(decoder.feed(new byte[] { current }));
        }
        assertEquals(1, messages.size());
        assertEquals(53, messages.get(0).sequenceId());
        assertArrayEquals(data, messages.get(0).data());
    }

    @Test
    @Timeout(value = 5000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void unframedBinaryDecodesLargeStringFromSingleByteFragments() throws Exception {
        // 长字符串正文逐字节到达，长度头不能使每次分片都申请完整字符串数组。
        ThriftWireCodec wire = new ThriftWireCodec(128 * 1024, 128, 32);
        InteropService.echo_args arguments = new InteropService.echo_args("a".repeat(64 * 1024));
        byte[] data = wire.encode("binary", "echo", TMessageType.CALL, 59, arguments);
        ThriftFrameDecoder decoder = new ThriftFrameDecoder(wire, false, 128 * 1024);

        // 完整字符串最终按原字节交付，超时仅用于防止处理过程挂起。
        List<ThriftMessage> messages = new ArrayList<>();
        for (byte current : data) {
            messages.addAll(decoder.feed(new byte[] { current }));
        }
        assertEquals(1, messages.size());
        assertEquals(59, messages.get(0).sequenceId());
        assertArrayEquals(data, messages.get(0).data());
    }

    @Test
    @Timeout(value = 5000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void unframedCompactDecodesLargeStringFromSingleByteFragments() throws Exception {
        // Compact 长度 varint 之后仍有大量分片，未齐正文不能触发反复的大数组申请。
        ThriftWireCodec wire = new ThriftWireCodec(128 * 1024, 128, 32);
        InteropService.echo_args arguments = new InteropService.echo_args("a".repeat(64 * 1024));
        byte[] data = wire.encode("compact", "echo", TMessageType.CALL, 60, arguments);
        ThriftFrameDecoder decoder = new ThriftFrameDecoder(wire, false, 128 * 1024);

        // 每次只提供一个字节，仍能完整交付唯一消息。
        List<ThriftMessage> messages = new ArrayList<>();
        for (byte current : data) {
            messages.addAll(decoder.feed(new byte[] { current }));
        }
        assertEquals(1, messages.size());
        assertEquals(60, messages.get(0).sequenceId());
        assertArrayEquals(data, messages.get(0).data());
    }

    @Test
    public void unframedCompactResumesExplicitFieldIdVarint() throws Exception {
        // 字段 ID 300 需要显式的多字节 varint，分片停在其两个字节之间。
        TMemoryBuffer output = new TMemoryBuffer(64);
        TProtocol protocol = new TCompactProtocol(output);
        protocol.writeMessageBegin(new TMessage("explicitField", TMessageType.CALL, 54));
        protocol.writeStructBegin(new TStruct("args"));
        int fieldStart = output.length();
        protocol.writeFieldBegin(new TField("value", TType.I32, (short) 300));
        protocol.writeI32(1048576);
        protocol.writeFieldEnd();
        protocol.writeFieldStop();
        protocol.writeStructEnd();
        protocol.writeMessageEnd();
        byte[] data = Arrays.copyOf(output.getArray(), output.length());
        int split = fieldStart + 2;
        ThriftFrameDecoder decoder = this.decoder(false, 1024);
        assertTrue(decoder.feed(Arrays.copyOf(data, split)).isEmpty());

        // 接续输入从 field-id varint 的剩余部分恢复，不重复消费字段头。
        List<ThriftMessage> messages = decoder.feed(Arrays.copyOfRange(data, split, data.length));
        assertEquals(1, messages.size());
        assertEquals(54, messages.get(0).sequenceId());
        assertArrayEquals(data, messages.get(0).data());
    }

    @Test
    public void unframedCompactKeepsNestedContainerStateAcrossMessages() throws Exception {
        // 两条消息都含 list/map/list 嵌套，逐字节到达并跨越消息边界。
        byte[] first = this.nestedCompactMessage(55);
        byte[] second = this.nestedCompactMessage(56);
        byte[] data = this.join(first, second);
        ThriftFrameDecoder decoder = this.decoder(false, 1024);

        // 每条消息的容器状态与字段 ID 状态均在完成后正确清理。
        List<ThriftMessage> messages = new ArrayList<>();
        for (byte current : data) {
            messages.addAll(decoder.feed(new byte[] { current }));
        }
        assertEquals(2, messages.size());
        assertEquals(55, messages.get(0).sequenceId());
        assertArrayEquals(first, messages.get(0).data());
        assertEquals(56, messages.get(1).sequenceId());
        assertArrayEquals(second, messages.get(1).data());
    }

    @Test
    public void unframedCompactResumesPartialMessageAfterCompleteMessage() throws Exception {
        // 一次接收中含完整首条消息和第二条嵌套容器消息的一部分。
        byte[] first = this.nestedCompactMessage(57);
        byte[] second = this.nestedCompactMessage(58);
        int split = second.length / 2;
        ThriftFrameDecoder decoder = this.decoder(false, 1024);
        List<ThriftMessage> initial = decoder.feed(this.join(first, Arrays.copyOf(second, split)));
        assertEquals(1, initial.size());
        assertEquals(57, initial.get(0).sequenceId());

        // 第二条消息接续完成时，恢复的是它自身的容器栈与剩余元素数。
        List<ThriftMessage> remaining = decoder.feed(Arrays.copyOfRange(second, split, second.length));
        assertEquals(1, remaining.size());
        assertEquals(58, remaining.get(0).sequenceId());
        assertArrayEquals(second, remaining.get(0).data());
    }

    private ThriftFrameDecoder decoder(boolean framed, int maxMessageSize) {
        return new ThriftFrameDecoder(new ThriftWireCodec(1024 * 1024, 128, 32), framed, maxMessageSize);
    }

    private byte[] ping(String encoding, int sequenceId) throws TException {
        ThriftWireCodec codec = new ThriftWireCodec(1024 * 1024, 128, 32);
        return codec.encode(encoding, "ping", TMessageType.CALL, sequenceId, new InteropService.ping_args());
    }

    private byte[] frame(byte[] message) {
        return ByteBuffer.allocate(4 + message.length).putInt(message.length).put(message).array();
    }

    private byte[] nestedCompactMessage(int sequenceId) throws TException {
        // 官方协议负责写出嵌套容器；这里只准备测试输入。
        TMemoryBuffer output = new TMemoryBuffer(128);
        TProtocol protocol = new TCompactProtocol(output);
        protocol.writeMessageBegin(new TMessage("nested", TMessageType.CALL, sequenceId));
        protocol.writeStructBegin(new TStruct("args"));
        protocol.writeFieldBegin(new TField("values", TType.LIST, (short) 20));
        protocol.writeListBegin(new TList(TType.MAP, 2));
        for (short index = 0; index < 2; index++) {
            protocol.writeMapBegin(new TMap(TType.I16, TType.LIST, 1));
            protocol.writeI16(index);
            protocol.writeListBegin(new TList(TType.I64, 2));
            protocol.writeI64(1048576L + index);
            protocol.writeI64(-1048576L - index);
            protocol.writeListEnd();
            protocol.writeMapEnd();
        }
        protocol.writeListEnd();
        protocol.writeFieldEnd();
        protocol.writeFieldStop();
        protocol.writeStructEnd();
        protocol.writeMessageEnd();
        return Arrays.copyOf(output.getArray(), output.length());
    }

    private byte[] join(byte[] first, byte[] second) {
        byte[] joined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }
}

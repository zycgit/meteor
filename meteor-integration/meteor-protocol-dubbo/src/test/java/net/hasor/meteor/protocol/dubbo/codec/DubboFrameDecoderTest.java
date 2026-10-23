/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.dubbo.codec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import static org.junit.Assert.*;

/** 固定头、分片、粘包与长度上限。 */
public class DubboFrameDecoderTest {
    @Test
    public void completeRequestPreservesHeaderAndBody() throws Exception {
        // 一个完整帧直接交付原始 ID、标志与正文。
        DubboFrameDecoder decoder = new DubboFrameDecoder(1024);
        DubboFrame frame = decoder.readFrame(frame((byte) 0xc2, (byte) 0, 123, new byte[] { 1, 2 }));
        assertTrue(frame.request());
        assertTrue(frame.twoWay());
        assertFalse(frame.event());
        assertEquals(2, frame.serializationId());
        assertEquals(123, frame.requestId());
        assertArrayEquals(new byte[] { 1, 2 }, frame.body());
    }

    @Test
    public void incompleteHeaderWaitsForNextChunk() throws Exception {
        // 前半个帧头被解码器持有，后续数据只提交剩余部分。
        ByteBuffer wire = frame((byte) 0xc2, (byte) 0, 12, new byte[] { 1 });
        DubboFrameDecoder decoder = new DubboFrameDecoder(1024);
        ByteBuffer first = wire.slice();
        first.limit(7);
        assertNull(decoder.readFrame(first));

        // 补齐后恢复同一帧。
        wire.position(7);
        assertEquals(12, decoder.readFrame(wire).requestId());
    }

    @Test
    public void incompleteBodyWaitsForNextChunk() throws Exception {
        // 完整帧头与部分正文不会提前交付调用。
        ByteBuffer wire = frame((byte) 0xc2, (byte) 0, 12, new byte[] { 1, 2, 3 });
        DubboFrameDecoder decoder = new DubboFrameDecoder(1024);
        ByteBuffer first = wire.slice();
        first.limit(17);
        assertNull(decoder.readFrame(first));

        // 新分片完成正文而不重复复制已收到的头。
        wire.position(17);
        assertArrayEquals(new byte[] { 1, 2, 3 }, decoder.readFrame(wire).body());
    }

    @Test
    public void byteByByteInputProducesOneFrame() throws Exception {
        // 任意网络分片粒度都保持帧边界。
        ByteBuffer wire = frame((byte) 0xc2, (byte) 0, -1, new byte[] { 1, 2 });
        DubboFrameDecoder decoder = new DubboFrameDecoder(1024);
        while (wire.remaining() > 1) {
            assertNull(decoder.readFrame(ByteBuffer.wrap(new byte[] { wire.get() })));
        }

        // 请求 ID 的符号位属于线协议，不能因负数被拒绝。
        DubboFrame completed = decoder.readFrame(ByteBuffer.wrap(new byte[] { wire.get() }));
        assertEquals(-1, completed.requestId());
    }

    @Test
    public void coalescedFramesLeaveFollowingBytesForNextRead() throws Exception {
        // 两次调用消费同一个缓冲区中的两个帧。
        ByteBuffer first = frame((byte) 2, (byte) 20, 1, new byte[] { 1 });
        ByteBuffer second = frame((byte) 2, (byte) 20, 2, new byte[] { 2 });
        ByteBuffer combined = ByteBuffer.allocate(first.remaining() + second.remaining());
        combined.put(first).put(second).flip();
        DubboFrameDecoder decoder = new DubboFrameDecoder(1024);
        assertEquals(1, decoder.readFrame(combined).requestId());
        assertEquals(2, decoder.readFrame(combined).requestId());
        assertFalse(combined.hasRemaining());
    }

    @Test
    public void emptyBodyStillHasACompleteFrame() throws Exception {
        // 帧层保留零长度正文，是否合法由具体消息编码层决定。
        DubboFrameDecoder decoder = new DubboFrameDecoder(1024);
        DubboFrame decoded = decoder.readFrame(frame((byte) 2, (byte) 20, 1, new byte[0]));
        assertEquals(0, decoded.body().length);
        assertEquals(20, decoded.status());
    }

    @Test
    public void bodyAtConfiguredMaximumIsAccepted() throws Exception {
        // 最大值只约束正文，不将固定头重复计入。
        DubboFrameDecoder decoder = new DubboFrameDecoder(3);
        assertEquals(3, decoder.readFrame(frame((byte) 2, (byte) 20, 1, new byte[3])).body().length);
    }

    @Test
    public void declaredBodyAboveMaximumFailsBeforeBodyArrives() {
        // 仅提供帧头就能拒绝超限声明，避免按远端长度分配大数组。
        ByteBuffer wire = frame((byte) 2, (byte) 20, 1, new byte[0]);
        wire.putInt(12, 1025);
        DubboFrameDecoder decoder = new DubboFrameDecoder(1024);
        assertThrows(IOException.class, () -> decoder.readFrame(wire));
    }

    @Test
    public void negativeBodyLengthIsRejected() {
        // 有符号长度为负数属于畸形报文。
        ByteBuffer wire = frame((byte) 2, (byte) 20, 1, new byte[0]);
        wire.putInt(12, -1);
        DubboFrameDecoder decoder = new DubboFrameDecoder(1024);
        assertThrows(IOException.class, () -> decoder.readFrame(wire));
    }

    @Test
    public void badMagicIsRejected() {
        // HTTP 或其它数据不能进入 Dubbo 正文解码。
        ByteBuffer wire = frame((byte) 2, (byte) 20, 1, new byte[0]);
        wire.put(0, (byte) 'H');
        DubboFrameDecoder decoder = new DubboFrameDecoder(1024);
        assertThrows(IOException.class, () -> decoder.readFrame(wire));
    }

    @Test
    public void resetDiscardsAnUnfinishedFrame() throws Exception {
        // 连接关闭时清理分片，不把旧连接残片带入新连接。
        DubboFrameDecoder decoder = new DubboFrameDecoder(1024);
        assertNull(decoder.readFrame(ByteBuffer.wrap(new byte[] { (byte) 0xda })));
        decoder.reset();
        assertEquals(3, decoder.readFrame(frame((byte) 2, (byte) 20, 3, new byte[] { 1 })).requestId());
    }

    @Test
    public void inputByteOrderDoesNotAffectNetworkByteOrder() throws Exception {
        // 调用方缓冲区属性不改变协议固定大端格式。
        ByteBuffer wire = frame((byte) 2, (byte) 20, 0x0102030405060708L, new byte[] { 1 });
        wire.order(ByteOrder.LITTLE_ENDIAN);
        DubboFrameDecoder decoder = new DubboFrameDecoder(1024);
        assertEquals(0x0102030405060708L, decoder.readFrame(wire).requestId());
    }

    @Test
    public void directBufferInputIsSupported() throws Exception {
        // 不要求网络输入暴露底层 byte 数组。
        ByteBuffer wire = frame((byte) 0xe2, (byte) 0, 5, new byte[] { 'N' });
        ByteBuffer direct = ByteBuffer.allocateDirect(wire.remaining());
        direct.put(wire).flip();
        DubboFrame result = new DubboFrameDecoder(1024).readFrame(direct);
        assertTrue(result.event());
        assertTrue(result.twoWay());
        assertEquals((byte) 0xe2, result.flags());
    }

    @Test
    public void nonPositiveMaximumIsRejected() {
        // 长度上限属于创建解码器时即可确定的配置错误。
        assertThrows(IllegalArgumentException.class, () -> new DubboFrameDecoder(0));
    }

    private static ByteBuffer frame(byte flags, byte status, long requestId, byte[] body) {
        ByteBuffer buffer = ByteBuffer.allocate(16 + body.length);
        buffer.putShort((short) 0xdabb).put(flags).put(status).putLong(requestId).putInt(body.length).put(body);
        return buffer.flip();
    }
}
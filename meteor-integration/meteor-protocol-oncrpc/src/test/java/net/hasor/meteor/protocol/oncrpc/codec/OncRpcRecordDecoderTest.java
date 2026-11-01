/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.codec;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class OncRpcRecordDecoderTest {
    @Test
    public void emptyInputProducesNoRecord() {
        // 空网络片段不产生记录，也不要求存在完整 marker。
        OncRpcRecordDecoder decoder = new OncRpcRecordDecoder(32, 4);

        // 不向上层交付虚假的空记录。
        assertTrue(decoder.feed(new byte[0]).isEmpty());
    }

    @Test
    public void completeSingleFragmentProducesRecord() {
        // 独立准备一个带结束位的完整 record。
        byte[] body = { 1, 2, 3, 4 };
        OncRpcRecordDecoder decoder = new OncRpcRecordDecoder(32, 4);

        // marker 不出现在交付正文中。
        List<byte[]> records = decoder.feed(this.fragment(true, body));
        assertEquals(1, records.size());
        assertArrayEquals(body, records.get(0));
    }

    @Test
    public void markerCanSplitAtEveryByteBoundary() {
        // 同一 marker 的各个切点均属于头部拆包场景。
        byte[] body = { 7, 8, 9 };
        byte[] encoded = this.fragment(true, body);

        // 每个不完整头部都等待后续输入，补齐后只输出一次。
        for (int split = 1; split < 4; split++) {
            OncRpcRecordDecoder decoder = new OncRpcRecordDecoder(32, 4);
            assertTrue(decoder.feed(Arrays.copyOf(encoded, split)).isEmpty());
            List<byte[]> records = decoder.feed(Arrays.copyOfRange(encoded, split, encoded.length));
            assertEquals(1, records.size());
            assertArrayEquals(body, records.get(0));
        }
    }

    @Test
    public void bodyCanSplitAtEveryByteBoundary() {
        // marker 完整到达，正文可以在任意位置拆包。
        byte[] body = { 1, 2, 3, 4, 5, 6 };
        byte[] encoded = this.fragment(true, body);

        // 任意正文前缀都不提前发布记录。
        for (int split = 4; split < encoded.length; split++) {
            OncRpcRecordDecoder decoder = new OncRpcRecordDecoder(32, 4);
            assertTrue(decoder.feed(Arrays.copyOf(encoded, split)).isEmpty());
            List<byte[]> records = decoder.feed(Arrays.copyOfRange(encoded, split, encoded.length));
            assertEquals(1, records.size());
            assertArrayEquals(body, records.get(0));
        }
    }

    @Test
    public void bytewiseInputPreservesFragmentedRecord() {
        // 一条记录由多片组成，头部和正文均逐字节到达。
        byte[] first = this.fragment(false, new byte[] { 1, 2 });
        byte[] last = this.fragment(true, new byte[] { 3, 4 });
        byte[] encoded = this.join(first, last);
        OncRpcRecordDecoder decoder = new OncRpcRecordDecoder(32, 4);

        // 最后一个字节之前持续等待，完整后仅交付合并正文。
        for (int index = 0; index < encoded.length - 1; index++) {
            assertTrue(decoder.feed(new byte[] { encoded[index] }).isEmpty());
        }
        byte[] finalByte = { encoded[encoded.length - 1] };
        List<byte[]> records = decoder.feed(finalByte);
        assertEquals(1, records.size());
        assertArrayEquals(new byte[] { 1, 2, 3, 4 }, records.get(0));
    }

    @Test
    public void coalescedRecordsPreserveInputOrder() {
        // 多条独立 record 在一次网络回调中到达。
        byte[] first = this.fragment(true, new byte[] { 10 });
        byte[] second = this.fragment(true, new byte[] { 20, 21 });
        byte[] third = this.fragment(true, new byte[] { 30 });
        OncRpcRecordDecoder decoder = new OncRpcRecordDecoder(32, 4);

        // 记录按先后顺序输出，不将下一条 marker 混入正文。
        List<byte[]> records = decoder.feed(this.join(first, second, third));
        assertEquals(3, records.size());
        assertArrayEquals(new byte[] { 10 }, records.get(0));
        assertArrayEquals(new byte[] { 20, 21 }, records.get(1));
        assertArrayEquals(new byte[] { 30 }, records.get(2));
    }

    @Test
    public void coalescedInputRetainsNextPartialMarker() {
        // 第一条完整记录之后仅收到第二条 marker 的一半。
        byte[] first = this.fragment(true, new byte[] { 1 });
        byte[] second = this.fragment(true, new byte[] { 2, 3 });
        byte[] partialMarker = Arrays.copyOf(second, 2);
        OncRpcRecordDecoder decoder = new OncRpcRecordDecoder(32, 4);
        List<byte[]> initial = decoder.feed(this.join(first, partialMarker));
        assertEquals(1, initial.size());
        assertArrayEquals(new byte[] { 1 }, initial.get(0));

        // 保留的头部与下一次输入拼接，正常交付第二条。
        List<byte[]> remaining = decoder.feed(Arrays.copyOfRange(second, 2, second.length));
        assertEquals(1, remaining.size());
        assertArrayEquals(new byte[] { 2, 3 }, remaining.get(0));
    }

    @Test
    public void coalescedInputRetainsNextPartialBody() {
        // 第一条完整记录之后，第二条只缺正文最后一字节。
        byte[] first = this.fragment(true, new byte[] { 1 });
        byte[] second = this.fragment(true, new byte[] { 2, 3 });
        byte[] partialBody = Arrays.copyOf(second, second.length - 1);
        OncRpcRecordDecoder decoder = new OncRpcRecordDecoder(32, 4);
        List<byte[]> initial = decoder.feed(this.join(first, partialBody));
        assertEquals(1, initial.size());
        assertArrayEquals(new byte[] { 1 }, initial.get(0));

        // 补齐第二条正文后，不重复交付第一条记录。
        List<byte[]> remaining = decoder.feed(new byte[] { 3 });
        assertEquals(1, remaining.size());
        assertArrayEquals(new byte[] { 2, 3 }, remaining.get(0));
    }

    @Test
    public void nonfinalFragmentsWaitForFinalFragment() {
        // 两个不带结束位的片段已经全部到达。
        byte[] first = this.fragment(false, new byte[] { 1, 2 });
        byte[] second = this.fragment(false, new byte[] { 3 });
        OncRpcRecordDecoder decoder = new OncRpcRecordDecoder(32, 4);
        assertTrue(decoder.feed(this.join(first, second)).isEmpty());

        // 只有最后一片结束后，才交付完整合并正文。
        List<byte[]> records = decoder.feed(this.fragment(true, new byte[] { 4, 5 }));
        assertEquals(1, records.size());
        assertArrayEquals(new byte[] { 1, 2, 3, 4, 5 }, records.get(0));
    }

    @Test
    public void emptyNonfinalFragmentDoesNotTerminateRecord() {
        // 空中间片段仍属于当前记录，不是记录结束标志。
        byte[] first = this.fragment(false, new byte[] { 1 });
        byte[] empty = this.fragment(false, new byte[0]);
        OncRpcRecordDecoder decoder = new OncRpcRecordDecoder(32, 4);
        assertTrue(decoder.feed(this.join(first, empty)).isEmpty());

        // 后续最后一片与前面的非空正文合并。
        List<byte[]> records = decoder.feed(this.fragment(true, new byte[] { 2 }));
        assertEquals(1, records.size());
        assertArrayEquals(new byte[] { 1, 2 }, records.get(0));
    }

    @Test
    public void emptyFinalFragmentTerminatesAccumulatedRecord() {
        // 已收集的非空片段以零长度最后一片结束。
        OncRpcRecordDecoder decoder = new OncRpcRecordDecoder(32, 4);
        assertTrue(decoder.feed(this.fragment(false, new byte[] { 1, 2 })).isEmpty());

        // 空结束片段不增加正文，但必须发布累计记录。
        List<byte[]> records = decoder.feed(this.fragment(true, new byte[0]));
        assertEquals(1, records.size());
        assertArrayEquals(new byte[] { 1, 2 }, records.get(0));
    }

    @Test
    public void zeroLengthRecordIsValidRecordMarking() {
        // RM 层允许空记录，RPC 消息合法性由上层判断。
        OncRpcRecordDecoder decoder = new OncRpcRecordDecoder(32, 4);

        // 零长度最后一片形成一条独立空记录。
        List<byte[]> records = decoder.feed(this.fragment(true, new byte[0]));
        assertEquals(1, records.size());
        assertArrayEquals(new byte[0], records.get(0));
    }

    @Test
    public void consecutiveEmptyRecordsRemainDistinct() {
        // 两个相邻的零长度 record 仍有独立 marker。
        byte[] empty = this.fragment(true, new byte[0]);
        OncRpcRecordDecoder decoder = new OncRpcRecordDecoder(32, 4);

        // 不合并相邻空记录，也不因正文为空而遗漏记录。
        List<byte[]> records = decoder.feed(this.join(empty, empty));
        assertEquals(2, records.size());
        assertArrayEquals(new byte[0], records.get(0));
        assertArrayEquals(new byte[0], records.get(1));
    }

    @Test
    public void recordExactlyAtMessageLimitIsAccepted() {
        // 正文长度恰好等于配置上限。
        byte[] body = { 1, 2, 3, 4 };
        OncRpcRecordDecoder decoder = new OncRpcRecordDecoder(body.length, 2);

        // 边界值合法，上限检查不误拒绝等值正文。
        List<byte[]> records = decoder.feed(this.fragment(true, body));
        assertEquals(1, records.size());
        assertArrayEquals(body, records.get(0));
    }

    @Test
    public void fragmentedRecordExactlyAtMessageLimitIsAccepted() {
        // 单片长度均低于上限，累计长度正好等于上限。
        byte[] first = this.fragment(false, new byte[] { 1, 2 });
        byte[] last = this.fragment(true, new byte[] { 3, 4 });
        OncRpcRecordDecoder decoder = new OncRpcRecordDecoder(4, 2);

        // 按整个 record 的累计长度接受边界值。
        List<byte[]> records = decoder.feed(this.join(first, last));
        assertEquals(1, records.size());
        assertArrayEquals(new byte[] { 1, 2, 3, 4 }, records.get(0));
    }

    @Test
    public void oversizedFragmentIsRejectedBeforeBodyArrives() {
        // 对端仅发出超限长度声明，不发送正文。
        byte[] marker = this.marker(true, 5);
        OncRpcRecordDecoder decoder = new OncRpcRecordDecoder(4, 2);

        // 第四个 marker 字节到达时立即拒绝。
        assertTrue(decoder.feed(Arrays.copyOf(marker, 3)).isEmpty());
        byte[] finalHeaderByte = { marker[3] };
        assertThrows(IllegalArgumentException.class, () -> decoder.feed(finalHeaderByte));
    }

    @Test
    public void maximumUnsignedFragmentLengthIsRejectedWithoutAllocation() {
        // 低 31 位全为一，表示极大正文声明。
        byte[] marker = this.marker(false, Integer.MAX_VALUE);
        OncRpcRecordDecoder decoder = new OncRpcRecordDecoder(32, 2);

        // 不等待正文，也不按对端声明分配极大缓冲区。
        assertThrows(IllegalArgumentException.class, () -> decoder.feed(marker));
    }

    @Test
    public void cumulativeMessageLimitIsCheckedAtNextMarker() {
        // 前一片已经占用大部分 record 容量。
        OncRpcRecordDecoder decoder = new OncRpcRecordDecoder(4, 4);
        assertTrue(decoder.feed(this.fragment(false, new byte[] { 1, 2, 3 })).isEmpty());

        // 下一片尚未发送正文，其长度声明已经超出剩余容量。
        byte[] nextMarker = this.marker(true, 2);
        assertThrows(IllegalArgumentException.class, () -> decoder.feed(nextMarker));
    }

    @Test
    public void recordExactlyAtFragmentLimitIsAccepted() {
        // 当前记录的片段数量恰好达到上限。
        byte[] first = this.fragment(false, new byte[] { 1 });
        byte[] last = this.fragment(true, new byte[] { 2 });
        OncRpcRecordDecoder decoder = new OncRpcRecordDecoder(32, 2);

        // 最后一片计入上限后仍能正常交付。
        List<byte[]> records = decoder.feed(this.join(first, last));
        assertEquals(1, records.size());
        assertArrayEquals(new byte[] { 1, 2 }, records.get(0));
    }

    @Test
    public void excessiveFragmentCountIsRejectedBeforeBodyArrives() {
        // 已收集的非结束片段用满片段配额。
        OncRpcRecordDecoder decoder = new OncRpcRecordDecoder(32, 1);
        assertTrue(decoder.feed(this.fragment(false, new byte[] { 1 })).isEmpty());

        // 下一片 marker 到达后立即拒绝，无需等待正文。
        byte[] nextMarker = this.marker(true, 1);
        assertThrows(IllegalArgumentException.class, () -> decoder.feed(nextMarker));
    }

    @Test
    public void emptyFragmentsCountTowardsFragmentLimit() {
        // 空非结束片段不占正文容量，但必须消耗片段配额。
        OncRpcRecordDecoder decoder = new OncRpcRecordDecoder(32, 2);
        byte[] empty = this.fragment(false, new byte[0]);
        assertTrue(decoder.feed(this.join(empty, empty)).isEmpty());

        // 无法用无限空片段绕过计数限制。
        assertThrows(IllegalArgumentException.class, () -> decoder.feed(empty));
    }

    @Test
    public void completedRecordResetsFragmentBudget() {
        // 每条记录都独立使用完整片段配额。
        byte[] first = this.fragment(true, new byte[] { 1 });
        byte[] second = this.fragment(true, new byte[] { 2 });
        OncRpcRecordDecoder decoder = new OncRpcRecordDecoder(1, 1);

        // 上一条的片段计数和正文长度不会污染下一条。
        List<byte[]> records = decoder.feed(this.join(first, second));
        assertEquals(2, records.size());
        assertArrayEquals(new byte[] { 1 }, records.get(0));
        assertArrayEquals(new byte[] { 2 }, records.get(1));
    }

    @Test
    public void resetDiscardsPartialMarker() {
        // 原记录仅收到部分 marker。
        OncRpcRecordDecoder decoder = new OncRpcRecordDecoder(32, 2);
        byte[] marker = this.marker(true, 6);
        assertTrue(decoder.feed(Arrays.copyOf(marker, 2)).isEmpty());

        // 显式复位后，从全新的 marker 开始解码。
        decoder.reset();
        List<byte[]> records = decoder.feed(this.fragment(true, new byte[] { 7 }));
        assertEquals(1, records.size());
        assertArrayEquals(new byte[] { 7 }, records.get(0));
    }

    @Test
    public void resetDiscardsPartialBody() {
        // 原记录已经解析头部，并收到正文的一部分。
        OncRpcRecordDecoder decoder = new OncRpcRecordDecoder(32, 2);
        byte[] encoded = this.fragment(true, new byte[] { 1, 2, 3 });
        assertTrue(decoder.feed(Arrays.copyOf(encoded, 5)).isEmpty());

        // 复位不保留旧正文或尚未收齐的长度。
        decoder.reset();
        List<byte[]> records = decoder.feed(this.fragment(true, new byte[] { 8 }));
        assertEquals(1, records.size());
        assertArrayEquals(new byte[] { 8 }, records.get(0));
    }

    @Test
    public void resetDiscardsAccumulatedFragments() {
        // 原记录已经累计一个完整的非结束片段。
        OncRpcRecordDecoder decoder = new OncRpcRecordDecoder(2, 1);
        assertTrue(decoder.feed(this.fragment(false, new byte[] { 1, 2 })).isEmpty());

        // 复位同时释放正文容量和片段配额。
        decoder.reset();
        List<byte[]> records = decoder.feed(this.fragment(true, new byte[] { 3, 4 }));
        assertEquals(1, records.size());
        assertArrayEquals(new byte[] { 3, 4 }, records.get(0));
    }

    @Test
    public void messageLimitFailureResetsPendingRecord() {
        // 累计正文后的下一片长度声明超过上限。
        OncRpcRecordDecoder decoder = new OncRpcRecordDecoder(2, 2);
        assertTrue(decoder.feed(this.fragment(false, new byte[] { 1, 2 })).isEmpty());
        byte[] oversizedMarker = this.marker(true, 1);
        assertThrows(IllegalArgumentException.class, () -> decoder.feed(oversizedMarker));

        // 错误不污染下一轮独立解码。
        List<byte[]> records = decoder.feed(this.fragment(true, new byte[] { 7, 8 }));
        assertEquals(1, records.size());
        assertArrayEquals(new byte[] { 7, 8 }, records.get(0));
    }

    @Test
    public void fragmentLimitFailureResetsPendingRecord() {
        // 片段配额已经耗尽，后续 marker 触发拒绝。
        OncRpcRecordDecoder decoder = new OncRpcRecordDecoder(32, 1);
        assertTrue(decoder.feed(this.fragment(false, new byte[] { 1 })).isEmpty());
        byte[] excessiveMarker = this.marker(true, 1);
        assertThrows(IllegalArgumentException.class, () -> decoder.feed(excessiveMarker));

        // 失败后清空累计状态，下一条可以重新使用配额。
        List<byte[]> records = decoder.feed(this.fragment(true, new byte[] { 9 }));
        assertEquals(1, records.size());
        assertArrayEquals(new byte[] { 9 }, records.get(0));
    }

    @Test
    public void binaryBodyDoesNotInterpretEmbeddedMarkers() {
        // 正文包含看似 marker 的字节，其意义仍是普通正文。
        byte[] body = { -128, 0, 0, 0, -1, -1, -1, -1 };
        OncRpcRecordDecoder decoder = new OncRpcRecordDecoder(32, 2);

        // 仅按外层 marker 指定的长度保留原始数据。
        List<byte[]> records = decoder.feed(this.fragment(true, body));
        assertEquals(1, records.size());
        assertArrayEquals(body, records.get(0));
    }

    @Test
    public void zeroMessageLimitIsRejected() {
        // 消息容量必须是正数。
        assertThrows(IllegalArgumentException.class, () -> new OncRpcRecordDecoder(0, 1));
    }

    @Test
    public void negativeMessageLimitIsRejected() {
        // 负容量不能进入解码器状态。
        assertThrows(IllegalArgumentException.class, () -> new OncRpcRecordDecoder(-1, 1));
    }

    @Test
    public void zeroFragmentLimitIsRejected() {
        // 至少允许一个片段才能表达记录。
        assertThrows(IllegalArgumentException.class, () -> new OncRpcRecordDecoder(1, 0));
    }

    @Test
    public void negativeFragmentLimitIsRejected() {
        // 负片段配额在构造时拒绝。
        assertThrows(IllegalArgumentException.class, () -> new OncRpcRecordDecoder(1, -1));
    }

    @Test
    public void encoderProducesEmptyFinalFragmentForEmptyMessage() {
        // 空记录仍需一个带结束位的 marker。
        byte[] encoded = OncRpcRecordDecoder.encodeRecord(new byte[0], 8);

        // 结果与 RM 定义的四字节空记录一致。
        assertArrayEquals(this.marker(true, 0), encoded);
    }

    @Test
    public void encoderDoesNotAppendEmptyFragmentAtExactBoundary() {
        // 正文长度刚好是片段长度的整数倍。
        byte[] body = { 1, 2, 3, 4 };
        byte[] encoded = OncRpcRecordDecoder.encodeRecord(body, 2);

        // 最后一段直接设置结束位，无额外空 marker。
        byte[] first = this.fragment(false, new byte[] { 1, 2 });
        byte[] last = this.fragment(true, new byte[] { 3, 4 });
        assertArrayEquals(this.join(first, last), encoded);
    }

    @Test
    public void encoderKeepsShortFinalFragmentLength() {
        // 最后一片只包含不足分片长度的剩余数据。
        byte[] body = { 1, 2, 3, 4, 5 };
        byte[] encoded = OncRpcRecordDecoder.encodeRecord(body, 3);

        // 每个 marker 的长度只描述紧随其后的片段。
        byte[] first = this.fragment(false, new byte[] { 1, 2, 3 });
        byte[] last = this.fragment(true, new byte[] { 4, 5 });
        assertArrayEquals(this.join(first, last), encoded);
    }

    @Test
    public void encoderUsesSingleFragmentWhenCapacityExceedsMessage() {
        // 分片容量远大于正文，不需要插入额外 marker。
        byte[] body = { 1, 2, 3 };
        byte[] encoded = OncRpcRecordDecoder.encodeRecord(body, Integer.MAX_VALUE);

        // 唯一 marker 带结束位和真实正文长度。
        assertArrayEquals(this.fragment(true, body), encoded);
    }

    @Test
    public void encoderSupportsSingleByteFragmentSize() {
        // 最小合法片段容量为一个正文字节。
        byte[] encoded = OncRpcRecordDecoder.encodeRecord(new byte[] { 1, 2, 3 }, 1);

        // 每字节独立分片，仅最后一片设置结束位。
        byte[] first = this.fragment(false, new byte[] { 1 });
        byte[] second = this.fragment(false, new byte[] { 2 });
        byte[] last = this.fragment(true, new byte[] { 3 });
        assertArrayEquals(this.join(first, second, last), encoded);
    }

    @Test
    public void encoderRejectsZeroFragmentSize() {
        // 零容量无法切分消息。
        assertThrows(IllegalArgumentException.class, () -> OncRpcRecordDecoder.encodeRecord(new byte[0], 0));
    }

    @Test
    public void encoderRejectsNegativeFragmentSize() {
        // 负容量在分配结果前拒绝。
        assertThrows(IllegalArgumentException.class, () -> OncRpcRecordDecoder.encodeRecord(new byte[] { 1 }, -1));
    }

    @Test
    public void fragmentedMessagesRemainIndependentAcrossBytewiseInput() {
        // 两条多片段记录连续传输，并以最小网络粒度到达。
        byte[] first = OncRpcRecordDecoder.encodeRecord(new byte[] { 1, 2, 3, 4, 5 }, 2);
        byte[] second = OncRpcRecordDecoder.encodeRecord(new byte[] { 6, 7, 8 }, 2);
        byte[] encoded = this.join(first, second);
        OncRpcRecordDecoder decoder = new OncRpcRecordDecoder(5, 3);
        List<byte[]> records = new ArrayList<>();

        // 每条记录完成时释放各自计数，不跨 record 拼接。
        for (byte next : encoded) {
            records.addAll(decoder.feed(new byte[] { next }));
        }
        assertEquals(2, records.size());
        assertArrayEquals(new byte[] { 1, 2, 3, 4, 5 }, records.get(0));
        assertArrayEquals(new byte[] { 6, 7, 8 }, records.get(1));
    }

    private byte[] fragment(boolean last, byte[] body) {
        return this.join(this.marker(last, body.length), body);
    }

    private byte[] marker(boolean last, int length) {
        int marker = length;
        if (last) {
            marker |= 0x8000_0000;
        }
        return ByteBuffer.allocate(4).putInt(marker).array();
    }

    private byte[] join(byte[]... parts) {
        int size = 0;
        for (byte[] part : parts) {
            size += part.length;
        }
        byte[] joined = new byte[size];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, joined, offset, part.length);
            offset += part.length;
        }
        return joined;
    }
}

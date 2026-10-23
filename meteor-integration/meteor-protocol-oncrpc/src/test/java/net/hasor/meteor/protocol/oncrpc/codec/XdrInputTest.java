/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.codec;
import java.math.BigInteger;
import java.util.HexFormat;
import org.junit.Test;
import static org.junit.Assert.*;

public class XdrInputTest {
    @Test
    public void signedIntegerUsesNetworkByteOrder() {
        // 标准整数使用网络字节序。
        XdrInput input = this.input("01020304");
        assertEquals(0x01020304, input.readInt());
        input.requireEnd();
    }

    @Test
    public void signedIntegerRetainsMinimumValue() {
        // 最高位为一时保留有符号整数边界。
        XdrInput input = this.input("80000000");
        assertEquals(Integer.MIN_VALUE, input.readInt());
    }

    @Test
    public void unsignedIntegerAcceptsMaximumValue() {
        // uint 的全部位可用，不映射成负数。
        XdrInput input = this.input("ffffffff");
        assertEquals(4294967295L, input.readUnsignedInt());
    }

    @Test
    public void signedHyperRetainsMinimumValue() {
        // hyper 按两个连续的四字节字读取。
        XdrInput input = this.input("8000000000000000");
        assertEquals(Long.MIN_VALUE, input.readLong());
    }

    @Test
    public void unsignedHyperAcceptsMaximumValue() {
        // unsigned hyper 使用 BigInteger 保留 64 位无符号范围。
        XdrInput input = this.input("ffffffffffffffff");
        BigInteger expected = new BigInteger("18446744073709551615");
        assertEquals(expected, input.readUnsignedLong());
    }

    @Test
    public void booleanZeroMeansFalse() {
        // RFC 4506 将零定义为 false。
        XdrInput input = this.input("00000000");
        assertFalse(input.readBoolean());
    }

    @Test
    public void booleanOneMeansTrue() {
        // RFC 4506 将一而不是任意非零值定义为 true。
        XdrInput input = this.input("00000001");
        assertTrue(input.readBoolean());
    }

    @Test
    public void booleanRejectsOtherDiscriminants() {
        // 二不是合法的布尔判别值。
        XdrInput input = this.input("00000002");
        assertThrows(IllegalArgumentException.class, input::readBoolean);
    }

    @Test
    public void floatUsesIeee754Bits() {
        // 0x3fc00000 是 IEEE 754 的单精度 1.5。
        XdrInput input = this.input("3fc00000");
        assertEquals(1.5F, input.readFloat(), 0F);
    }

    @Test
    public void floatRetainsNegativeZero() {
        // 负零不能在数值转换中丢失符号位。
        XdrInput input = this.input("80000000");
        int bits = Float.floatToRawIntBits(input.readFloat());
        assertEquals(0x80000000, bits);
    }

    @Test
    public void doubleAcceptsPositiveInfinity() {
        // IEEE 754 的无穷值仍是合法 XDR 浮点数。
        XdrInput input = this.input("7ff0000000000000");
        assertEquals(Double.POSITIVE_INFINITY, input.readDouble(), 0D);
    }

    @Test
    public void fixedOpaqueConsumesZeroPadding() {
        // 三字节定长 opaque 后有一个零填充字节。
        XdrInput input = this.input("0102030000000007");
        assertArrayEquals(new byte[] { 1, 2, 3 }, input.readFixedOpaque(3));

        // 读指针跨过填充后落到下一个字段。
        assertEquals(7, input.readInt());
        input.requireEnd();
    }

    @Test
    public void fixedOpaqueRejectsNonzeroPadding() {
        // opaque 填充必须是零，不能忽略线格式中的垃圾数据。
        XdrInput input = this.input("01020301");
        assertThrows(IllegalArgumentException.class, () -> input.readFixedOpaque(3));
    }

    @Test
    public void fixedOpaqueAllowsZeroLength() {
        // 零长度定长 opaque 不消耗正文。
        XdrInput input = this.input("00000008");
        assertArrayEquals(new byte[0], input.readFixedOpaque(0));
        assertEquals(8, input.readInt());
    }

    @Test
    public void variableOpaqueReadsUnsignedLengthAndPadding() {
        // 变长 opaque 的长度不包括尾部填充。
        XdrInput input = this.input("0000000301020300");
        assertArrayEquals(new byte[] { 1, 2, 3 }, input.readOpaque(3));
        input.requireEnd();
    }

    @Test
    public void variableOpaqueAllowsZeroLength() {
        // 空 opaque 仅占四字节长度头。
        XdrInput input = this.input("00000000");
        assertArrayEquals(new byte[0], input.readOpaque(0));
        input.requireEnd();
    }

    @Test
    public void variableOpaqueRejectsLengthAboveSchemaBound() {
        // 正文合法，但字段声明的上限小于报文长度。
        XdrInput input = this.input("0000000301020300");
        assertThrows(IllegalArgumentException.class, () -> input.readOpaque(2));
    }

    @Test
    public void variableOpaqueRejectsUnsignedLengthOutsideJavaRange() {
        // 0xffffffff 不能被当作负数或可分配的数组长度。
        XdrInput input = this.input("ffffffff");
        assertThrows(IllegalArgumentException.class, () -> input.readOpaque(Integer.MAX_VALUE));
    }

    @Test
    public void asciiStringReadsStandardCharacterData() {
        // XDR string 使用 ASCII，长度为字符的八位字节数量。
        XdrInput input = this.input("0000000341626300");
        assertEquals("Abc", input.readString(3));
        input.requireEnd();
    }

    @Test
    public void asciiStringRejectsNonAsciiOctets() {
        // 非 ASCII 字节不能通过替换字符悄悄转换。
        XdrInput input = this.input("00000001ff000000");
        assertThrows(IllegalArgumentException.class, () -> input.readString(1));
    }

    @Test
    public void rawBytesDoNotAddAlignment() {
        // readBytes 只推进要求的字节，供复合协议读取原始区间。
        XdrInput input = this.input("0102030405");
        assertArrayEquals(new byte[] { 1, 2 }, input.readBytes(2));
        assertEquals(2, input.position());
        assertEquals(3, input.remaining());
    }

    @Test
    public void remainingBytesAdvanceToEnd() {
        // 读取剩余区间从当前位置开始。
        XdrInput input = this.input("010203040506");
        input.readBytes(2);

        // 返回剩余数据并使输入耗尽。
        assertArrayEquals(new byte[] { 3, 4, 5, 6 }, input.readRemaining());
        assertEquals(0, input.remaining());
        input.requireEnd();
    }

    @Test
    public void requireEndRejectsTrailingBytes() {
        // 调用方声明消息结束时不能遗留未解释数据。
        XdrInput input = this.input("0000000100000002");
        input.readInt();
        assertThrows(IllegalArgumentException.class, input::requireEnd);
    }

    @Test
    public void integerRejectsTruncatedWord() {
        // 不足四字节的整数必须失败。
        XdrInput input = this.input("010203");
        assertThrows(IllegalArgumentException.class, input::readInt);
    }

    @Test
    public void hyperRejectsTruncatedSecondWord() {
        // hyper 不允许只有完整高位字与部分低位字。
        XdrInput input = this.input("01020304050607");
        assertThrows(IllegalArgumentException.class, input::readLong);
    }

    @Test
    public void fixedOpaqueRejectsMissingPadding() {
        // 数据区完整但对齐填充尚未到达。
        XdrInput input = this.input("010203");
        assertThrows(IllegalArgumentException.class, () -> input.readFixedOpaque(3));
    }

    @Test
    public void variableOpaqueRejectsTruncatedBody() {
        // 长度头声明八字节，实际正文只有四字节。
        XdrInput input = this.input("0000000801020304");
        assertThrows(IllegalArgumentException.class, () -> input.readOpaque(8));
    }

    @Test
    public void rawBytesRejectsNegativeLength() {
        // 读取区间不接受负数长度。
        XdrInput input = this.input("01020304");
        assertThrows(IllegalArgumentException.class, () -> input.readBytes(-1));
    }

    @Test
    public void constructorRejectsOversizedMessage() {
        // 输入总量先受消息预算限制，避免在深层字段中才发现超限。
        byte[] data = new byte[5];
        assertThrows(IllegalArgumentException.class, () -> new XdrInput(data, 4, 16, 8));
    }

    private XdrInput input(String hex) {
        byte[] data = HexFormat.of().parseHex(hex);
        return new XdrInput(data, 4096, 64, 16);
    }
}

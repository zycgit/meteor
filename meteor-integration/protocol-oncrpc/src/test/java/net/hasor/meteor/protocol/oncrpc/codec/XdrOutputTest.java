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
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class XdrOutputTest {
    @Test
    public void signedIntegerProducesNetworkByteOrder() {
        // 有符号整数按网络字节序输出四字节。
        XdrOutput output = this.output();
        output.writeInt(0x01020304);
        assertArrayEquals(this.hex("01020304"), output.toByteArray());
    }

    @Test
    public void unsignedIntegerAcceptsUpperBoundary() {
        // uint 上边界占满四字节，不经过有符号截断校验。
        XdrOutput output = this.output();
        output.writeUnsignedInt(4294967295L);
        assertArrayEquals(this.hex("ffffffff"), output.toByteArray());
    }

    @Test
    public void unsignedIntegerRejectsNegativeValue() {
        // 负数不属于 uint 范围。
        XdrOutput output = this.output();
        assertThrows(IllegalArgumentException.class, () -> output.writeUnsignedInt(-1));
    }

    @Test
    public void unsignedIntegerRejectsOverflow() {
        // 超过 32 位的正数不能丢弃高位。
        XdrOutput output = this.output();
        assertThrows(IllegalArgumentException.class, () -> output.writeUnsignedInt(4294967296L));
    }

    @Test
    public void signedHyperProducesEightBytes() {
        // hyper 采用八字节网络字节序。
        XdrOutput output = this.output();
        output.writeLong(0x0102030405060708L);
        assertArrayEquals(this.hex("0102030405060708"), output.toByteArray());
    }

    @Test
    public void unsignedHyperAcceptsUpperBoundary() {
        // BigInteger 保留完整 unsigned hyper 上边界。
        XdrOutput output = this.output();
        output.writeUnsignedLong(new BigInteger("18446744073709551615"));
        assertArrayEquals(this.hex("ffffffffffffffff"), output.toByteArray());
    }

    @Test
    public void unsignedHyperRejectsNegativeValue() {
        // unsigned hyper 不接受负数。
        XdrOutput output = this.output();
        assertThrows(IllegalArgumentException.class, () -> output.writeUnsignedLong(BigInteger.valueOf(-1)));
    }

    @Test
    public void unsignedHyperRejectsOverflow() {
        // 第 65 位不能被截断后继续发送。
        XdrOutput output = this.output();
        BigInteger tooLarge = BigInteger.ONE.shiftLeft(64);
        assertThrows(IllegalArgumentException.class, () -> output.writeUnsignedLong(tooLarge));
    }

    @Test
    public void booleanTrueProducesOneWord() {
        // true 的标准表示是四字节整数一。
        XdrOutput output = this.output();
        output.writeBoolean(true);
        assertArrayEquals(this.hex("00000001"), output.toByteArray());
    }

    @Test
    public void booleanFalseProducesZeroWord() {
        // false 的标准表示是四字节整数零。
        XdrOutput output = this.output();
        output.writeBoolean(false);
        assertArrayEquals(this.hex("00000000"), output.toByteArray());
    }

    @Test
    public void floatProducesIeee754Bits() {
        // 单精度浮点数直接使用 IEEE 754 标准表示。
        XdrOutput output = this.output();
        output.writeFloat(1.5F);
        assertArrayEquals(this.hex("3fc00000"), output.toByteArray());
    }

    @Test
    public void doubleProducesIeee754Bits() {
        // 双精度浮点数使用八字节标准表示。
        XdrOutput output = this.output();
        output.writeDouble(-2.25D);
        assertArrayEquals(this.hex("c002000000000000"), output.toByteArray());
    }

    @Test
    public void fixedOpaqueAddsZeroPaddingWithoutLengthHeader() {
        // 三字节定长 opaque 只增加一字节零填充。
        XdrOutput output = this.output();
        output.writeFixedOpaque(new byte[] { 1, 2, 3 }, 3);
        assertArrayEquals(this.hex("01020300"), output.toByteArray());
    }

    @Test
    public void fixedOpaqueRejectsWrongDeclaredLength() {
        // 实际数据必须与定长字段声明完全一致。
        XdrOutput output = this.output();
        byte[] data = new byte[] { 1, 2 };
        assertThrows(IllegalArgumentException.class, () -> output.writeFixedOpaque(data, 3));
    }

    @Test
    public void variableOpaqueAddsLengthAndZeroPadding() {
        // 变长 opaque 的长度头不包含对齐填充。
        XdrOutput output = this.output();
        output.writeOpaque(new byte[] { 1, 2, 3 }, 3);
        assertArrayEquals(this.hex("0000000301020300"), output.toByteArray());
    }

    @Test
    public void variableOpaqueRejectsSchemaOverflow() {
        // 写入时遵守字段声明的长度上限。
        XdrOutput output = this.output();
        byte[] data = new byte[] { 1, 2, 3 };
        assertThrows(IllegalArgumentException.class, () -> output.writeOpaque(data, 2));
    }

    @Test
    public void asciiStringProducesStandardBytes() {
        // ASCII string 写入字节长度与零填充。
        XdrOutput output = this.output();
        output.writeString("Abc", 3);
        assertArrayEquals(this.hex("0000000341626300"), output.toByteArray());
    }

    @Test
    public void asciiStringRejectsUnicodeCharacter() {
        // XDR string 不把非 ASCII 文本隐式转换成替换字符。
        XdrOutput output = this.output();
        assertThrows(IllegalArgumentException.class, () -> output.writeString("中文", 32));
    }

    @Test
    public void rawBytesRetainOriginalLength() {
        // 原始字节写入不增加长度头或四字节填充。
        XdrOutput output = this.output();
        output.writeBytes(new byte[] { 1, 2, 3 });
        assertArrayEquals(this.hex("010203"), output.toByteArray());
        assertEquals(3, output.size());
    }

    @Test
    public void messageBudgetRejectsOverflow() {
        // 前一个字段已占满预算，后续写入必须失败。
        XdrOutput output = new XdrOutput(4, 16, 8);
        output.writeInt(1);
        assertThrows(IllegalArgumentException.class, () -> output.writeInt(2));
    }

    @Test
    public void opaquePaddingCountsTowardsMessageBudget() {
        // 数据与长度头虽能容纳，完整对齐后的字段超出预算。
        XdrOutput output = new XdrOutput(7, 16, 8);
        byte[] data = new byte[] { 1, 2, 3 };
        assertThrows(IllegalArgumentException.class, () -> output.writeOpaque(data, 3));
    }

    private XdrOutput output() {
        return new XdrOutput(4096, 64, 16);
    }

    private byte[] hex(String hex) {
        return HexFormat.of().parseHex(hex);
    }
}

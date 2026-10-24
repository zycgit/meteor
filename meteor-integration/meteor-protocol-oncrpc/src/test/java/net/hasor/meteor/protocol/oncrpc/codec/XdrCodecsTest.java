/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.codec;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class XdrCodecsTest {
    @Test
    public void integerScalarRejectsNullWithAnExplicitError() {
        // 非 optional 的标量拒绝 null，不暴露拆箱引发的 NullPointerException。
        XdrOutput output = this.output();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            output.write(XdrCodecs.INT, null);
        });
        assertTrue(failure.getMessage().contains("cannot be null"));
    }

    @Test
    public void unsignedIntegerScalarRejectsNullWithAnExplicitError() {
        // unsigned int 同样需要显式可空声明，不能隐式增加存在位。
        XdrOutput output = this.output();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            output.write(XdrCodecs.UNSIGNED_INT, null);
        });
        assertTrue(failure.getMessage().contains("cannot be null"));
    }

    @Test
    public void hyperScalarRejectsNullWithAnExplicitError() {
        // Java Long 的空值不是 XDR hyper 的合法线上值。
        XdrOutput output = this.output();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            output.write(XdrCodecs.LONG, null);
        });
        assertTrue(failure.getMessage().contains("cannot be null"));
    }

    @Test
    public void booleanScalarRejectsNullWithAnExplicitError() {
        // null 不能被当成 false 写入，否则会无声改变用户数据。
        XdrOutput output = this.output();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            output.write(XdrCodecs.BOOLEAN, null);
        });
        assertTrue(failure.getMessage().contains("cannot be null"));
    }

    @Test
    public void floatScalarRejectsNullWithAnExplicitError() {
        // 空包装值必须在浮点拆箱前明确拒绝。
        XdrOutput output = this.output();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            output.write(XdrCodecs.FLOAT, null);
        });
        assertTrue(failure.getMessage().contains("cannot be null"));
    }

    @Test
    public void doubleScalarRejectsNullWithAnExplicitError() {
        // 空包装值不能自动替换为零或 NaN。
        XdrOutput output = this.output();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            output.write(XdrCodecs.DOUBLE, null);
        });
        assertTrue(failure.getMessage().contains("cannot be null"));
    }

    @Test
    public void integerCodecWritesStandardWord() {
        // 标量编码器写出 RFC 4506 的整数表示。
        XdrOutput output = this.output();
        output.write(XdrCodecs.INT, 17);
        assertArrayEquals(this.hex("00000011"), output.toByteArray());
    }

    @Test
    public void unsignedIntegerCodecReadsUpperBoundary() {
        // uint 编码器向用户暴露 long，保留全部无符号位。
        XdrInput input = this.input("ffffffff");
        assertEquals(Long.valueOf(4294967295L), input.read(XdrCodecs.UNSIGNED_INT));
    }

    @Test
    public void hyperCodecReadsSignedValue() {
        // hyper 编码器保留负数符号。
        XdrInput input = this.input("fffffffffffffffe");
        assertEquals(Long.valueOf(-2), input.read(XdrCodecs.LONG));
    }

    @Test
    public void unsignedHyperCodecWritesUpperBoundary() {
        // 编码器保留 unsigned hyper 的全部 64 位。
        XdrOutput output = this.output();
        BigInteger maximum = new BigInteger("18446744073709551615");
        output.write(XdrCodecs.UNSIGNED_LONG, maximum);
        assertArrayEquals(this.hex("ffffffffffffffff"), output.toByteArray());
    }

    @Test
    public void booleanCodecReadsTrue() {
        // 布尔编码器读取标准判别值一。
        XdrInput input = this.input("00000001");
        assertEquals(Boolean.TRUE, input.read(XdrCodecs.BOOLEAN));
    }

    @Test
    public void floatCodecReadsNan() {
        // NaN 是合法浮点数，不应在协议层拒绝。
        XdrInput input = this.input("7fc00000");
        assertTrue(Float.isNaN(input.read(XdrCodecs.FLOAT)));
    }

    @Test
    public void doubleCodecReadsNegativeZero() {
        // 编码器不能把负零正规化成正零。
        XdrInput input = this.input("8000000000000000");
        long bits = Double.doubleToRawLongBits(input.read(XdrCodecs.DOUBLE));
        assertEquals(Long.MIN_VALUE, bits);
    }

    @Test
    public void quadrupleCodecPreservesAllSixteenBytes() {
        // quadruple 保留原始 IEEE 754 位，不经过精度不足的 double。
        byte[] bits = this.hex("3fff0000000000000000000000000001");
        XdrOutput output = this.output();
        output.write(XdrCodecs.QUADRUPLE, bits);
        assertArrayEquals(bits, output.toByteArray());
    }

    @Test
    public void quadrupleCodecReadsAllSixteenBytes() {
        // 解码完整读取原始 quadruple。
        String encoded = "80000000000000000000000000000000";
        XdrInput input = this.input(encoded);
        assertArrayEquals(this.hex(encoded), input.read(XdrCodecs.QUADRUPLE));
        input.requireEnd();
    }

    @Test
    public void quadrupleCodecRejectsWrongByteCount() {
        // 十五字节不是完整 quadruple。
        XdrOutput output = this.output();
        byte[] truncated = new byte[15];
        assertThrows(IllegalArgumentException.class, () -> output.write(XdrCodecs.QUADRUPLE, truncated));
    }

    @Test
    public void voidCodecConsumesNoBytes() {
        // void 的线格式为空，不能消耗后续字段。
        XdrInput input = this.input("00000009");
        assertNull(input.read(XdrCodecs.VOID));
        assertEquals(9, input.readInt());
    }

    @Test
    public void voidCodecProducesNoBytes() {
        // void 返回空值且不产生占位字节。
        XdrOutput output = this.output();
        output.write(XdrCodecs.VOID, null);
        assertEquals(0, output.size());
    }

    @Test
    public void boundedStringCodecUsesAscii() {
        // 标准 string 与 ASCII 的显式字节表示一致。
        XdrOutput output = this.output();
        output.write(XdrCodecs.string(3), "abc");
        assertArrayEquals(this.hex("0000000361626300"), output.toByteArray());
    }

    @Test
    public void utf8CodecExplicitlySupportsUnicode() {
        // UTF-8 属于显式扩展，编码长度是字节数。
        XdrOutput output = this.output();
        output.write(XdrCodecs.utf8(3), "中");
        assertArrayEquals(this.hex("00000003e4b8ad00"), output.toByteArray());
    }

    @Test
    public void utf8CodecReadsUnicode() {
        // UTF-8 解码恢复原文。
        XdrInput input = this.input("00000003e4b8ad00");
        assertEquals("中", input.read(XdrCodecs.utf8(3)));
    }

    @Test
    public void utf8CodecRejectsMalformedSequence() {
        // 截断的多字节字符不能用替换字符接受。
        XdrInput input = this.input("00000002e4b80000");
        XdrCodec<String> codec = XdrCodecs.utf8(8);
        assertThrows(IllegalArgumentException.class, () -> input.read(codec));
    }

    @Test
    public void utf8CodecEnforcesByteLengthInsteadOfCharacterCount() {
        // 一个字符仍可能超过声明的 UTF-8 字节上限。
        XdrOutput output = this.output();
        XdrCodec<String> codec = XdrCodecs.utf8(2);
        assertThrows(IllegalArgumentException.class, () -> output.write(codec, "中"));
    }

    @Test
    public void fixedOpaqueCodecUsesDeclaredLength() {
        // 定长 opaque 不携带变长字段的长度头。
        XdrInput input = this.input("01020300");
        assertArrayEquals(new byte[] { 1, 2, 3 }, input.read(XdrCodecs.fixedOpaque(3)));
        input.requireEnd();
    }

    @Test
    public void opaqueCodecWritesBoundedVariableData() {
        // 变长 opaque 编码器遵循长度头与填充约定。
        XdrOutput output = this.output();
        output.write(XdrCodecs.opaque(3), new byte[] { 1, 2, 3 });
        assertArrayEquals(this.hex("0000000301020300"), output.toByteArray());
    }

    @Test
    public void listCodecUsesCountThenElements() {
        // 变长数组先写元素数量，随后按声明顺序写元素。
        XdrOutput output = this.output();
        output.write(XdrCodecs.list(XdrCodecs.INT, 2), Arrays.asList(5, -1));
        assertArrayEquals(this.hex("0000000200000005ffffffff"), output.toByteArray());
    }

    @Test
    public void listCodecReadsElementsInDeclarationOrder() {
        // 独立黄金报文验证数组顺序，不依赖自身编码往返。
        XdrInput input = this.input("0000000200000009fffffffd");
        List<Integer> decoded = input.read(XdrCodecs.list(XdrCodecs.INT, 2));
        assertEquals(Arrays.asList(9, -3), decoded);
    }

    @Test
    public void listCodecAllowsEmptyArray() {
        // 零长度数组只含数量头。
        XdrInput input = this.input("00000000");
        assertTrue(input.read(XdrCodecs.list(XdrCodecs.INT, 0)).isEmpty());
        input.requireEnd();
    }

    @Test
    public void listCodecRejectsWriteAboveSchemaBound() {
        // 写入数量不能超过 IDL 声明的上限。
        XdrOutput output = this.output();
        XdrCodec<List<Integer>> codec = XdrCodecs.list(XdrCodecs.INT, 1);
        List<Integer> values = Arrays.asList(1, 2);
        assertThrows(IllegalArgumentException.class, () -> output.write(codec, values));
    }

    @Test
    public void listCodecRejectsReadAboveSchemaBound() {
        // 即便正文足够，也不能超出声明数组上限。
        XdrInput input = this.input("000000020000000100000002");
        XdrCodec<List<Integer>> codec = XdrCodecs.list(XdrCodecs.INT, 1);
        assertThrows(IllegalArgumentException.class, () -> input.read(codec));
    }

    @Test
    public void listCodecRejectsUnsignedCountOverflow() {
        // 超大无符号数量必须在分配数组前失败。
        XdrInput input = this.input("ffffffff");
        XdrCodec<List<Integer>> codec = XdrCodecs.list(XdrCodecs.INT, Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> input.read(codec));
    }

    @Test
    public void listCodecRejectsTruncatedElement() {
        // 数量头声明两个元素，正文只包含一个。
        XdrInput input = this.input("0000000200000001");
        XdrCodec<List<Integer>> codec = XdrCodecs.list(XdrCodecs.INT, 2);
        assertThrows(IllegalArgumentException.class, () -> input.read(codec));
    }

    @Test
    public void listCodecEnforcesGlobalElementBudget() {
        // 全局元素预算比字段声明更严格时仍必须生效。
        byte[] data = this.hex("000000020000000100000002");
        XdrInput input = new XdrInput(data, 4096, 1, 16);
        XdrCodec<List<Integer>> codec = XdrCodecs.list(XdrCodecs.INT, 2);
        assertThrows(IllegalArgumentException.class, () -> input.read(codec));
    }

    @Test
    public void fixedArrayCodecOmitsCountHeader() {
        // 定长数组只保存固定数量的元素。
        XdrOutput output = this.output();
        output.write(XdrCodecs.fixedArray(XdrCodecs.INT, 2), Arrays.asList(1, 2));
        assertArrayEquals(this.hex("0000000100000002"), output.toByteArray());
    }

    @Test
    public void fixedArrayCodecReadsDeclaredCount() {
        // 没有数量头时使用声明中的元素数量。
        XdrInput input = this.input("0000000700000008");
        List<Integer> decoded = input.read(XdrCodecs.fixedArray(XdrCodecs.INT, 2));
        assertEquals(Arrays.asList(7, 8), decoded);
    }

    @Test
    public void fixedArrayCodecRejectsWrongElementCount() {
        // 定长数组不能用不足数量的数据补零发送。
        XdrOutput output = this.output();
        XdrCodec<List<Integer>> codec = XdrCodecs.fixedArray(XdrCodecs.INT, 2);
        List<Integer> values = Arrays.asList(1);
        assertThrows(IllegalArgumentException.class, () -> output.write(codec, values));
    }

    @Test
    public void optionalCodecWritesPresentDiscriminant() {
        // 可选值存在时写一，后跟真实值。
        XdrOutput output = this.output();
        output.write(XdrCodecs.optional(XdrCodecs.INT), 7);
        assertArrayEquals(this.hex("0000000100000007"), output.toByteArray());
    }

    @Test
    public void optionalCodecWritesAbsentDiscriminant() {
        // 空指针只发送 false 判别值。
        XdrOutput output = this.output();
        output.write(XdrCodecs.optional(XdrCodecs.INT), null);
        assertArrayEquals(this.hex("00000000"), output.toByteArray());
    }

    @Test
    public void optionalCodecReadsPresentValue() {
        // 判别值为一时读取可选对象。
        XdrInput input = this.input("0000000100000007");
        assertEquals(Integer.valueOf(7), input.read(XdrCodecs.optional(XdrCodecs.INT)));
    }

    @Test
    public void optionalCodecReadsAbsentValue() {
        // false 判别值不继续消费对象正文。
        XdrInput input = this.input("0000000000000009");
        assertNull(input.read(XdrCodecs.optional(XdrCodecs.INT)));
        assertEquals(9, input.readInt());
    }

    @Test
    public void optionalCodecRejectsInvalidDiscriminant() {
        // optional 采用严格 XDR bool，不接受任意非零判别值。
        XdrInput input = this.input("00000002");
        XdrCodec<Integer> codec = XdrCodecs.optional(XdrCodecs.INT);
        assertThrows(IllegalArgumentException.class, () -> input.read(codec));
    }

    @Test
    public void enumerationCodecWritesExplicitNumber() {
        // 枚举值使用映射编号，不使用 Java ordinal。
        XdrCodec<String> codec = XdrCodecs.enumeration(value -> "ready", value -> 17);
        XdrOutput output = this.output();
        output.write(codec, "ready");
        assertArrayEquals(this.hex("00000011"), output.toByteArray());
    }

    @Test
    public void enumerationCodecReadsExplicitNumber() {
        // 读取时由使用者的声明映射枚举编号。
        XdrCodec<String> codec = XdrCodecs.enumeration(value -> "state-" + value, value -> 0);
        XdrInput input = this.input("00000011");
        assertEquals("state-17", input.read(codec));
    }

    @Test
    public void enumerationCodecRejectsUnknownNumber() {
        // 没有声明对应成员的枚举编号必须失败。
        XdrCodec<String> codec = XdrCodecs.enumeration(value -> null, value -> 0);
        XdrInput input = this.input("0000007f");
        assertThrows(IllegalArgumentException.class, () -> input.read(codec));
    }

    @Test
    public void nestedCodecReadEnforcesDepthLimit() {
        // 两层 optional 需要连续进入两层嵌套编码器。
        XdrInput input = new XdrInput(this.hex("000000010000000100000007"), 4096, 64, 1);
        XdrCodec<Integer> codec = XdrCodecs.optional(XdrCodecs.optional(XdrCodecs.INT));
        assertThrows(IllegalArgumentException.class, () -> input.read(codec));
    }

    @Test
    public void nestedCodecWriteEnforcesDepthLimit() {
        // 嵌套深度预算同时约束写入，避免递归输出耗尽栈。
        XdrOutput output = new XdrOutput(4096, 64, 1);
        XdrCodec<Integer> codec = XdrCodecs.optional(XdrCodecs.optional(XdrCodecs.INT));
        assertThrows(IllegalArgumentException.class, () -> output.write(codec, 7));
    }

    @Test
    public void nestedArrayCountsElementsAcrossWholeMessage() {
        // 两个内层数组分别合法，但它们的总元素数超过消息预算。
        byte[] data = this.hex("0000000200000001000000010000000100000002");
        XdrInput input = new XdrInput(data, 4096, 3, 16);
        XdrCodec<List<Integer>> inner = XdrCodecs.list(XdrCodecs.INT, 1);
        XdrCodec<List<List<Integer>>> codec = XdrCodecs.list(inner, 2);
        assertThrows(IllegalArgumentException.class, () -> input.read(codec));
    }

    @Test
    public void nestedArrayWriteCountsElementsAcrossWholeMessage() {
        // 两个内层数组的总元素数超过消息预算，写入同样必须拒绝。
        XdrOutput output = new XdrOutput(4096, 3, 16);
        XdrCodec<List<Integer>> inner = XdrCodecs.list(XdrCodecs.INT, 1);
        XdrCodec<List<List<Integer>>> codec = XdrCodecs.list(inner, 2);
        List<List<Integer>> values = Arrays.asList(Arrays.asList(1), Arrays.asList(2));
        assertThrows(IllegalArgumentException.class, () -> output.write(codec, values));
    }

    @Test
    public void failedReadRestoresCodecDepth() {
        // 枚举读取失败后，深度预算必须退出当前编码器。
        XdrInput input = new XdrInput(this.hex("0000007f00000007"), 4096, 64, 1);
        XdrCodec<String> unknownEnum = XdrCodecs.enumeration(value -> null, value -> 0);
        assertThrows(IllegalArgumentException.class, () -> input.read(unknownEnum));

        // 后续独立字段仍可使用同一个深度预算。
        assertEquals(Integer.valueOf(7), input.read(XdrCodecs.INT));
        input.requireEnd();
    }

    @Test
    public void failedWriteRestoresCodecDepth() {
        // 空枚举值失败后，深度预算必须退出当前编码器。
        XdrOutput output = new XdrOutput(4096, 64, 1);
        XdrCodec<String> enumCodec = XdrCodecs.enumeration(value -> "ready", value -> 0);
        assertThrows(IllegalArgumentException.class, () -> output.write(enumCodec, null));

        // 后续独立字段仍可正常写入。
        output.write(XdrCodecs.INT, 7);
        assertArrayEquals(this.hex("00000007"), output.toByteArray());
    }

    @Test
    public void utf8CodecRejectsUnpairedSurrogate() {
        // Java 中孤立的高代理字符不能变成替换字节发送。
        XdrOutput output = this.output();
        String malformed = String.valueOf((char) 0xd800);
        XdrCodec<String> codec = XdrCodecs.utf8(8);
        assertThrows(IllegalArgumentException.class, () -> output.write(codec, malformed));
    }

    @Test
    public void asciiStringPreservesEmbeddedNul() {
        // XDR 字符串由长度定界，内部零字节不会截断文本。
        XdrInput input = this.input("0000000341004200");
        String expected = "A" + (char) 0 + "B";
        assertEquals(expected, input.read(XdrCodecs.string(3)));
        input.requireEnd();
    }

    private XdrInput input(String hex) {
        return new XdrInput(this.hex(hex), 4096, 64, 16);
    }

    private XdrOutput output() {
        return new XdrOutput(4096, 64, 16);
    }

    private byte[] hex(String hex) {
        return HexFormat.of().parseHex(hex);
    }
}

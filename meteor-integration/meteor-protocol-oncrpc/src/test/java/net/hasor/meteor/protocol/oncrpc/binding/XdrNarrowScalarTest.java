/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.binding;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import net.hasor.cobble.reflect.resolvable.ResolvableType;
import net.hasor.meteor.protocol.oncrpc.codec.XdrCodec;
import net.hasor.meteor.protocol.oncrpc.codec.XdrInput;
import net.hasor.meteor.protocol.oncrpc.codec.XdrOutput;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;

/** Java 窄整数和 UTF-16 code unit 使用标准 XDR int，并在 Java 类型边界处校验。 */
@RunWith(Parameterized.class)
public class XdrNarrowScalarTest {
    private final Class<?> raw;
    private final Object   lower;
    private final Object   upper;
    private final int      minimum;
    private final int      maximum;

    public XdrNarrowScalarTest(Class<?> raw, Object lower, Object upper, int minimum, int maximum) {
        this.raw = raw;
        this.lower = lower;
        this.upper = upper;
        this.minimum = minimum;
        this.maximum = maximum;
    }

    @Parameterized.Parameters(name = "{0}")
    public static List<Object[]> types() {
        // 每种基本类型紧跟其包装类型，Java 边界与标准 int32 线上边界分别传入。
        List<Object[]> types = new ArrayList<>();
        byte byteMin = Byte.MIN_VALUE;
        byte byteMax = Byte.MAX_VALUE;
        types.add(new Object[] { byte.class, byteMin, byteMax, (int) byteMin, (int) byteMax });
        types.add(new Object[] { Byte.class, byteMin, byteMax, (int) byteMin, (int) byteMax });

        short shortMin = Short.MIN_VALUE;
        short shortMax = Short.MAX_VALUE;
        types.add(new Object[] { short.class, shortMin, shortMax, (int) shortMin, (int) shortMax });
        types.add(new Object[] { Short.class, shortMin, shortMax, (int) shortMin, (int) shortMax });

        char charMin = Character.MIN_VALUE;
        char charMax = Character.MAX_VALUE;
        types.add(new Object[] { char.class, charMin, charMax, 0, (int) charMax });
        types.add(new Object[] { Character.class, charMin, charMax, 0, (int) charMax });
        return types;
    }

    @Test
    public void lowerBoundaryWritesOneStandardSignedWord() {
        // 独立构造标准 int32 黄金报文，不能依赖同一编码器的往返掩盖错误。
        XdrOutput output = this.output();
        this.codec().write(output, this.lower);
        assertArrayEquals(this.word(this.minimum), output.toByteArray());
    }

    @Test
    public void upperBoundaryWritesOneStandardSignedWord() {
        // char 上界仍在有符号 int32 内，不缩窄到 byte 或 ASCII。
        XdrOutput output = this.output();
        this.codec().write(output, this.upper);
        assertArrayEquals(this.word(this.maximum), output.toByteArray());
    }

    @Test
    public void lowerBoundaryReadsTheExactJavaType() {
        // 包装类型保持 Byte、Short 或 Character，不返回会破坏反射参数绑定的 Integer。
        Object restored = this.codec().read(this.input(this.minimum));
        assertEquals(this.lower, restored);
        assertSame(this.lower.getClass(), restored.getClass());
    }

    @Test
    public void upperBoundaryReadsTheExactJavaType() {
        // 完整保留目标 Java 类型的最大合法值。
        Object restored = this.codec().read(this.input(this.maximum));
        assertEquals(this.upper, restored);
        assertSame(this.upper.getClass(), restored.getClass());
    }

    @Test
    public void wordBelowJavaRangeIsRejectedInsteadOfTruncated() {
        // 对端可发送任意 int32，但超出目标 Java 类型范围时必须失败。
        XdrCodec<Object> codec = this.codec();
        XdrInput input = this.input(this.minimum - 1);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> codec.read(input));
        assertTrue(failure.getMessage().contains("range"));
    }

    @Test
    public void wordAboveJavaRangeIsRejectedInsteadOfTruncated() {
        // 上界不能通过强制类型转换绕回负数或零。
        XdrCodec<Object> codec = this.codec();
        XdrInput input = this.input(this.maximum + 1);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> codec.read(input));
        assertTrue(failure.getMessage().contains("range"));
    }

    @Test
    public void nullDoesNotInventAnOptionalDiscriminant() {
        // 普通 XDR int 没有 null；可空映射必须由显式 optional 契约声明。
        XdrCodec<Object> codec = this.codec();
        XdrOutput output = this.output();
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> codec.write(output, null));
        assertTrue(error.getMessage().contains("cannot be null"));
        assertEquals(0, output.size());
    }

    @Test
    public void generatedDefinitionDeclaresTheStandardIntType() {
        // 不生成 Java 私有的 byte、short 或 char 线上类型。
        XdrSchemaBuilder builder = new XdrSchemaBuilder(null, 1024, 100);
        ResolvableType type = ResolvableType.forClass(this.raw);
        assertNotNull(builder.codec(type));
        assertEquals("int", builder.definition(type).kind);
    }

    private XdrCodec<Object> codec() {
        XdrSchemaBuilder builder = new XdrSchemaBuilder(null, 1024, 100);
        return builder.codec(ResolvableType.forClass(this.raw));
    }

    private XdrOutput output() {
        return new XdrOutput(1024, 100, 16);
    }

    private XdrInput input(int value) {
        return new XdrInput(this.word(value), 1024, 100, 16);
    }

    private byte[] word(int value) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
    }
}

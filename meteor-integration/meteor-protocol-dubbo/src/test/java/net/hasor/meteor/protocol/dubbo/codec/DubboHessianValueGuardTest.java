/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.dubbo.codec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DubboHessianValueGuardTest {
    @Test
    public void rejectsEmptyDocument() {
        // 无正文不能表示一次完整的 Hessian 值序列。
        IOException failure = assertThrows(IOException.class, () -> DubboHessianValueGuard.validate(new byte[0]));
        assertTrue(failure.getMessage().contains("Empty Dubbo Hessian document"));
    }

    @Test
    public void acceptsMultipleTopLevelValues() throws Exception {
        // Dubbo 将版本、服务、方法与参数连续写入同一个 Hessian 对象流。
        byte[] document = bytes(1, 'v', 1, 's', 1, 'm', 0x91, 'N', 'H', 'Z');

        // 校验器应读取整个序列，而非把首个值之后的内容视为多余字节。
        DubboHessianValueGuard.validate(document);
    }

    @Test
    public void validatesEveryTopLevelValue() {
        // 首个值合法不能掩盖后续非法标签。
        byte[] document = bytes('N', 'E');
        IOException failure = assertThrows(IOException.class, () -> DubboHessianValueGuard.validate(document));
        assertTrue(failure.getMessage().contains("Invalid Hessian 2 value tag"));
    }

    @Test
    public void acceptsBooleanValues() throws Exception {
        // 布尔值没有额外正文，两个值可以直接相邻。
        DubboHessianValueGuard.validate(bytes('T', 'F'));
    }

    @Test
    public void acceptsCompactIntegers() throws Exception {
        // 三种紧凑整数宽度覆盖合法标签的两端。
        byte[] document = bytes(0x80, 0xbf, 0xc0, 0, 0xcf, 255, 0xd0, 0, 0, 0xd7, 255, 255);
        DubboHessianValueGuard.validate(document);
    }

    @Test
    public void acceptsCompactLongs() throws Exception {
        // 长整数的紧凑表示使用独立标签范围。
        byte[] document = bytes(0xd8, 0xef, 0xf0, 0, 0xff, 255, 0x38, 0, 0, 0x3f, 255, 255);
        DubboHessianValueGuard.validate(document);
    }

    @Test
    public void acceptsFullWidthNumbers() throws Exception {
        // 完整整数、长整数和双精度数分别消费四个或八个字节。
        byte[] document = bytes('I', 0, 0, 0, 1, 'L', 0, 0, 0, 0, 0, 0, 0, 2, //
                'D', 0, 0, 0, 0, 0, 0, 0, 0, 0x59, 0, 0, 0, 3, 0x5f, 0, 0, 0, 0);
        DubboHessianValueGuard.validate(document);
    }

    @Test
    public void acceptsCompactDoubles() throws Exception {
        // 零、一、整数和短整数形式均属于合法双精度值。
        DubboHessianValueGuard.validate(bytes(0x5b, 0x5c, 0x5d, 7, 0x5e, 0, 8));
    }

    @Test
    public void acceptsDateValues() throws Exception {
        // 日期允许八字节毫秒与四字节分钟表示。
        DubboHessianValueGuard.validate(bytes(0x4a, 0, 0, 0, 0, 0, 0, 0, 1, 0x4b, 0, 0, 0, 1));
    }

    @Test
    public void rejectsTruncatedFullWidthNumber() {
        // I 标签必须带有完整四字节正文。
        byte[] document = bytes('I', 0, 0, 1);
        IOException failure = assertThrows(IOException.class, () -> DubboHessianValueGuard.validate(document));
        assertTrue(failure.getMessage().contains("Hessian declared length"));
    }

    @Test
    public void acceptsCompactString() throws Exception {
        // 紧凑字符串长度直接编码在标签中。
        DubboHessianValueGuard.validate(bytes(3, 'r', 'p', 'c'));
    }

    @Test
    public void acceptsMediumString() throws Exception {
        // 中等长度形式使用标签加一个长度字节。
        DubboHessianValueGuard.validate(bytes(0x30, 1, 's'));
    }

    @Test
    public void acceptsLongString() throws Exception {
        // S 标签后面的无符号短整数表示字符数。
        DubboHessianValueGuard.validate(bytes('S', 0, 1, 's'));
    }

    @Test
    public void acceptsChunkedString() throws Exception {
        // 非最终块后必须继续读取最终字符串块。
        DubboHessianValueGuard.validate(bytes('R', 0, 1, 'a', 1, 'b'));
    }

    @Test
    public void rejectsStringWithoutFinalChunk() {
        // 前块完整仍不能省略最终块。
        byte[] document = bytes('R', 0, 1, 'a');
        IOException failure = assertThrows(IOException.class, () -> DubboHessianValueGuard.validate(document));
        assertTrue(failure.getMessage().contains("Truncated Hessian message"));
    }

    @Test
    public void rejectsStringWithMissingCharacters() {
        // 字符数声明不能超过剩余正文。
        byte[] document = bytes(3, 'a');
        IOException failure = assertThrows(IOException.class, () -> DubboHessianValueGuard.validate(document));
        assertTrue(failure.getMessage().contains("Hessian declared length"));
    }

    @Test
    public void acceptsTwoByteUtf8Character() throws Exception {
        // Hessian 的字符数不等于 UTF-8 字节数。
        DubboHessianValueGuard.validate(bytes(1, 0xc2, 0xa2));
    }

    @Test
    public void acceptsThreeByteUtf8Character() throws Exception {
        // 一个中文字符消费三个 UTF-8 字节。
        DubboHessianValueGuard.validate(bytes(1, 0xe4, 0xb8, 0xad));
    }

    @Test
    public void acceptsUtf16SurrogateUnits() throws Exception {
        // Hessian 以两个 UTF-16 单元编码补充字符，允许代理单元的三字节形式。
        DubboHessianValueGuard.validate(bytes(2, 0xed, 0xa0, 0xbd, 0xed, 0xb8, 0x80));
    }

    @Test
    public void rejectsInvalidUtf8Continuation() {
        // 连续字节必须位于 UTF-8 的后续字节范围。
        byte[] document = bytes(1, 0xc2, 0x40);
        IOException failure = assertThrows(IOException.class, () -> DubboHessianValueGuard.validate(document));
        assertTrue(failure.getMessage().contains("Invalid Hessian UTF-8 continuation"));
    }

    @Test
    public void rejectsOverlongUtf8Character() {
        // 三字节形式不能用于本可由更短编码表示的字符。
        byte[] document = bytes(1, 0xe0, 0x80, 0x80);
        IOException failure = assertThrows(IOException.class, () -> DubboHessianValueGuard.validate(document));
        assertTrue(failure.getMessage().contains("Overlong Hessian UTF-8 sequence"));
    }

    @Test
    public void rejectsUnsupportedUtf8LeadingByte() {
        // 非法首字节不能被当作单个字符跳过。
        byte[] document = bytes(1, 0xff);
        IOException failure = assertThrows(IOException.class, () -> DubboHessianValueGuard.validate(document));
        assertTrue(failure.getMessage().contains("Invalid Hessian UTF-8 sequence"));
    }

    @Test
    public void rejectsTruncatedUtf8Character() {
        // 字符首字节存在不代表整个 UTF-8 单元已经到齐。
        byte[] document = bytes(1, 0xc2);
        IOException failure = assertThrows(IOException.class, () -> DubboHessianValueGuard.validate(document));
        assertTrue(failure.getMessage().contains("Truncated Hessian message"));
    }

    @Test
    public void acceptsCompactBinary() throws Exception {
        // 二进制内容不按 UTF-8 校验。
        DubboHessianValueGuard.validate(bytes(0x22, 0xff, 0x80));
    }

    @Test
    public void acceptsMediumBinary() throws Exception {
        // 中等长度二进制的长度由标签和后续字节共同给出。
        DubboHessianValueGuard.validate(bytes(0x34, 1, 0xff));
    }

    @Test
    public void acceptsLongBinary() throws Exception {
        // B 标签使用无符号短整数声明最终二进制块长度。
        DubboHessianValueGuard.validate(bytes('B', 0, 2, 0xff, 0x80));
    }

    @Test
    public void acceptsChunkedBinary() throws Exception {
        // 连续块与最终块一起构成一个二进制值。
        DubboHessianValueGuard.validate(bytes('A', 0, 1, 0xff, 0x21, 0x80));
    }

    @Test
    public void rejectsBinaryChunkWithNonBinaryTerminator() {
        // 非最终块后不能直接跟其他值类型。
        byte[] document = bytes('A', 0, 1, 0xff, 'N');
        IOException failure = assertThrows(IOException.class, () -> DubboHessianValueGuard.validate(document));
        assertTrue(failure.getMessage().contains("Expected Hessian binary chunk"));
    }

    @Test
    public void rejectsBinaryWithMissingBytes() {
        // 声明两字节但只提供一字节的块必须失败。
        byte[] document = bytes('B', 0, 2, 0xff);
        IOException failure = assertThrows(IOException.class, () -> DubboHessianValueGuard.validate(document));
        assertTrue(failure.getMessage().contains("Hessian declared length"));
    }

    @Test
    public void acceptsFixedUntypedList() throws Exception {
        // X 后的整数给出列表元素数量。
        DubboHessianValueGuard.validate(bytes('X', 0x92, 'N', 'T'));
    }

    @Test
    public void acceptsVariableUntypedList() throws Exception {
        // W 列表由 Z 结束，元素本身仍逐个校验。
        DubboHessianValueGuard.validate(bytes('W', 'N', 'T', 'Z'));
    }

    @Test
    public void acceptsTypedFixedList() throws Exception {
        // V 列表在长度前声明类型名。
        DubboHessianValueGuard.validate(bytes('V', 1, 'x', 0x91, 'N'));
    }

    @Test
    public void acceptsTypedVariableList() throws Exception {
        // U 列表保留类型名并使用结束标记。
        DubboHessianValueGuard.validate(bytes('U', 1, 'x', 'N', 'Z'));
    }

    @Test
    public void acceptsCompactTypedListWithTypeReference() throws Exception {
        // 类型引用在同一对象流的多个顶层值之间保留。
        DubboHessianValueGuard.validate(bytes(0x70, 1, 'x', 0x71, 0x90, 'N'));
    }

    @Test
    public void rejectsUnknownTypeReference() {
        // 未声明过的类型不能通过索引引用。
        byte[] document = bytes(0x70, 0x90);
        IOException failure = assertThrows(IOException.class, () -> DubboHessianValueGuard.validate(document));
        assertTrue(failure.getMessage().contains("Invalid Hessian reference"));
    }

    @Test
    public void rejectsNegativeListLength() {
        // X 的负长度不能退化为读取到结束标记的列表。
        byte[] document = bytes('X', 0x8f);
        IOException failure = assertThrows(IOException.class, () -> DubboHessianValueGuard.validate(document));
        assertTrue(failure.getMessage().contains("Hessian declared length"));
    }

    @Test
    public void rejectsListClaimingHugeAllocation() {
        // 六字节正文不能宣称包含接近 2GB 的数组。
        byte[] document = bytes('X', 'I', 0x7f, 0xff, 0xff, 0xff);
        IOException failure = assertThrows(IOException.class, () -> DubboHessianValueGuard.validate(document));
        assertTrue(failure.getMessage().contains("Hessian declared length"));
    }

    @Test
    public void rejectsListLengthWithWrongValueType() {
        // 列表长度必须是整数，而不是任意 Hessian 值。
        byte[] document = bytes('X', 'N');
        IOException failure = assertThrows(IOException.class, () -> DubboHessianValueGuard.validate(document));
        assertTrue(failure.getMessage().contains("Expected Hessian integer"));
    }

    @Test
    public void rejectsVariableListWithoutTerminator() {
        // 可变长列表只有见到 Z 才能完成。
        byte[] document = bytes('W', 'N');
        IOException failure = assertThrows(IOException.class, () -> DubboHessianValueGuard.validate(document));
        assertTrue(failure.getMessage().contains("Truncated Hessian message"));
    }

    @Test
    public void acceptsUntypedMap() throws Exception {
        // Map 的键与值均按完整的 Hessian 值读取。
        DubboHessianValueGuard.validate(bytes('H', 1, 'k', 0x91, 'Z'));
    }

    @Test
    public void acceptsTypedMap() throws Exception {
        // 类型声明不影响 Map 的键值配对与结束标记。
        DubboHessianValueGuard.validate(bytes('M', 1, 'x', 1, 'k', 0x91, 'Z'));
    }

    @Test
    public void rejectsMapWithMissingValue() {
        // Z 不能作为一个键缺失的值。
        byte[] document = bytes('H', 1, 'k', 'Z');
        IOException failure = assertThrows(IOException.class, () -> DubboHessianValueGuard.validate(document));
        assertTrue(failure.getMessage().contains("Invalid Hessian 2 value tag"));
    }

    @Test
    public void acceptsReferenceToPreviousTopLevelContainer() throws Exception {
        // Dubbo 的参数和附件可以共享一个对象引用表。
        DubboHessianValueGuard.validate(bytes(0x78, 'Q', 0x90));
    }

    @Test
    public void acceptsReferenceToCurrentContainer() throws Exception {
        // 容器在读取元素前登记引用，允许合法的自引用列表。
        DubboHessianValueGuard.validate(bytes(0x79, 'Q', 0x90));
    }

    @Test
    public void rejectsForwardObjectReference() {
        // 引用不能指向尚未出现的对象。
        byte[] document = bytes('Q', 0x90);
        IOException failure = assertThrows(IOException.class, () -> DubboHessianValueGuard.validate(document));
        assertTrue(failure.getMessage().contains("Invalid Hessian reference"));
    }

    @Test
    public void rejectsNegativeObjectReference() {
        // 已有引用也不能让负数索引合法化。
        byte[] document = bytes(0x78, 'Q', 0x8f);
        IOException failure = assertThrows(IOException.class, () -> DubboHessianValueGuard.validate(document));
        assertTrue(failure.getMessage().contains("Invalid Hessian reference"));
    }

    @Test
    public void acceptsObjectAndReusesClassDefinition() throws Exception {
        // 一次类定义可服务于后续两个顶层对象。
        byte[] document = bytes('C', 1, 'x', 0x91, 1, 'f', 0x60, 'N', 'O', 0x90, 'T');
        DubboHessianValueGuard.validate(document);
    }

    @Test
    public void rejectsObjectWithoutClassDefinition() {
        // 紧凑对象标签同样必须引用已知类定义。
        byte[] document = bytes(0x60);
        IOException failure = assertThrows(IOException.class, () -> DubboHessianValueGuard.validate(document));
        assertTrue(failure.getMessage().contains("Invalid Hessian reference"));
    }

    @Test
    public void rejectsClassDefinitionWithNonStringFieldName() {
        // 字段名不能由 null 或其他值类型代替。
        byte[] document = bytes('C', 1, 'x', 0x91, 'N', 0x60, 'N');
        IOException failure = assertThrows(IOException.class, () -> DubboHessianValueGuard.validate(document));
        assertTrue(failure.getMessage().contains("Expected Hessian string"));
    }

    @Test
    public void acceptsExactlySixtyFourNestedLists() throws Exception {
        // 深度上限内的合法值不能被提前拒绝。
        byte[] document = new byte[65];
        Arrays.fill(document, (byte) 0x79);
        document[64] = 'N';

        // 最内层的 null 正好位于第 64 层。
        DubboHessianValueGuard.validate(document);
    }

    @Test
    public void rejectsMoreThanSixtyFourNestedLists() {
        // 嵌套超过上限时，在进入原生 Hessian 递归前失败。
        byte[] document = new byte[66];
        Arrays.fill(document, (byte) 0x79);
        document[65] = 'N';

        // 检查错误来自明确的深度限制。
        IOException failure = assertThrows(IOException.class, () -> DubboHessianValueGuard.validate(document));
        assertTrue(failure.getMessage().contains("Hessian value nesting exceeds 64 levels"));
    }

    @Test
    public void rejectsTooManyConsecutiveClassDefinitions() {
        // 类定义之间的递归也占用深度额度，不能通过空类绕过限制。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int index = 0; index < 65; index++) {
            bytes.writeBytes(bytes('C', 1, 'x', 0x90));
        }
        bytes.write('N');
        byte[] document = bytes.toByteArray();

        // 连续第 65 次定义触发与值嵌套相同的上限。
        IOException failure = assertThrows(IOException.class, () -> DubboHessianValueGuard.validate(document));
        assertTrue(failure.getMessage().contains("Hessian value nesting exceeds 64 levels"));
    }

    @Test
    public void rejectsArrayTypeWithExcessiveDimensions() {
        // 即使列表没有元素，类型名也不能令数组类型工厂递归 65 层。
        byte[] name = ("[".repeat(65) + "I").getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes(bytes(0x70, 'S', 0, name.length));
        bytes.writeBytes(name);
        byte[] document = bytes.toByteArray();

        // 在类型真正交给原生反序列化器之前拒绝。
        IOException failure = assertThrows(IOException.class, () -> DubboHessianValueGuard.validate(document));
        assertTrue(failure.getMessage().contains("Hessian array type exceeds 64 dimensions"));
    }

    @Test
    public void rejectsArrayDimensionsSplitAcrossChunks() {
        // 维度可跨字符串块出现，总维数仍必须受限。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes(bytes(0x70, 'R', 0, 32));
        bytes.writeBytes("[".repeat(32).getBytes(StandardCharsets.UTF_8));
        bytes.writeBytes(bytes('S', 0, 34));
        bytes.writeBytes(("[".repeat(33) + "I").getBytes(StandardCharsets.UTF_8));
        byte[] document = bytes.toByteArray();

        // 分块不能绕过类型名整体检查。
        IOException failure = assertThrows(IOException.class, () -> DubboHessianValueGuard.validate(document));
        assertTrue(failure.getMessage().contains("Hessian array type exceeds 64 dimensions"));
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (byte) values[index];
        }
        return result;
    }
}
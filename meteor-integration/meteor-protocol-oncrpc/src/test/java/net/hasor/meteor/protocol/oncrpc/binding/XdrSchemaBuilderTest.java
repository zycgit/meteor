/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.binding;
import java.util.List;
import net.hasor.cobble.reflect.resolvable.ResolvableType;
import net.hasor.meteor.annotation.MetEnumValue;
import net.hasor.meteor.annotation.MetField;
import net.hasor.meteor.annotation.MetType;
import net.hasor.meteor.protocol.oncrpc.annotation.OncRpcProgram;
import net.hasor.meteor.protocol.oncrpc.codec.XdrCodec;
import net.hasor.meteor.protocol.oncrpc.codec.XdrInput;
import net.hasor.meteor.protocol.oncrpc.codec.XdrOutput;
import net.hasor.meteor.protocol.oncrpc.codec.XdrStructCodec;
import org.junit.Test;
import static org.junit.Assert.*;

/** 位置、编号和类型映射在发布时确定，测试不创建网络通道。 */
public class XdrSchemaBuilderTest {
    @Test
    public void structWriteSortsNoncontiguousFieldIdsInsteadOfJavaDeclarationOrder() {
        // Java 先声明 y，编号 9 的 x 先于编号 30 的 y；编号间隙不进入正文。
        XdrCodec<Object> codec = this.builder().codec(ResolvableType.forClass(Point.class));
        Point point = new Point();
        point.x = 7;
        point.y = 9;
        XdrOutput output = new XdrOutput(1024, 100, 16);

        // 编译后的字段顺序直接决定正文，不携带字段号或补齐编号间隙。
        codec.write(output, point);
        assertArrayEquals(new byte[] { 0, 0, 0, 7, 0, 0, 0, 9 }, output.toByteArray());
    }

    @Test
    public void structReadUsesTheSameNoncontiguousFieldIdOrder() {
        // 反向恢复不再扫描 Java 字段，使用同一份准备结果。
        XdrCodec<Object> codec = this.builder().codec(ResolvableType.forClass(Point.class));
        byte[] body = { 0, 0, 0, 7, 0, 0, 0, 9 };

        // 解码沿用发布时确定的字段顺序，恢复到相应 Java 属性。
        Point point = (Point) codec.read(new XdrInput(body, 1024, 100, 16));
        assertEquals(7, point.x);
        assertEquals(9, point.y);
    }

    @Test
    public void commonNamesAreRetainedInTheCompiledStruct() {
        // 类型名和成员名用于契约描述，实际线协议仍是按位置编码。
        XdrStructCodec codec = (XdrStructCodec) this.builder().codec(ResolvableType.forClass(Point.class));

        assertEquals("Coordinate", codec.name);
        assertEquals("first", codec.fields.get(0).name);
        assertEquals("x", codec.fields.get(0).field.getName());
    }

    @Test
    public void enumUsesExplicitNumericIdsInsteadOfOrdinals() {
        // 枚举声明顺序与协议编号不同，READ 不等于 ordinal 0。
        XdrCodec<Object> codec = this.builder().codec(ResolvableType.forClass(Mode.class));
        XdrOutput output = new XdrOutput(1024, 100, 16);

        codec.write(output, Mode.READ);

        assertArrayEquals(new byte[] { 0, 0, 0, 42 }, output.toByteArray());
    }

    @Test
    public void enumWithoutNumericDeclarationsIsNotInferred() {
        // 缺少编号时不能悄悄把 Java ordinal 变成契约。
        assertNull(this.builder().codec(ResolvableType.forClass(Unmapped.class)));
    }

    @Test
    public void annotatedMemberWithoutAFieldIdIsRejected() {
        // 只声明公共名称不会为自动生成的 XDR 推测顺序。
        XdrSchemaBuilder builder = this.builder();

        // 零表示未提供公共字段号，不能解释成第一个 XDR 成员。
        ResolvableType type = ResolvableType.forClass(MissingNumber.class);
        assertThrows(IllegalArgumentException.class, () -> builder.codec(type));
    }

    @Test
    public void negativeFieldIdIsRejectedForAutomaticStructs() {
        // 自动生成只接受正数编号，不能将负数排序成合法成员。
        XdrSchemaBuilder builder = this.builder();
        ResolvableType type = ResolvableType.forClass(NegativeNumber.class);
        assertThrows(IllegalArgumentException.class, () -> builder.codec(type));
    }

    @Test
    public void duplicateFieldIdsAreRejectedForAutomaticStructs() {
        // 两个不同属性使用相同编号时，没有唯一的生成顺序。
        XdrSchemaBuilder builder = this.builder();
        ResolvableType type = ResolvableType.forClass(DuplicateNumbers.class);
        assertThrows(IllegalArgumentException.class, () -> builder.codec(type));
    }

    @Test
    public void propertiesWithoutMetFieldDoNotEnterTheAutomaticStruct() {
        // 未标注的应用状态没有 XDR 映射，不能被反射声明顺序带入报文。
        XdrCodec<Object> codec = this.builder().codec(ResolvableType.forClass(PartiallyMapped.class));
        PartiallyMapped value = new PartiallyMapped();
        value.number = 23;
        value.local = new Object();
        XdrOutput output = new XdrOutput(1024, 100, 16);

        // 正文只包含显式编号的属性，不需要为 local 配置编码器。
        codec.write(output, value);
        assertArrayEquals(new byte[] { 0, 0, 0, 23 }, output.toByteArray());
    }

    @Test
    public void getterOnlyDeclarationUsesTheBeanAccessors() {
        // 公共注解只放 getter，DTO 的存储字段不参与契约。
        XdrCodec<Object> codec = this.builder().codec(ResolvableType.forClass(GetterMapped.class));
        GetterMapped value = new GetterMapped();
        value.setValue(19);
        XdrOutput output = new XdrOutput(1024, 100, 16);
        codec.write(output, value);

        // 相同准备结果通过 setter 恢复值，getter 与字段写法语义一致。
        GetterMapped restored = (GetterMapped) codec.read(new XdrInput(output.toByteArray(), 1024, 100, 16));
        assertEquals(19, restored.getValue());
        assertArrayEquals(new byte[] { 0, 0, 0, 19 }, output.toByteArray());
    }

    @Test
    public void getterNameAndFieldIdMergeIntoOneXdrMember() {
        // 字段提供编号，getter 提供公共名称；公共解析器合并同一属性。
        XdrStructCodec codec = (XdrStructCodec) this.builder().codec(ResolvableType.forClass(MergedProperty.class));

        // 协议复用合并后的属性，不把字段与 getter 当作两个成员。
        assertEquals(1, codec.fields.size());
        assertEquals("wireValue", codec.fields.get(0).name);
    }

    @Test
    public void conflictingFieldAndGetterIdsAreRejectedByCommonMetadata() {
        // 同一属性声明两个不同编号，公共元信息阶段就应报告冲突。
        XdrSchemaBuilder builder = this.builder();
        ResolvableType type = ResolvableType.forClass(ConflictingIds.class);
        assertThrows(IllegalArgumentException.class, () -> builder.codec(type));
    }

    @Test
    public void conflictingFieldAndGetterNamesAreRejectedByCommonMetadata() {
        // 编号一致也不能让两个不同公共名称静默覆盖。
        XdrSchemaBuilder builder = this.builder();
        ResolvableType type = ResolvableType.forClass(ConflictingNames.class);
        assertThrows(IllegalArgumentException.class, () -> builder.codec(type));
    }

    @Test
    public void schemaCompilationDoesNotInstantiateTheDto() {
        // 构造方法只会在实际读取报文时使用。
        XdrCodec<Object> codec = this.builder().codec(ResolvableType.forClass(Explosive.class));

        assertNotNull(codec);
    }

    @Test
    public void recursiveContainersReuseTheSamePreparedStruct() {
        // List<Node> 可通过空列表终止，编译阶段不能无限递归。
        XdrCodec<Object> codec = this.builder().codec(ResolvableType.forClass(Node.class));
        Node node = new Node();
        node.children = List.of();
        XdrOutput output = new XdrOutput(1024, 100, 16);

        codec.write(output, node);

        assertArrayEquals(new byte[] { 0, 0, 0, 0 }, output.toByteArray());
    }

    private XdrSchemaBuilder builder() {
        return new XdrSchemaBuilder(new OncRpcServiceResource(Contract.class), 1024, 100);
    }

    @OncRpcProgram(id = 0x20000001)
    public interface Contract {
    }

    @MetType(name = "Coordinate")
    public static class Point {
        @MetField(id = 30)
        private int y;
        @MetField(name = "first", id = 9)
        private int x;
    }

    public enum Mode {
        @MetEnumValue(id = 42) READ,
        @MetEnumValue(id = 7) WRITE
    }

    public enum Unmapped {
        VALUE
    }

    public static class MissingNumber {
        @MetField(name = "value")
        private int value;
    }

    public static class NegativeNumber {
        @MetField(id = -3)
        private int value;
    }

    public static class DuplicateNumbers {
        @MetField(id = 7)
        private int first;
        @MetField(id = 7)
        private int second;
    }

    public static class PartiallyMapped {
        private Object local;
        @MetField(id = 17)
        private int    number;
    }

    public static class GetterMapped {
        private int storage;

        @MetField(id = 17)
        public int getValue() {
            return this.storage;
        }

        public void setValue(int value) {
            this.storage = value;
        }
    }

    public static class MergedProperty {
        @MetField(id = 7)
        private int value;

        @MetField(name = "wireValue")
        public int getValue() {
            return this.value;
        }

        public void setValue(int value) {
            this.value = value;
        }
    }

    public static class ConflictingIds {
        @MetField(id = 3)
        private int value;

        @MetField(id = 7)
        public int getValue() {
            return this.value;
        }

        public void setValue(int value) {
            this.value = value;
        }
    }

    public static class ConflictingNames {
        @MetField(name = "first", id = 3)
        private int value;

        @MetField(name = "second", id = 3)
        public int getValue() {
            return this.value;
        }

        public void setValue(int value) {
            this.value = value;
        }
    }

    public static class Explosive {
        @MetField(id = 1)
        private int value;

        public Explosive() {
            throw new AssertionError("Must not instantiate during schema compilation");
        }
    }

    public static class Node {
        @MetField(id = 1)
        private List<Node> children;
    }
}

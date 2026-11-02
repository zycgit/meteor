/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.codec;
import java.math.BigDecimal;
import net.hasor.meteor.annotation.MetField;
import java.nio.ByteBuffer;
import java.util.*;
import net.hasor.cobble.function.EConsumer;
import net.hasor.cobble.reflect.resolvable.ResolvableType;
import net.hasor.example.protocol.thrift.mapping.Note;
import net.hasor.example.protocol.thrift.mapping.NoteService;
import net.hasor.meteor.annotation.MetParam;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.protocol.thrift.ThriftIdlResource;
import net.hasor.example.protocol.thrift.mapping.MappedBean;
import net.hasor.example.protocol.thrift.mapping.MappedState;
import net.hasor.example.protocol.thrift.mapping.FailingNote;
import net.hasor.meteor.protocol.thrift.annotation.*;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.*;
import org.apache.thrift.transport.TMemoryInputTransport;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ThriftValueCodecTest {
    private final ThriftWireCodec wire = new ThriftWireCodec(1024 * 1024, 1000, 32);

    @Test
    public void binaryPreservesScalarAndContainerValues() throws Exception {
        // 普通 DTO 覆盖 Thrift 原生标量、binary、enum、UUID 和容器。
        Values source = this.values();
        Values result = (Values) this.roundTrip("binary", this.type(Values.class), source);

        // 一次 DTO 往返保持其完整契约。
        this.assertValues(source, result);
    }

    @Test
    public void compactPreservesScalarAndContainerValues() throws Exception {
        // 相同字段定义交给官方 Compact 编码，不另建 DTO 编码分支。
        Values source = this.values();
        Values result = (Values) this.roundTrip("compact", this.type(Values.class), source);

        // Compact 的布尔字段和紧凑容器头不改变普通 DTO 语义。
        this.assertValues(source, result);
    }

    @Test
    public void jsonPreservesScalarAndContainerValues() throws Exception {
        // 这里使用 Thrift JSON，不是 Meteor 通用 JSON 序列化器。
        Values source = this.values();
        Values result = (Values) this.roundTrip("json", this.type(Values.class), source);

        // JSON 的 binary 与 map 表达仍回到声明的 Java 类型。
        this.assertValues(source, result);
    }

    @Test
    public void negativeFloatZeroKeepsItsSign() throws Exception {
        // float 提升到 double 再恢复时保留 IEEE 754 的负零。
        Float result = (Float) this.roundTrip("binary", this.type(float.class), -0.0f);

        assertEquals(Float.floatToRawIntBits(-0.0f), Float.floatToRawIntBits(result));
    }

    @Test
    public void floatNaNRetainsItsNonNumericSemantics() throws Exception {
        // 标准 Thrift double 允许 NaN，Java float 声明也保留该语义。
        Float result = (Float) this.roundTrip("compact", this.type(Float.class), Float.NaN);

        assertTrue(Float.isNaN(result));
    }

    @Test
    public void floatInfinityDoesNotBecomeAFiniteValue() throws Exception {
        // 无限值在 float 和 double 中都能表示，不作为数值溢出拒绝。
        Object result = this.roundTrip("binary", this.type(float.class), Float.POSITIVE_INFINITY);

        assertEquals(Float.POSITIVE_INFINITY, result);
    }

    @Test
    public void doublePrecisionLossIsRejectedForAFloatDeclaration() {
        // 对端的 double 有效值不能被静默舍入到本地 float。
        ThriftType type = this.type(float.class);
        assertThrows(TProtocolException.class, () -> this.readBody(type, output -> output.writeDouble(1.1d)));
    }

    @Test
    public void doubleOverflowIsRejectedForAFloatDeclaration() {
        // 有限 double 不能被静默改成无限 float。
        ThriftType type = this.type(Float.class);
        assertThrows(TProtocolException.class, //
                () -> this.readBody(type, output -> output.writeDouble(Double.MAX_VALUE)));
    }

    @Test
    public void doubleUnderflowIsRejectedForAFloatDeclaration() {
        // double 的非零最小值不能被静默改成 float 的零。
        ThriftType type = this.type(float.class);
        assertThrows(TProtocolException.class, //
                () -> this.readBody(type, output -> output.writeDouble(Double.MIN_VALUE)));
    }

    @Test
    public void optionalFloatAbsentStaysNull() throws Exception {
        // 包装字段缺失仍按原有 optional 契约处理。
        Values source = this.values();
        source.boxedRatio = null;
        Values result = (Values) this.roundTrip("compact", this.type(Values.class), source);

        assertNull(result.boxedRatio);
    }

    @Test
    public void nulCharacterRemainsAOneCharacterString() throws Exception {
        // NUL 是合法 UTF-8 字符，不应与空串或缺失字段混淆。
        Object result = this.roundTrip("binary", this.type(char.class), '\0');

        assertEquals(Character.valueOf('\0'), result);
    }

    @Test
    public void optionalCharacterAbsentStaysNull() throws Exception {
        // Character 的 null 由 optional 字段承载，不写成空字符串。
        Values source = this.values();
        source.boxedLetter = null;
        Values result = (Values) this.roundTrip("compact", this.type(Values.class), source);

        assertNull(result.boxedLetter);
    }

    @Test
    public void emptyStringIsRejectedForACharacterDeclaration() {
        // 对端空串没有可恢复的 Java 字符。
        ThriftType type = this.type(char.class);
        assertThrows(TProtocolException.class, () -> this.readBody(type, output -> output.writeString("")));
    }

    @Test
    public void multipleCharactersAreRejectedForACharacterDeclaration() {
        // 不通过截断对端文本制造一个字符。
        ThriftType type = this.type(Character.class);
        assertThrows(TProtocolException.class, () -> this.readBody(type, output -> output.writeString("ab")));
    }

    @Test
    public void supplementaryCodePointIsRejectedForACharacterDeclaration() {
        // 一个补充平面码点占两个 UTF-16 单元，不能装进单个 Character。
        ThriftType type = this.type(char.class);
        assertThrows(TProtocolException.class, () -> this.readBody(type, output -> output.writeString("🙂")));
    }

    @Test
    public void unpairedSurrogateIsRejectedBeforeUtf8Replacement() {
        // 单个代理项不能编码成标准 UTF-8，写出前报错以免被替换为问号。
        ThriftType type = this.type(Character.class);
        assertThrows(TProtocolException.class, () -> this.roundTrip("binary", type, '\ud800'));
    }

    @Test
    public void ordinaryStringStillAllowsEmptyText() throws Exception {
        // Character 的长度约束不影响已有 String 字段。
        Object result = this.roundTrip("binary", this.type(String.class), "");

        assertEquals("", result);
    }

    @Test
    public void ordinaryStringStillAllowsSupplementaryCodePoints() throws Exception {
        // 完整字符串继续保留中文和补充平面字符。
        Object result = this.roundTrip("compact", this.type(String.class), "中文🙂");

        assertEquals("中文🙂", result);
    }

    @Test
    public void doubleObjectCannotBePassedAsADeclaredFloat() {
        // 保留声明类型检查，不用通用 Number 转换掩盖错误的调用参数。
        ThriftType type = this.type(Float.class);
        assertThrows(TException.class, () -> this.roundTrip("binary", type, 1.5d));
    }

    @Test
    public void stringObjectCannotBePassedAsADeclaredCharacter() {
        // 相同线协议类型不能代替真实 Java 字段类型校验。
        ThriftType type = this.type(Character.class);
        assertThrows(TException.class, () -> this.roundTrip("binary", type, "中"));
    }

    @Test
    public void idlDoubleDefaultRestoresADeclaredFloat() throws Exception {
        // 绑定已有 IDL 的默认值也遵循同一套 Java 标量映射。
        Object result = ThriftValueCodec.defaultValue(this.type(Float.class), 1.5d);

        assertEquals(Float.valueOf(1.5f), result);
    }

    @Test
    public void idlStringDefaultRestoresADeclaredCharacter() throws Exception {
        // 默认文本根据真实 Java 声明恢复字符，不写入 String 到 Character 字段。
        Object result = ThriftValueCodec.defaultValue(this.type(Character.class), "中");

        assertEquals(Character.valueOf('中'), result);
    }

    @Test
    public void idlFiniteDefaultCannotOverflowToFloatInfinity() {
        // IDL 十进制常量本身有限，不能在默认值准备期间变成无限 float。
        ThriftType type = this.type(Float.class);
        BigDecimal declared = new BigDecimal("1e1000");

        assertThrows(TProtocolException.class, () -> ThriftValueCodec.defaultValue(type, declared));
    }

    @Test
    public void idlPositiveDefaultCannotUnderflowToFloatZero() {
        // 非零常量先转换为 double 就已下溢，不能再把零当成可精确表示的 float。
        ThriftType type = this.type(Float.class);
        BigDecimal declared = new BigDecimal("1e-1000");

        assertThrows(TProtocolException.class, () -> ThriftValueCodec.defaultValue(type, declared));
    }

    @Test
    public void idlNegativeDefaultCannotUnderflowToFloatZero() {
        // 极小负常量也必须明确拒绝，不能静默丢失非零大小。
        ThriftType type = this.type(float.class);
        BigDecimal declared = new BigDecimal("-1e-1000");

        assertThrows(TProtocolException.class, () -> ThriftValueCodec.defaultValue(type, declared));
    }

    @Test
    public void idlZeroDefaultRemainsAValidFloat() throws Exception {
        // 真正的十进制零不属于下溢，继续按声明恢复 float 包装值。
        Object result = ThriftValueCodec.defaultValue(this.type(Float.class), BigDecimal.ZERO);

        assertEquals(Float.valueOf(0), result);
    }

    @Test
    public void nestedPrimitiveArraysRetainTheirDeclaredShape() throws Exception {
        // 嵌套 list 还原为真正的 int[][]，空子数组不与 null 混淆。
        int[][] source = { { Integer.MIN_VALUE, 0, Integer.MAX_VALUE }, {}, { -7 } };
        int[][] result = (int[][]) this.roundTrip("compact", this.type(int[][].class), source);

        assertTrue(Arrays.deepEquals(source, result));
    }

    @Test
    public void emptyObjectArrayRetainsItsComponentType() throws Exception {
        // 空数组仍由发布时的 String 声明确定类型，不能退化为 Object[] 或 List。
        String[] result = (String[]) this.roundTrip("binary", this.type(String[].class), new String[0]);

        assertEquals(String[].class, result.getClass());
        assertEquals(0, result.length);
    }

    @Test
    public void nestedByteArraysPreserveBinaryContents() throws Exception {
        // 每个 byte[] 走 binary，外层 list 保留顺序和空 binary。
        byte[][] source = { { Byte.MIN_VALUE, 0, Byte.MAX_VALUE }, {}, { 1, -1 } };
        byte[][] result = (byte[][]) this.roundTrip("binary", this.type(byte[][].class), source);

        assertTrue(Arrays.deepEquals(source, result));
    }

    @Test
    public void floatArrayRetainsSmallAndLargeValues() throws Exception {
        // 数组元素复用 float 到 double 的无损标量映射。
        float[] source = { Float.MIN_VALUE, -Float.MAX_VALUE, -0.0f };
        float[] result = (float[]) this.roundTrip("json", this.type(float[].class), source);

        assertArrayEquals(source, result, 0);
    }

    @Test
    public void nullObjectArrayElementIsRejected() {
        // 标准 Thrift list 不包含单个元素的 null 标记，不能静默删除该位置。
        String[] source = { "value", null };

        assertThrows(TProtocolException.class, () -> this.roundTrip("binary", this.type(String[].class), source));
    }

    @Test
    public void wrongWireElementTypeIsRejectedForAnArray() {
        // Java int[] 不能接收声明为 string 的线协议 list。
        ThriftType type = this.type(int[].class);
        assertThrows(TProtocolException.class, () -> this.readBody(type, output -> {
            output.writeListBegin(new TList(TType.STRING, 1));
            output.writeString("7");
            output.writeListEnd();
        }));
    }

    @Test
    public void idlListDefaultCreatesAnIndependentPrimitiveArray() throws Exception {
        // 已有 IDL 的 list 常量可绑定到 int[]，每个 DTO 获得自己的数组。
        ThriftType type = this.type(int[].class);
        int[] first = (int[]) ThriftValueCodec.defaultValue(type, List.of(1, 2));
        int[] second = (int[]) ThriftValueCodec.defaultValue(type, List.of(1, 2));
        first[0] = 9;

        assertArrayEquals(new int[] { 1, 2 }, second);
    }

    @Test
    public void optionalAbsentStaysNull() throws Exception {
        // 空包装值表示未设置，而不是数字零。
        Note source = new Note();
        Note result = (Note) this.roundTrip("compact", this.type(Note.class), source);

        // primitive 必填 id 仍允许合法值 0。
        assertEquals(0, result.getId());
        assertNull(result.getPriority());
    }

    @Test
    public void optionalZeroStaysPresent() throws Exception {
        // optional 包装值显式设置为零。
        Note source = new Note();
        source.setPriority(0);
        Note result = (Note) this.roundTrip("compact", this.type(Note.class), source);

        // present zero 不能被省略成 null。
        assertEquals(Integer.valueOf(0), result.getPriority());
    }

    @Test
    public void recursiveValuesUseTheDeclaredNestedType() throws Exception {
        // 同一个 DTO 定义在多层 map/list 中递归使用。
        Note child = new Note();
        child.setId(11);
        Note source = new Note();
        source.setChildren(Map.of("children", List.of(child)));
        Note result = (Note) this.roundTrip("binary", this.type(Note.class), source);

        // 递归类型不用 Java 类名写入报文。
        assertEquals(11, result.getChildren().get("children").get(0).getId());
    }

    @Test
    public void beanAccessorsAreUsedForBothDirections() throws Exception {
        // 只有 getter 带注解，反向通过对应 setter 赋值。
        MappedBean source = new MappedBean();
        source.setValue("Bean 中文");
        ThriftType type = this.type(MappedBean.class);
        Object decoded = this.roundTrip("binary", type, source);

        // 得到独立普通 Bean。
        assertEquals("Bean 中文", ((MappedBean) decoded).getValue());
    }

    @Test
    public void fieldIdsIgnoreDtoDeclarationOrder() throws Exception {
        // 对端先发 id，再发 text，顺序与本地 Note 声明相反。
        Note result = (Note) this.readBody(this.type(Note.class), output -> {
            output.writeStructBegin(new TStruct("Note"));
            output.writeFieldBegin(new TField("id", TType.I64, (short) 1));
            output.writeI64(19);
            output.writeFieldEnd();
            output.writeFieldBegin(new TField("text", TType.STRING, (short) 7));
            output.writeString("numbered");
            output.writeFieldEnd();
            output.writeFieldStop();
            output.writeStructEnd();
        });

        // 身份由编号决定，不能依赖遍历位置。
        assertEquals(19, result.getId());
        assertEquals("numbered", result.getText());
    }

    @Test
    public void unknownFieldIsSkipped() throws Exception {
        // 新版对端增加本地尚未认识的字段。
        Plain result = (Plain) this.readBody(this.type(Plain.class), output -> {
            output.writeStructBegin(new TStruct("Plain"));
            output.writeFieldBegin(new TField("future", TType.I32, (short) 99));
            output.writeI32(17);
            output.writeFieldEnd();
            output.writeFieldBegin(new TField("text", TType.STRING, (short) 1));
            output.writeString("known");
            output.writeFieldEnd();
            output.writeFieldStop();
            output.writeStructEnd();
        });

        // 跳过未知字段后仍能读取后续字段。
        assertEquals("known", result.text);
    }

    @Test
    public void mismatchedOptionalFieldTypeIsSkipped() throws Exception {
        // 同一编号携带不匹配类型，按 Thrift 规则跳过。
        Plain result = (Plain) this.readBody(this.type(Plain.class), output -> {
            output.writeStructBegin(new TStruct("Plain"));
            output.writeFieldBegin(new TField("text", TType.I32, (short) 1));
            output.writeI32(17);
            output.writeFieldEnd();
            output.writeFieldStop();
            output.writeStructEnd();
        });

        // 没有把整数强转或转成字符串。
        assertNull(result.text);
    }

    @Test
    public void missingRequiredFieldIsRejectedDespiteJavaDefault() throws Exception {
        // Note.id 构造后为 0，不能用默认值掩盖报文缺失。
        ThriftType type = this.idlType(NoteService.class, "NoteService");
        assertThrows(TProtocolException.class, () -> this.readBody(type, output -> {
            output.writeStructBegin(new TStruct("Note"));
            output.writeFieldStop();
            output.writeStructEnd();
        }));
    }

    @Test
    public void nullRequiredReferenceIsRejectedOnWrite() throws Exception {
        // 必填引用未设置时拒绝生成不完整报文。
        ThriftType type = this.idlType(RequiredTextService.class, "RequiredTextService");
        assertThrows(TProtocolException.class, () -> this.roundTrip("binary", type, new RequiredText()));
    }

    @Test
    public void unionKeepsOnlyItsSelectedBranch() throws Exception {
        // 普通 nullable 字段表达 union 的选择状态。
        Choice source = new Choice();
        source.count = Long.MAX_VALUE;
        Choice result = (Choice) this.roundTrip("compact", this.type(Choice.class), source);

        // 未选择分支保持 null。
        assertNull(result.text);
        assertEquals(source.count, result.count);
    }

    @Test
    public void unionWithTwoBranchesIsRejectedOnWrite() {
        // 设置两个分支不属于合法 union。
        Choice source = new Choice();
        source.text = "text";
        source.count = 1L;
        assertThrows(TProtocolException.class, () -> this.roundTrip("binary", this.type(Choice.class), source));
    }

    @Test
    public void emptyUnionIsRejectedOnWrite() {
        // 没有选择任何分支时不能输出 union。
        assertThrows(TProtocolException.class, //
                () -> this.roundTrip("binary", this.type(Choice.class), new Choice()));
    }

    @Test
    public void unionWithTwoWireBranchesIsRejectedOnRead() {
        // 不信任对端也遵循只选择一个分支的约定。
        assertThrows(TProtocolException.class, () -> this.readBody(this.type(Choice.class), output -> {
            output.writeStructBegin(new TStruct("Choice"));
            output.writeFieldBegin(new TField("text", TType.STRING, (short) 1));
            output.writeString("text");
            output.writeFieldEnd();
            output.writeFieldBegin(new TField("count", TType.I64, (short) 2));
            output.writeI64(1);
            output.writeFieldEnd();
            output.writeFieldStop();
            output.writeStructEnd();
        }));
    }

    @Test
    public void emptyWireUnionIsRejectedOnRead() {
        // 一个空 struct 不能作为合法 union 交付业务。
        assertThrows(TProtocolException.class, () -> this.readBody(this.type(Choice.class), output -> {
            output.writeStructBegin(new TStruct("Choice"));
            output.writeFieldStop();
            output.writeStructEnd();
        }));
    }

    @Test
    public void listWithTheWrongElementTypeIsRejected() {
        // 容器本身类型正确，但对端元素类型与 List<String> 声明不同。
        ThriftType list = this.type(Values.class).fieldsById.get((short) 9).type;
        assertThrows(TProtocolException.class, () -> this.readBody(list, output -> {
            output.writeListBegin(new TList(TType.I32, 1));
            output.writeI32(7);
            output.writeListEnd();
        }));
    }

    @Test
    public void mapWithTheWrongKeyTypeIsRejected() {
        // Map<String, List<Integer>> 不能把整数 key 猜成字符串。
        ThriftType map = this.type(Values.class).fieldsById.get((short) 11).type;
        assertThrows(TProtocolException.class, () -> this.readBody(map, output -> {
            output.writeMapBegin(new TMap(TType.I32, TType.LIST, 1));
            output.writeI32(7);
            output.writeListBegin(new TList(TType.I32, 0));
            output.writeListEnd();
            output.writeMapEnd();
        }));
    }

    @Test
    public void incompatibleRuntimeValueIsReportedAsAThriftFailure() {
        // 擦除后的参数调用仍可能提供与声明标量不同的对象。
        ThriftType type = this.type(Long.class);
        assertThrows(TException.class, () -> this.roundTrip("binary", type, "not a long"));
    }

    @Test
    public void dtoConstructorFailureIsReportedWhenReadingTheValue() {
        // 发布只编译构造入口，真正读取报文时才创建 DTO。
        ThriftType type = this.type(FailingNote.class);
        assertThrows(TException.class, () -> this.readBody(type, output -> {
            output.writeStructBegin(new TStruct("Failure"));
            output.writeFieldStop();
            output.writeStructEnd();
        }));
    }

    @Test
    public void unknownEnumNumberDoesNotGuessAnOrdinal() throws Exception {
        // 对端发来本地未声明的枚举值。
        ThriftType type = this.type(MappedState.class);
        Object result = this.readBody(type, output -> output.writeI32(42));

        // 与官方 findByValue 一样返回未识别状态。
        assertNull(result);
    }

    @Test
    public void binaryBufferPositionIsNotConsumed() throws Exception {
        // 只传输 position 到 limit 的字节。
        ByteBuffer source = ByteBuffer.wrap(new byte[] { 9, 1, 2, 8 });
        source.position(1);
        source.limit(3);
        Object result = this.roundTrip("binary", this.type(ByteBuffer.class), source);

        // 编码不修改调用方持有的缓冲区。
        assertEquals(ByteBuffer.wrap(new byte[] { 1, 2 }), result);
        assertEquals(1, source.position());
        assertEquals(3, source.limit());
    }

    @Test
    public void nullContainerElementIsRejected() {
        // Thrift list 的元素没有单独的 null 标记。
        Values source = this.values();
        source.names = Arrays.asList("valid", null);
        assertThrows(TProtocolException.class, () -> this.roundTrip("binary", this.type(Values.class), source));
    }

    @Test
    public void recursionStillHonorsTheExistingDepthLimit() {
        // 非法循环对象图不应无限递归或造成栈溢出。
        Note source = new Note();
        source.setChildren(Map.of("loop", List.of(source)));
        TProtocolException failure = assertThrows(TProtocolException.class, //
                () -> this.roundTrip("binary", this.type(Note.class), source));

        // 两种契约来源使用相同的有界 TProtocol。
        assertEquals(TProtocolException.DEPTH_LIMIT, failure.getType());
    }

    private Object roundTrip(String encoding, ThriftType type, Object source) throws Exception {
        byte[] bytes = this.wire.encodeBody(encoding, "value", TMessageType.REPLY, 1, //
                output -> ThriftValueCodec.write(output, type, source));
        return this.decodeBody(encoding, type, bytes);
    }

    private Object readBody(ThriftType type, EConsumer<TProtocol, TException> writer) throws Exception {
        byte[] bytes = this.wire.encodeBody("binary", "value", TMessageType.REPLY, 1, writer);
        return this.decodeBody("binary", type, bytes);
    }

    private Object decodeBody(String encoding, ThriftType type, byte[] bytes) throws Exception {
        TProtocol input = this.wire.createProtocol(encoding, new TMemoryInputTransport(bytes));
        input.readMessageBegin();
        Object value = ThriftValueCodec.read(input, type);
        input.readMessageEnd();
        return value;
    }

    private ThriftType type(Class<?> type) {
        return new ThriftSchemaBuilder().javaType(ResolvableType.forClass(type));
    }

    private ThriftType idlType(Class<?> contract, String name) throws Exception {
        ThriftIdlResource resource = new ThriftIdlResource(this.getClass().getResource("/thrift/mapping.thrift"));
        ThriftServiceSchema schema = resource.selectService(name).bind(new ServiceDomain<>(contract));
        return schema.javaMethod("echo").args.fields.get(0).type;
    }

    private Values values() {
        Values value = new Values();
        value.enabled = true;
        value.tiny = -17;
        value.small = -1234;
        value.count = -123456;
        value.id = Long.MAX_VALUE;
        value.ratio = 42.5;
        value.text = "中文🙂";
        value.binary = new byte[] { 0, 1, -1 };
        value.names = Arrays.asList("alpha", "");
        value.codes = new LinkedHashSet<>(Arrays.asList(7L, -9L));
        value.labels = Map.of("nested", List.of(1, -2));
        value.state = MappedState.FIRST;
        value.uuid = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
        value.precision = Float.MIN_VALUE;
        value.boxedRatio = -Float.MAX_VALUE;
        value.letter = '中';
        value.boxedLetter = '文';
        value.counts = new int[] { Integer.MIN_VALUE, 0, Integer.MAX_VALUE };
        value.texts = new String[] { "", "中文", "🙂" };
        return value;
    }

    private void assertValues(Values expected, Values actual) {
        assertEquals(expected.enabled, actual.enabled);
        assertEquals(expected.tiny, actual.tiny);
        assertEquals(expected.small, actual.small);
        assertEquals(expected.count, actual.count);
        assertEquals(expected.id, actual.id);
        assertEquals(expected.ratio, actual.ratio, 0);
        assertEquals(expected.text, actual.text);
        assertArrayEquals(expected.binary, actual.binary);
        assertEquals(expected.names, actual.names);
        assertEquals(expected.codes, actual.codes);
        assertEquals(expected.labels, actual.labels);
        assertEquals(expected.state, actual.state);
        assertEquals(expected.uuid, actual.uuid);
        assertEquals(expected.precision, actual.precision, 0);
        assertEquals(expected.boxedRatio, actual.boxedRatio);
        assertEquals(expected.letter, actual.letter);
        assertEquals(expected.boxedLetter, actual.boxedLetter);
        assertArrayEquals(expected.counts, actual.counts);
        assertArrayEquals(expected.texts, actual.texts);
    }

    public static class Values {
        @MetField(id = 1)
        private boolean                    enabled;
        @MetField(id = 2)
        private byte                       tiny;
        @MetField(id = 3)
        private short                      small;
        @MetField(id = 4)
        private int                        count;
        @MetField(id = 5)
        private long                       id;
        @MetField(id = 6)
        private double                     ratio;
        @MetField(id = 7)
        private String                     text;
        @MetField(id = 8)
        private byte[]                     binary;
        @MetField(id = 9)
        private List<String>               names;
        @MetField(id = 10)
        private Set<Long>                  codes;
        @MetField(id = 11)
        private Map<String, List<Integer>> labels;
        @MetField(id = 12)
        private MappedState                state;
        @MetField(id = 13)
        private UUID                       uuid;
        @MetField(id = 14)
        private float                      precision;
        @MetField(id = 15)
        private Float                      boxedRatio;
        @MetField(id = 16)
        private char                       letter;
        @MetField(id = 17)
        private Character                  boxedLetter;
        @MetField(id = 18)
        private int[]                      counts;
        @MetField(id = 19)
        private String[]                   texts;
    }

    public static class Plain {
        @MetField(id = 1)
        private String text;
    }

    public static class RequiredText {
        @MetField(id = 1)
        private String text;
    }

    public interface RequiredTextService {
        RequiredText echo(@MetParam(id = 1) RequiredText value);
    }

    @ThriftUnion
    public static class Choice {
        @MetField(id = 1)
        private String text;
        @MetField(id = 2)
        private Long   count;
    }
}

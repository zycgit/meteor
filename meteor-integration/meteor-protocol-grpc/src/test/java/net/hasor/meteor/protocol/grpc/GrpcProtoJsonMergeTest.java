/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import com.google.protobuf.Any;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MessageOptions;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.util.JsonFormat;
import org.junit.Test;
import static org.junit.Assert.*;

/** Protobuf JSON 合并保留 double 符号位，同时沿用官方解析器的契约校验。 */
public class GrpcProtoJsonMergeTest {
    private static final FileDescriptor          FILE        = fixture();
    private static final Descriptor              ROOT        = FILE.findMessageTypeByName("Root");
    private static final Descriptor              LEAF        = FILE.findMessageTypeByName("Leaf");
    private static final Descriptor              TAGGED_LEAF = FILE.findMessageTypeByName("TaggedLeaf");
    private static final JsonFormat.TypeRegistry REGISTRY    = JsonFormat.TypeRegistry.newBuilder() //
            .add(FILE.getMessageTypes()).add(DoubleValue.getDescriptor()).build();

    @Test
    public void proto3ScalarNegativeZeroRetainsItsRawBitsOnTheWire() throws Exception {
        // proto3 标量的负零不能在 JSON 合并时被默认值折叠。
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(ROOT);
        GrpcProtoSchema.mergeJson("{\"value\":-0.0}", builder, REGISTRY);

        // 既检查合并结果，也独立读取真正写出的 Protobuf 二进制。
        assertNegativeZero(builder.getField(ROOT.findFieldByName("value")));
        DynamicMessage wire = roundTrip(builder.build());
        assertNegativeZero(wire.getField(ROOT.findFieldByName("value")));
        assertTrue(wire.hasField(ROOT.findFieldByName("value")));
    }

    @Test
    public void fractionalStringNegativeZeroRetainsItsRawBits() throws Exception {
        // 官方 double 解析器接受不含整数部分的字符串数值。
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(ROOT);
        GrpcProtoSchema.mergeJson("{\"value\":\"-.0\"}", builder, REGISTRY);

        // 恢复依据实际 double 值，不能只识别固定的 -0.0 文本。
        DynamicMessage wire = roundTrip(builder.build());
        assertNegativeZero(wire.getField(ROOT.findFieldByName("value")));
    }

    @Test
    public void escapedMinusInJsonStringPreservesNegativeZero() throws Exception {
        // 实际 JSON 通过 Unicode 转义表达负号，原始输入中没有减号字符。
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(ROOT);
        GrpcProtoSchema.mergeJson("{\"value\":\"\\u002d0.0\"}", builder, REGISTRY);

        // JSON 解码后的负零仍需写入真实 Protobuf 二进制。
        DynamicMessage wire = roundTrip(builder.build());
        assertNegativeZero(wire.getField(ROOT.findFieldByName("value")));
    }

    @Test
    public void escapedZeroInJsonStringPreservesNegativeZero() throws Exception {
        // 实际 JSON 保留负号，只把首个数字零写成 Unicode 转义。
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(ROOT);
        GrpcProtoSchema.mergeJson("{\"value\":\"-\\u0030.0\"}", builder, REGISTRY);

        // 输入文本没有连续的 -0，解码后的 double 符号位仍被保留。
        DynamicMessage wire = roundTrip(builder.build());
        assertNegativeZero(wire.getField(ROOT.findFieldByName("value")));
    }

    @Test
    public void unicodeDigitPositiveZeroRemainsAcceptedBesideNegativeZero() throws Exception {
        // 官方解析器接受 Unicode 数字零，另一个负零字段触发后续恢复检查。
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(ROOT);
        GrpcProtoSchema.mergeJson("{\"value\":\"\\u0660\",\"custom_value\":-0.0}", builder, REGISTRY);

        // 正零不被重新交给仅接受 ASCII 数字的 double 解析器。
        DynamicMessage wire = roundTrip(builder.build());
        Double value = (Double) wire.getField(ROOT.findFieldByName("value"));
        assertEquals(0L, Double.doubleToRawLongBits(value));
        assertNegativeZero(wire.getField(ROOT.findFieldByName("custom_value")));
    }

    @Test
    public void unicodeDigitNegativeZeroPreservesItsRawBits() throws Exception {
        // 负号后的零使用 Unicode 数字，数值仍然是合法的负零。
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(ROOT);
        GrpcProtoSchema.mergeJson("{\"value\":\"-\\u0660\"}", builder, REGISTRY);

        // 恢复依据已接受的零值及符号，不限制数字的文本字符集。
        DynamicMessage wire = roundTrip(builder.build());
        assertNegativeZero(wire.getField(ROOT.findFieldByName("value")));
    }

    @Test
    public void nestedMessageNegativeZeroRetainsItsRawBitsOnTheWire() throws Exception {
        // 子消息中的 double 使用子描述符定位，不按 JSON 路径猜测字段。
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(ROOT);
        GrpcProtoSchema.mergeJson("{\"child\":{\"value\":-0.0}}", builder, REGISTRY);

        // 子消息经过完整二进制往返后仍保留负零。
        DynamicMessage wire = roundTrip(builder.build());
        DynamicMessage child = (DynamicMessage) wire.getField(ROOT.findFieldByName("child"));
        assertNegativeZero(child.getField(LEAF.findFieldByName("value")));
    }

    @Test
    public void repeatedDoubleNegativeZeroPreservesItsElementPosition() throws Exception {
        // 同一集合同时携带正零、负零和非零，恢复时必须保持元素位置。
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(ROOT);
        GrpcProtoSchema.mergeJson("{\"samples\":[1.25,-0.0,0.0]}", builder, REGISTRY);

        // 通过二进制结果按原索引检查，避免只验证集合中存在某个值。
        DynamicMessage wire = roundTrip(builder.build());
        var samples = ROOT.findFieldByName("samples");
        assertEquals(3, wire.getRepeatedFieldCount(samples));
        assertEquals(1.25, (Double) wire.getRepeatedField(samples, 0), 0.0);
        assertNegativeZero(wire.getRepeatedField(samples, 1));
        assertEquals(0L, Double.doubleToRawLongBits((Double) wire.getRepeatedField(samples, 2)));
    }

    @Test
    public void repeatedMessagesPreserveNestedNegativeZero() throws Exception {
        // DTO 集合的负零位于各元素内部，不能只恢复重复 double 标量。
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(ROOT);
        GrpcProtoSchema.mergeJson("{\"children\":[{\"value\":1.25},{\"value\":-0.0}]}", builder, REGISTRY);

        // 二进制往返保持子消息顺序，并恢复目标元素内的 double。
        DynamicMessage wire = roundTrip(builder.build());
        var children = ROOT.findFieldByName("children");
        DynamicMessage first = (DynamicMessage) wire.getRepeatedField(children, 0);
        DynamicMessage second = (DynamicMessage) wire.getRepeatedField(children, 1);
        assertEquals(1.25, (Double) first.getField(LEAF.findFieldByName("value")), 0.0);
        assertNegativeZero(second.getField(LEAF.findFieldByName("value")));
    }

    @Test
    public void mapDoubleNegativeZeroRemainsAttachedToItsKey() throws Exception {
        // Map 的条目顺序不构成契约，使用 key 选择需要保留符号位的值。
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(ROOT);
        GrpcProtoSchema.mergeJson("{\"amounts\":{\"negative\":-0.0,\"ordinary\":2.5}}", builder, REGISTRY);

        // 独立读取 Map entry 的二进制结果，其他条目保持原值。
        Map<Object, Object> amounts = mapValues(roundTrip(builder.build()), "amounts");
        assertEquals(2, amounts.size());
        assertNegativeZero(amounts.get("negative"));
        assertEquals(2.5, (Double) amounts.get("ordinary"), 0.0);
    }

    @Test
    public void mapMessagePreservesNestedNegativeZero() throws Exception {
        // Map 的 DTO 值需要继续按值消息的描述符遍历。
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(ROOT);
        GrpcProtoSchema.mergeJson("{\"childrenByCode\":{\"negative\":{\"value\":-0.0}}}", builder, REGISTRY);

        // key 定位业务对象后检查它的真实二进制 double。
        Map<Object, Object> children = mapValues(roundTrip(builder.build()), "children_by_code");
        DynamicMessage child = (DynamicMessage) children.get("negative");
        assertNegativeZero(child.getField(LEAF.findFieldByName("value")));
    }

    @Test
    public void positiveZeroAndNonzeroDoublesRemainUnchanged() throws Exception {
        // 修复只处理负零，不改变正常 double 的值或正零的符号位。
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(ROOT);
        GrpcProtoSchema.mergeJson("{\"value\":0.0,\"child\":{\"value\":-12.5}}", builder, REGISTRY);

        // 默认正零与实际写出的子消息均按各自原值读取。
        DynamicMessage wire = roundTrip(builder.build());
        assertEquals(0L, Double.doubleToRawLongBits((Double) wire.getField(ROOT.findFieldByName("value"))));
        DynamicMessage child = (DynamicMessage) wire.getField(ROOT.findFieldByName("child"));
        assertEquals(-12.5, (Double) child.getField(LEAF.findFieldByName("value")), 0.0);
    }

    @Test
    public void explicitJsonNameAliasPreservesNegativeZero() throws Exception {
        // json_name 与 proto 字段名不同，恢复过程仍使用同一个字段描述符。
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(ROOT);
        GrpcProtoSchema.mergeJson("{\"jsonAmount\":-0.0}", builder, REGISTRY);

        // 检查实际声明的 proto 字段，不把 JSON 名称当成额外字段。
        DynamicMessage wire = roundTrip(builder.build());
        assertNegativeZero(wire.getField(ROOT.findFieldByName("custom_value")));
    }

    @Test
    public void protoFieldNameAliasPreservesNegativeZero() throws Exception {
        // 官方解析器也接受显式 json_name 对应的原始 proto 名称。
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(ROOT);
        GrpcProtoSchema.mergeJson("{\"custom_value\":-0.0}", builder, REGISTRY);

        // 两种名称映射的结果都进入同一个二进制字段。
        DynamicMessage wire = roundTrip(builder.build());
        assertNegativeZero(wire.getField(ROOT.findFieldByName("custom_value")));
    }

    @Test
    public void finalProtoNameAliasKeepsPositiveZero() throws Exception {
        // 同一字段依次使用 JSON 名称和 proto 名称，最后一次赋值为正零。
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(ROOT);
        GrpcProtoSchema.mergeJson("{\"sampleValue\":-0.0,\"sample_value\":0.0}", builder, REGISTRY);

        // 负零恢复不能覆盖官方解析器按源顺序选出的最后一次赋值。
        DynamicMessage wire = roundTrip(builder.build());
        Double value = (Double) wire.getField(ROOT.findFieldByName("sample_value"));
        assertEquals(0L, Double.doubleToRawLongBits(value));
    }

    @Test
    public void finalJsonNameAliasPreservesNegativeZero() throws Exception {
        // 两种名称按相反顺序出现，最后一次赋值为负零。
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(ROOT);
        GrpcProtoSchema.mergeJson("{\"sample_value\":0.0,\"sampleValue\":-0.0}", builder, REGISTRY);

        // 从源顺序选择最后一个成员，再恢复最终字段的负零位模式。
        DynamicMessage wire = roundTrip(builder.build());
        assertNegativeZero(wire.getField(ROOT.findFieldByName("sample_value")));
    }

    @Test
    public void laterNullAliasKeepsTheEarlierNegativeZero() throws Exception {
        // 官方解析器跳过后续 null，同字段先前的非 null 赋值继续生效。
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(ROOT);
        GrpcProtoSchema.mergeJson("{\"sampleValue\":-0.0,\"sample_value\":null}", builder, REGISTRY);

        // 源顺序处理别名时，不能把 null 当成覆盖先前数值的赋值。
        DynamicMessage wire = roundTrip(builder.build());
        assertNegativeZero(wire.getField(ROOT.findFieldByName("sample_value")));
    }

    @Test
    public void doubleValueWrapperPreservesNegativeZero() throws Exception {
        // DoubleValue 的 JSON 是标量，不是带 value 属性的普通对象。
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(ROOT);
        GrpcProtoSchema.mergeJson("{\"wrapped\":-0.0}", builder, REGISTRY);

        // 通过官方包装类型读取最终字节，确认内部 double 的符号位。
        DynamicMessage wire = roundTrip(builder.build());
        DynamicMessage wrapped = (DynamicMessage) wire.getField(ROOT.findFieldByName("wrapped"));
        DoubleValue value = DoubleValue.parseFrom(wrapped.toByteArray());
        assertNegativeZero(value.getValue());
    }

    @Test
    public void anyContainingAnOrdinaryMessagePreservesNegativeZero() throws Exception {
        // Any 的业务消息由注册表解析，负零位于业务消息而非 Any 信封上。
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(ROOT);
        String json = "{\"payload\":{\"@type\":\"type.googleapis.com/test.merge.Leaf\",\"value\":-0.0}}";
        GrpcProtoSchema.mergeJson(json, builder, REGISTRY);

        // 解开真实 Any.value 字节，再用业务描述符读取负零。
        DynamicMessage wire = roundTrip(builder.build());
        Any payload = anyValue(wire);
        assertEquals("type.googleapis.com/test.merge.Leaf", payload.getTypeUrl());
        DynamicMessage leaf = DynamicMessage.parseFrom(LEAF, payload.getValue());
        assertNegativeZero(leaf.getField(LEAF.findFieldByName("value")));
    }

    @Test
    public void anyTypeEnvelopeDoesNotPopulateAnAliasedBusinessField() throws Exception {
        // 业务 double 字段也声明 json_name 为 @type，但 Any 信封标记不属于业务内容。
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(ROOT);
        String json = "{\"payload\":{\"@type\":\"type.googleapis.com/test.merge.TaggedLeaf\",\"zero\":-0.0}}";
        GrpcProtoSchema.mergeJson(json, builder, REGISTRY);

        // 解开 Any 后，业务类型标记保持默认正零，真正的负零字段仍被恢复。
        Any payload = anyValue(roundTrip(builder.build()));
        DynamicMessage leaf = DynamicMessage.parseFrom(TAGGED_LEAF, payload.getValue());
        Double typeTag = (Double) leaf.getField(TAGGED_LEAF.findFieldByName("type_tag"));
        assertEquals(0L, Double.doubleToRawLongBits(typeTag));
        assertNegativeZero(leaf.getField(TAGGED_LEAF.findFieldByName("zero")));
    }

    @Test
    public void anyContainingDoubleValuePreservesNegativeZero() throws Exception {
        // Any 中的包装类型保留其特殊 value JSON 形式。
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(ROOT);
        String json = "{\"payload\":{\"@type\":\"type.googleapis.com/google.protobuf.DoubleValue\",\"value\":-0.0}}";
        GrpcProtoSchema.mergeJson(json, builder, REGISTRY);

        // 官方 Any 解包同时验证类型信息和内部二进制内容。
        Any payload = anyValue(roundTrip(builder.build()));
        assertTrue(payload.is(DoubleValue.class));
        assertNegativeZero(payload.unpack(DoubleValue.class).getValue());
    }

    @Test
    public void unknownJsonFieldsRemainRejected() throws Exception {
        // 先交给官方解析器校验，补偿负零不能容忍未知业务属性。
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(ROOT);
        IOException failure = assertThrows(IOException.class, () -> {
            GrpcProtoSchema.mergeJson("{\"missing\":-0.0}", builder, REGISTRY);
        });

        // 调用方继续得到解析失败，不会收到看似成功的默认消息。
        assertNotNull(failure.getMessage());
    }

    @Test
    public void outOfRangeIntegersRemainRejected() throws Exception {
        // double 修复不能绕过其他字段的 int32 范围约束。
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(ROOT);
        IOException failure = assertThrows(IOException.class, () -> {
            GrpcProtoSchema.mergeJson("{\"count\":2147483648,\"value\":-0.0}", builder, REGISTRY);
        });

        // 保留官方解析器对超范围整数的失败契约。
        assertNotNull(failure.getMessage());
    }

    @Test
    public void numericMapKeyUsesTheParsedIntegerIdentity() throws Exception {
        // 官方解析器接受整数值形式的 1.0，恢复时应按已解析的 int32 key 对齐。
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(ROOT);
        GrpcProtoSchema.mergeJson("{\"numbered\":{\"1.0\":-0.0}}", builder, REGISTRY);

        // 线上的 key 是整数 1，不再具有原始 JSON 文本形式。
        Map<Object, Object> numbered = mapValues(roundTrip(builder.build()), "numbered");
        assertEquals(1, numbered.size());
        assertNegativeZero(numbered.get(1));
    }

    @Test
    public void unsignedMapKeyRetainsItsFullRangeIdentity() throws Exception {
        // uint64 最大值使用标准 JSON 十进制 key，不能按有符号 long 文本匹配。
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(ROOT);
        GrpcProtoSchema.mergeJson("{\"unsignedAmounts\":{\"18446744073709551615\":-0.0}}", builder, REGISTRY);

        // Protobuf 反射用 long 的位模式承载 uint64，负零仍归属该条目。
        Map<Object, Object> amounts = mapValues(roundTrip(builder.build()), "unsigned_amounts");
        assertEquals(1, amounts.size());
        assertNegativeZero(amounts.get(-1L));
    }

    @Test
    public void equivalentIntegerMapKeysKeepTheLastValue() throws Exception {
        // 两个不同 JSON key 文本映射成同一个 int32，最后一个条目携带负零。
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(ROOT);
        GrpcProtoSchema.mergeJson("{\"numbered\":{\"1\":2.5,\"1.0\":-0.0}}", builder, REGISTRY);

        // 按 Map 的最后赋值语义读取二进制条目，不能恢复到前一个同 key 条目。
        Map<Object, Object> numbered = mapValues(roundTrip(builder.build()), "numbered");
        assertEquals(1, numbered.size());
        assertNegativeZero(numbered.get(1));
    }

    private static void assertNegativeZero(Object value) {
        assertNotNull(value);
        assertEquals(Long.MIN_VALUE, Double.doubleToRawLongBits(((Number) value).doubleValue()));
    }

    private static DynamicMessage roundTrip(DynamicMessage message) throws IOException {
        return DynamicMessage.parseFrom(message.getDescriptorForType(), message.toByteArray());
    }

    private static Any anyValue(DynamicMessage message) throws IOException {
        DynamicMessage value = (DynamicMessage) message.getField(ROOT.findFieldByName("payload"));
        return Any.parseFrom(value.toByteArray());
    }

    private static Map<Object, Object> mapValues(DynamicMessage message, String name) {
        Map<Object, Object> values = new LinkedHashMap<>();
        var field = ROOT.findFieldByName(name);
        for (int index = 0; index < message.getRepeatedFieldCount(field); index++) {
            DynamicMessage entry = (DynamicMessage) message.getRepeatedField(field, index);
            Descriptor type = entry.getDescriptorForType();
            values.put(entry.getField(type.findFieldByName("key")), entry.getField(type.findFieldByName("value")));
        }
        return values;
    }

    private static FileDescriptor fixture() {
        // 描述符完全由官方公开 API 构建，Map entry 与包装类型沿用真实线格式。
        DescriptorProto leaf = DescriptorProto.newBuilder().setName("Leaf") //
                .addField(field("value", 1, Type.TYPE_DOUBLE)).build();
        DescriptorProto taggedLeaf = DescriptorProto.newBuilder().setName("TaggedLeaf") //
                .addField(field("type_tag", 1, Type.TYPE_DOUBLE).setJsonName("@type")) //
                .addField(field("zero", 2, Type.TYPE_DOUBLE)).build();
        DescriptorProto root = DescriptorProto.newBuilder().setName("Root") //
                .addNestedType(mapEntry("AmountsEntry", Type.TYPE_STRING)) //
                .addNestedType(mapEntry("NumberedEntry", Type.TYPE_INT32)) //
                .addNestedType(mapEntry("UnsignedAmountsEntry", Type.TYPE_UINT64)) //
                .addNestedType(mapMessageEntry()) //
                .addField(field("value", 1, Type.TYPE_DOUBLE)) //
                .addField(field("child", 2, Type.TYPE_MESSAGE).setTypeName(".test.merge.Leaf")) //
                .addField(field("samples", 3, Type.TYPE_DOUBLE).setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)) //
                .addField(mapField("amounts", 4, "AmountsEntry")) //
                .addField(field("wrapped", 5, Type.TYPE_MESSAGE).setTypeName(".google.protobuf.DoubleValue")) //
                .addField(field("payload", 6, Type.TYPE_MESSAGE).setTypeName(".google.protobuf.Any")) //
                .addField(field("count", 7, Type.TYPE_INT32)) //
                .addField(mapField("numbered", 8, "NumberedEntry")) //
                .addField(mapField("unsigned_amounts", 9, "UnsignedAmountsEntry")) //
                .addField(field("custom_value", 10, Type.TYPE_DOUBLE).setJsonName("jsonAmount")) //
                .addField(field("children", 11, Type.TYPE_MESSAGE).setTypeName(".test.merge.Leaf") //
                        .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED)) //
                .addField(mapField("children_by_code", 12, "ChildrenByCodeEntry")) //
                .addField(field("sample_value", 13, Type.TYPE_DOUBLE).setJsonName("sampleValue")).build();
        FileDescriptorProto file = FileDescriptorProto.newBuilder().setName("negative-zero.proto") //
                .setPackage("test.merge").setSyntax("proto3") //
                .addDependency(Any.getDescriptor().getFile().getName()) //
                .addDependency(DoubleValue.getDescriptor().getFile().getName()) //
                .addMessageType(leaf).addMessageType(taggedLeaf).addMessageType(root).build();
        try {
            FileDescriptor[] dependencies = { Any.getDescriptor().getFile(), DoubleValue.getDescriptor().getFile() };
            return FileDescriptor.buildFrom(file, dependencies);
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static FieldDescriptorProto.Builder field(String name, int number, Type type) {
        return FieldDescriptorProto.newBuilder().setName(name).setNumber(number).setType(type) //
                .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL);
    }

    private static FieldDescriptorProto.Builder mapField(String name, int number, String entryName) {
        return field(name, number, Type.TYPE_MESSAGE).setTypeName(".test.merge.Root." + entryName) //
                .setLabel(FieldDescriptorProto.Label.LABEL_REPEATED);
    }

    private static DescriptorProto mapEntry(String name, Type keyType) {
        return DescriptorProto.newBuilder().setName(name) //
                .setOptions(MessageOptions.newBuilder().setMapEntry(true)) //
                .addField(field("key", 1, keyType)).addField(field("value", 2, Type.TYPE_DOUBLE)).build();
    }

    private static DescriptorProto mapMessageEntry() {
        return DescriptorProto.newBuilder().setName("ChildrenByCodeEntry") //
                .setOptions(MessageOptions.newBuilder().setMapEntry(true)) //
                .addField(field("key", 1, Type.TYPE_STRING)) //
                .addField(field("value", 2, Type.TYPE_MESSAGE).setTypeName(".test.merge.Leaf")).build();
    }
}

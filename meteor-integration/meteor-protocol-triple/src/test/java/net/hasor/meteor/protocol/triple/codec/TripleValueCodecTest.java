/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.triple.codec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.*;
import com.caucho.hessian.io.Hessian2Input;
import com.caucho.hessian.io.Hessian2Output;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.WireFormat;
import net.hasor.example.protocol.triple.TripleDemoService;
import net.hasor.example.protocol.triple.TripleMessage;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import org.junit.Test;
import static org.junit.Assert.*;

public class TripleValueCodecTest {
    private final TripleValueCodec codec = new TripleValueCodec(4096, this.getClass().getClassLoader());

    @Test
    public void rejectsZeroMessageLimit() {
        // 消息上限是正数，避免创建无法容纳任何包装的编解码器。
        assertThrows(IllegalArgumentException.class, () -> new TripleValueCodec(0, this.getClass().getClassLoader()));
    }

    @Test
    public void rejectsNegativeMessageLimit() {
        // 负数配置在编解码器创建时失败。
        assertThrows(IllegalArgumentException.class, () -> new TripleValueCodec(-1, this.getClass().getClassLoader()));
    }

    @Test
    public void requestDeclaresOfficialHessianSerializer() throws Exception {
        // 使用独立 protobuf 读取器检查 Dubbo 约定的序列化器字段。
        byte[] wrapper = this.codec.encodeArguments(new Object[] { "hello" }, new Class<?>[] { String.class });
        CodedInputStream input = CodedInputStream.newInstance(wrapper);
        assertEquals(10, input.readTag());
        assertEquals("hessian4", input.readStringRequireUtf8());
    }

    @Test
    public void acceptsHessian2SerializerAliasInRequest() throws Exception {
        // Dubbo 的部分响应路径沿用 SPI 名称，两个名称均表示同一 Hessian 2 文档。
        byte[] wrapper = this.request("hessian2", List.of(this.hessian("hello")), List.of("java.lang.String"));

        // 只接受已知同义名称，参数类型与正文仍按原签名验证。
        Object[] result = this.codec.decodeArguments(wrapper, new Class<?>[] { String.class });
        assertArrayEquals(new Object[] { "hello" }, result);
    }

    @Test
    public void acceptsHessian2SerializerAliasInResponse() throws Exception {
        // 官方异常定制器可能写入 hessian2，而普通 wrapper 默认写入 hessian4。
        byte[] wrapper = this.result("hessian2", this.hessian("result"), "java.lang.String");

        // 结果编码不变，无需安装另一套序列化器。
        assertEquals("result", this.codec.decodeResult(wrapper, String.class));
    }

    @Test
    public void requestContainsIndependentHessianDocuments() throws Exception {
        // 两个参数各自包含一个 Hessian 2 对象，而非共享一个对象流。
        Object[] values = { "hello", 7 };
        Class<?>[] types = { String.class, int.class };
        byte[] wrapper = this.codec.encodeArguments(values, types);
        List<byte[]> encoded = this.fields(wrapper, 2);

        // 每个参数均能由新建的官方 Hessian 输入独立读取。
        assertEquals(2, encoded.size());
        assertEquals("hello", this.readHessian(encoded.get(0), String.class));
        assertEquals(7, this.readHessian(encoded.get(1), int.class));
    }

    @Test
    public void requestDeclaresOrderedJavaTypeNames() throws Exception {
        // 参数类型按原始签名顺序写入字段 3，包括基本类型的名称。
        Object[] values = { "hello", 7 };
        Class<?>[] types = { String.class, int.class };
        byte[] wrapper = this.codec.encodeArguments(values, types);
        assertEquals(Arrays.asList("java.lang.String", "int"), this.codec.argumentTypes(wrapper));
    }

    @Test
    public void requestWithNoArgumentsHasNoValueFields() throws Exception {
        // 无参方法仍保留序列化器声明，参数与类型字段均为空。
        byte[] wrapper = this.codec.encodeArguments(new Object[0], new Class<?>[0]);
        assertTrue(this.fields(wrapper, 2).isEmpty());
        assertTrue(this.codec.argumentTypes(wrapper).isEmpty());
        assertArrayEquals(new Object[0], this.codec.decodeArguments(wrapper, new Class<?>[0]));
    }

    @Test
    public void decodesIndependentNullReferenceArgument() throws Exception {
        // 对端用 Hessian null 表示空引用，声明类型仍是 String。
        byte[] wrapper = this.request("hessian4", List.of(this.hessian(null)), List.of("java.lang.String"));
        Object[] decoded = this.codec.decodeArguments(wrapper, new Class<?>[] { String.class });
        assertArrayEquals(new Object[] { null }, decoded);
    }

    @Test
    public void decodesIndependentMultipleArguments() throws Exception {
        // 按正式方法的签名解码多个独立值，不从线协议加载参数类型。
        Method method = TripleDemoService.class.getMethod("greet", String.class, int.class);
        List<byte[]> values = List.of(this.hessian("world"), this.hessian(3));
        byte[] wrapper = this.request("hessian4", values, List.of("java.lang.String", "int"));
        assertArrayEquals(new Object[] { "world", 3 }, this.codec.decodeArguments(wrapper, method));
    }

    @Test
    public void decodesPrimitiveArrayArgument() throws Exception {
        // 数组类型使用 JVM 的 getName 表示，Hessian 正文保留元素类型。
        byte[] wrapper = this.request("hessian4", List.of(this.hessian(new int[] { 1, 2, 3 })), List.of("[I"));
        Object[] decoded = this.codec.decodeArguments(wrapper, new Class<?>[] { int[].class });
        assertArrayEquals(new int[] { 1, 2, 3 }, (int[]) decoded[0]);
    }

    @Test
    public void decodesDeclaredBusinessObjectArgument() throws Exception {
        // 普通 Java Bean 无需 proto 文件即可作为已发布参数类型。
        TripleMessage message = TripleMessage.of("object", 9);
        List<byte[]> values = List.of(this.hessian(message));
        List<String> types = List.of(TripleMessage.class.getName());
        byte[] wrapper = this.request("hessian4", values, types);
        Object[] decoded = this.codec.decodeArguments(wrapper, new Class<?>[] { TripleMessage.class });

        // 原始字段值在独立 Hessian 文档中恢复。
        TripleMessage result = (TripleMessage) decoded[0];
        assertEquals("object", result.getText());
        assertEquals(9, result.getNumber());
    }

    @Test
    public void immutableListArgumentUsesCollectionEncoding() {
        // JDK 不可变列表按容器元素编码，不反射其私有 writeReplace 方法。
        List<String> values = List.of("first", "second");
        byte[] wrapper = this.codec.encodeArguments(new Object[] { values }, new Class<?>[] { List.class });

        // 接收端以公开 List 签名恢复内容。
        Object[] decoded = this.codec.decodeArguments(wrapper, new Class<?>[] { List.class });
        assertEquals(values, decoded[0]);
    }

    @Test
    public void immutableMapArgumentUsesMapEncoding() {
        // 不可变 Map 使用键值协议，接收方无需访问 JDK 内部字段。
        Map<String, Integer> values = Map.of("first", 1, "second", 2);
        byte[] wrapper = this.codec.encodeArguments(new Object[] { values }, new Class<?>[] { Map.class });

        // 声明 Map 类型足以恢复键值关系。
        Object[] decoded = this.codec.decodeArguments(wrapper, new Class<?>[] { Map.class });
        assertEquals(values, decoded[0]);
    }

    @Test
    public void immutableSetArgumentUsesCollectionEncoding() {
        // Set 保留集合类型信息，不能退化为 List。
        Set<String> values = Set.of("first", "second");
        byte[] wrapper = this.codec.encodeArguments(new Object[] { values }, new Class<?>[] { Set.class });

        // 以公开 Set 签名恢复成员。
        Object[] decoded = this.codec.decodeArguments(wrapper, new Class<?>[] { Set.class });
        assertEquals(values, decoded[0]);
    }

    @Test
    public void immutableListResultUsesCollectionEncoding() {
        // 返回不可变列表同样使用容器协议。
        List<String> value = List.of("reply");
        byte[] wrapper = this.codec.encodeResult(value, List.class);
        assertEquals(value, this.codec.decodeResult(wrapper, List.class));
    }

    @Test
    public void immutableMapResultUsesMapEncoding() {
        // 返回不可变 Map 不需要额外开放 java.util 模块。
        Map<String, String> value = Map.of("reply", "value");
        byte[] wrapper = this.codec.encodeResult(value, Map.class);
        assertEquals(value, this.codec.decodeResult(wrapper, Map.class));
    }

    @Test
    public void immutableSetResultUsesCollectionEncoding() {
        // 返回 Set 的类型语义与成员在 RPC 中保留。
        Set<String> value = Set.of("reply");
        byte[] wrapper = this.codec.encodeResult(value, Set.class);
        assertEquals(value, this.codec.decodeResult(wrapper, Set.class));
    }

    @Test
    public void nestedImmutableCollectionsUseContainerEncoding() {
        // 集合中的嵌套不可变容器也通过相同工厂编码。
        Map<String, List<Set<Integer>>> value = Map.of("nested", List.of(Set.of(1, 2)));
        byte[] wrapper = this.codec.encodeResult(value, Map.class);

        // 不依赖容器的私有实现类恢复整个嵌套结构。
        assertEquals(value, this.codec.decodeResult(wrapper, Map.class));
    }

    @Test
    public void rejectsArrayTypeWithExcessiveDimensions() throws Exception {
        // 空列表也不能携带令原生类型工厂递归过深的数组类型名。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Hessian2Output output = new Hessian2Output(bytes);
        output.writeListBegin(0, "[".repeat(65) + "int");
        output.flush();
        byte[] wrapper = this.result("hessian4", bytes.toByteArray(), List.class.getName());

        // 在原生反序列化器解析类型之前报告协议格式错误。
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> this.codec.decodeResult(wrapper, List.class));
        assertEquals(13, failure.getCode());
    }

    @Test
    public void rejectsArrayDimensionsSplitAcrossTypeChunks() throws Exception {
        // 类型名分块不能绕过总维数限制，首块与尾块合计 65 个数组维度。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(0x70);
        bytes.write('R');
        bytes.write(0);
        bytes.write(32);
        bytes.write("[".repeat(32).getBytes());
        bytes.write(0x30);
        bytes.write(34);
        bytes.write(("[".repeat(33) + "I").getBytes());
        byte[] wrapper = this.result("hessian4", bytes.toByteArray(), List.class.getName());

        // 所有字符串块合并后统一校验。
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> this.codec.decodeResult(wrapper, List.class));
        assertEquals(13, failure.getCode());
    }

    @Test
    public void overloadSelectsPublishedPrimitiveSignature() throws Exception {
        // 同名方法依靠参数签名区分，不能将 int 值当作 String 重载。
        Method method = TripleDemoService.class.getMethod("overloaded", int.class);
        byte[] wrapper = this.request("hessian4", List.of(this.hessian(42)), List.of("int"));
        assertArrayEquals(new Object[] { 42 }, this.codec.decodeArguments(wrapper, method));
    }

    @Test
    public void resultDeclaresOfficialSerializerAndReturnType() throws Exception {
        // 响应字段 1 是序列化器，字段 2 是对象，字段 3 是声明返回类型。
        byte[] wrapper = this.codec.encodeResult("reply", String.class);
        CodedInputStream input = CodedInputStream.newInstance(wrapper);
        assertEquals(10, input.readTag());
        assertEquals("hessian4", input.readStringRequireUtf8());
        assertEquals(18, input.readTag());
        assertEquals("reply", this.readHessian(input.readByteArray(), String.class));
        assertEquals(26, input.readTag());
        assertEquals("java.lang.String", input.readStringRequireUtf8());
        assertEquals(0, input.readTag());
    }

    @Test
    public void decodesIndependentStringResult() throws Exception {
        // 独立构造官方响应信封，避免只测试自身编码与解码的对称性。
        byte[] wrapper = this.result("hessian4", this.hessian("reply"), "java.lang.String");
        assertEquals("reply", this.codec.decodeResult(wrapper, String.class));
    }

    @Test
    public void decodesIndependentPrimitiveResult() throws Exception {
        // 基本类型响应仍按已发布方法的返回类型读取。
        byte[] wrapper = this.result("hessian4", this.hessian(27), "int");
        assertEquals(27, this.codec.decodeResult(wrapper, int.class));
    }

    @Test
    public void decodesIndependentBusinessObjectResult() throws Exception {
        // 业务对象的字段在响应包装中完整保留。
        TripleMessage message = TripleMessage.of("response", 8);
        byte[] wrapper = this.result("hessian4", this.hessian(message), TripleMessage.class.getName());
        TripleMessage decoded = (TripleMessage) this.codec.decodeResult(wrapper, TripleMessage.class);
        assertEquals("response", decoded.getText());
        assertEquals(8, decoded.getNumber());
    }

    @Test
    public void voidResultEncodesHessianNull() throws Exception {
        // void 方法仍有一个表示成功完成的包装值。
        byte[] wrapper = this.codec.encodeResult(null, void.class);
        assertEquals(1, this.fields(wrapper, 2).size());
        assertNull(this.codec.decodeResult(wrapper, void.class));
    }

    @Test
    public void referenceResultPreservesNull() throws Exception {
        // 空引用响应与没有响应正文是不同语义。
        byte[] wrapper = this.result("hessian4", this.hessian(null), "java.lang.String");
        assertNull(this.codec.decodeResult(wrapper, String.class));
    }

    @Test
    public void exceptionChannelIgnoresOriginalResultType() throws Exception {
        // Dubbo 异常通道仍声明原返回类型，实际对象由异常响应头确定。
        IllegalArgumentException failure = new IllegalArgumentException("bad input");
        byte[] wrapper = this.result("hessian4", this.hessian(failure), "java.lang.String");
        TripleValueCodec exceptions = new TripleValueCodec(65536, this.getClass().getClassLoader());
        Throwable decoded = (Throwable) exceptions.decodeResult(wrapper, Throwable.class);
        assertEquals(IllegalArgumentException.class, decoded.getClass());
        assertEquals("bad input", decoded.getMessage());
    }

    @Test
    public void rejectsArgumentValueAndTypeCountDisagreement() {
        // 编码方不能产生参数数量与声明数量不一致的信封。
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> this.codec.encodeArguments(new Object[] { "one" }, new Class<?>[0]));
        assertEquals(13, failure.getCode());
    }

    @Test
    public void rejectsUnknownRequestSerializer() throws Exception {
        // 未安装的序列化器必须报告协议能力不支持。
        byte[] wrapper = this.request("not-installed", List.of(), List.of());
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> this.codec.argumentTypes(wrapper));
        assertEquals(12, failure.getCode());
    }

    @Test
    public void rejectsUnknownResponseSerializer() throws Exception {
        // 响应不允许悄悄以 Hessian 解码未知格式。
        byte[] wrapper = this.result("not-installed", this.hessian("value"), "java.lang.String");
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> this.codec.decodeResult(wrapper, String.class));
        assertEquals(12, failure.getCode());
    }

    @Test
    public void rejectsTruncatedRequestProtobuf() {
        // 长度字段声明了尚未到达的序列化器字符串。
        byte[] wrapper = { 10, 8, 'h' };
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> this.codec.argumentTypes(wrapper));
        assertEquals(13, failure.getCode());
    }

    @Test
    public void rejectsTruncatedResponseProtobuf() {
        // 响应包装在读取 Hessian 值之前先检查 protobuf 完整性。
        byte[] wrapper = { 10, 8, 'h' };
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> this.codec.decodeResult(wrapper, String.class));
        assertEquals(13, failure.getCode());
    }

    @Test
    public void rejectsRequestFieldWithVarintWireType() throws Exception {
        // 序列化器是字符串，不能接受同编号的 varint 字段。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        output.writeInt32(1, 1);
        output.flush();
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> this.codec.argumentTypes(bytes.toByteArray()));
        assertEquals(13, failure.getCode());
    }

    @Test
    public void rejectsResponseFieldWithVarintWireType() throws Exception {
        // 结果值只能是 length-delimited 数据字段。
        byte[] wrapper = this.append(this.result("hessian4", this.hessian("v"), "java.lang.String"), 16, 1);
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> this.codec.decodeResult(wrapper, String.class));
        assertEquals(13, failure.getCode());
    }

    @Test
    public void rejectsDuplicateRequestSerializer() throws Exception {
        // 单值序列化器字段重复会使包装含义不确定。
        byte[] first = this.request("hessian4", List.of(), List.of());
        byte[] wrapper = this.concat(first, this.request("hessian4", List.of(), List.of()));
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> this.codec.argumentTypes(wrapper));
        assertEquals(13, failure.getCode());
    }

    @Test
    public void rejectsDuplicateResponseSerializer() throws Exception {
        // 响应只允许一份序列化器声明。
        byte[] first = this.result("hessian4", this.hessian("v"), "java.lang.String");
        byte[] wrapper = this.concat(first, this.request("hessian4", List.of(), List.of()));
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> this.codec.decodeResult(wrapper, String.class));
        assertEquals(13, failure.getCode());
    }

    @Test
    public void rejectsDuplicateResponseValue() throws Exception {
        // 两个返回值不能压缩为任意一个值。
        byte[] wrapper = this.result("hessian4", this.hessian("first"), "java.lang.String");
        wrapper = this.concat(wrapper, this.binaryField(2, this.hessian("second")));
        byte[] encoded = wrapper;
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> this.codec.decodeResult(encoded, String.class));
        assertEquals(13, failure.getCode());
    }

    @Test
    public void rejectsDuplicateResponseType() throws Exception {
        // 返回类型是单值字段，重复声明直接失败。
        byte[] wrapper = this.result("hessian4", this.hessian("v"), "java.lang.String");
        byte[] encoded = this.concat(wrapper, this.stringField(3, "java.lang.String"));
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> this.codec.decodeResult(encoded, String.class));
        assertEquals(13, failure.getCode());
    }

    @Test
    public void rejectsRequestWithoutSerializer() throws Exception {
        // 参数和类型齐全仍不能省略包装编码方式。
        byte[] values = this.binaryField(2, this.hessian("v"));
        byte[] wrapper = this.concat(values, this.stringField(3, "java.lang.String"));
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> this.codec.argumentTypes(wrapper));
        assertEquals(13, failure.getCode());
    }

    @Test
    public void rejectsResponseWithoutValue() throws Exception {
        // 无返回值字段与 Hessian null 不同，缺失字段属于损坏的包装。
        byte[] serializer = this.stringField(1, "hessian4");
        byte[] wrapper = this.concat(serializer, this.stringField(3, "java.lang.String"));
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> this.codec.decodeResult(wrapper, String.class));
        assertEquals(13, failure.getCode());
    }

    @Test
    public void rejectsResponseWithoutDeclaredType() throws Exception {
        // 正文即使可读，仍必须明确声明返回类型。
        byte[] serializer = this.stringField(1, "hessian4");
        byte[] wrapper = this.concat(serializer, this.binaryField(2, this.hessian("v")));
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> this.codec.decodeResult(wrapper, String.class));
        assertEquals(13, failure.getCode());
    }

    @Test
    public void rejectsArgumentAndDeclaredTypeCountMismatch() throws Exception {
        // 参数值缺少对应签名时，不能进入方法选择阶段。
        byte[] wrapper = this.request("hessian4", List.of(this.hessian("v")), List.of());
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> this.codec.argumentTypes(wrapper));
        assertEquals(13, failure.getCode());
    }

    @Test
    public void rejectsArgumentsNotMatchingPublishedCount() throws Exception {
        // 包装自身完整，但不能向无参方法传递额外参数。
        byte[] wrapper = this.request("hessian4", List.of(this.hessian("v")), List.of("java.lang.String"));
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> this.codec.decodeArguments(wrapper, new Class<?>[0]));
        assertEquals(13, failure.getCode());
    }

    @Test
    public void rejectsArgumentsNotMatchingPublishedType() throws Exception {
        // 参数声明不能替换服务已发布的方法签名。
        byte[] wrapper = this.request("hessian4", List.of(this.hessian(3)), List.of("int"));
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> this.codec.decodeArguments(wrapper, new Class<?>[] { String.class }));
        assertEquals(13, failure.getCode());
    }

    @Test
    public void rejectsNetworkClassNameWithoutLoadingIt() throws Exception {
        // 任意网络类名只用于签名比较，不触发不存在类的加载。
        String networkType = "untrusted.payload.ClassThatMustNotBeLoaded";
        byte[] wrapper = this.request("hessian4", List.of(this.hessian("v")), List.of(networkType));
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> this.codec.decodeArguments(wrapper, new Class<?>[] { String.class }));
        assertEquals(13, failure.getCode());
        assertNull(failure.getCause());
    }

    @Test
    public void rejectsResponseNotMatchingPublishedType() throws Exception {
        // 普通返回通道必须符合本地已发布的返回类型。
        byte[] wrapper = this.result("hessian4", this.hessian(3), "int");
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> this.codec.decodeResult(wrapper, String.class));
        assertEquals(13, failure.getCode());
    }

    @Test
    public void rejectsEmptyHessianArgumentDocument() throws Exception {
        // 空字节数组没有携带 Hessian null 或任何对象。
        byte[] wrapper = this.request("hessian4", List.of(new byte[0]), List.of("java.lang.String"));
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> this.codec.decodeArguments(wrapper, new Class<?>[] { String.class }));
        assertEquals(13, failure.getCode());
    }

    @Test
    public void rejectsNullPrimitiveArgument() throws Exception {
        // Java 基本类型参数不允许由对端的 null 填充。
        byte[] wrapper = this.request("hessian4", List.of(this.hessian(null)), List.of("int"));
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> this.codec.decodeArguments(wrapper, new Class<?>[] { int.class }));
        assertEquals(13, failure.getCode());
    }

    @Test
    public void acceptsUnknownRequestProtobufField() throws Exception {
        // 后续协议版本增加的未知字段应按 protobuf 规则跳过。
        byte[] wrapper = this.request("hessian4", List.of(this.hessian("v")), List.of("java.lang.String"));
        byte[] extended = this.concat(wrapper, this.stringField(19, "future field"));
        assertArrayEquals(new Object[] { "v" }, this.codec.decodeArguments(extended, new Class<?>[] { String.class }));
    }

    @Test
    public void acceptsUnknownResponseProtobufField() throws Exception {
        // 未知响应字段不改变已知的单个返回值。
        byte[] wrapper = this.result("hessian4", this.hessian("v"), "java.lang.String");
        byte[] extended = this.concat(wrapper, this.stringField(19, "future field"));
        assertEquals("v", this.codec.decodeResult(extended, String.class));
    }

    @Test
    public void rejectsTrailingHessianArgumentObject() throws Exception {
        // 一个参数文档不得藏有第二个未读取的对象。
        byte[] values = this.concat(this.hessian("first"), this.hessian("second"));
        byte[] wrapper = this.request("hessian4", List.of(values), List.of("java.lang.String"));
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> this.codec.decodeArguments(wrapper, new Class<?>[] { String.class }));
        assertEquals(13, failure.getCode());
    }

    @Test
    public void rejectsTrailingHessianResultObject() throws Exception {
        // 一个返回值包装只能容纳一个 Hessian 对象。
        byte[] values = this.concat(this.hessian("first"), this.hessian("second"));
        byte[] wrapper = this.result("hessian4", values, "java.lang.String");
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> this.codec.decodeResult(wrapper, String.class));
        assertEquals(13, failure.getCode());
    }

    @Test
    public void rejectsRequestSerializerWithInvalidUtf8() {
        // 字符串字段必须是合法 UTF-8，不能静默替换非法字节。
        byte[] wrapper = { 10, 1, (byte) 0xff };
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> this.codec.argumentTypes(wrapper));
        assertEquals(13, failure.getCode());
    }

    @Test
    public void rejectsResponseTypeWithInvalidUtf8() throws Exception {
        // 返回类型声明同样严格检查 UTF-8。
        byte[] prefix = this.concat(this.stringField(1, "hessian4"), this.binaryField(2, this.hessian("v")));
        byte[] wrapper = this.append(prefix, 26, 1, 255);
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> this.codec.decodeResult(wrapper, String.class));
        assertEquals(13, failure.getCode());
    }

    @Test
    public void rejectsIncomingRequestOverConfiguredLimit() throws Exception {
        // 限制检查覆盖完整信封，而不仅是每个参数值。
        byte[] wrapper = this.request("hessian4", List.of(this.hessian("payload")), List.of("java.lang.String"));
        TripleValueCodec limited = new TripleValueCodec(wrapper.length - 1, this.getClass().getClassLoader());
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> limited.argumentTypes(wrapper));
        assertEquals(8, failure.getCode());
    }

    @Test
    public void rejectsIncomingResponseOverConfiguredLimit() throws Exception {
        // 返回信封在解析前应用相同的消息上限。
        byte[] wrapper = this.result("hessian4", this.hessian("payload"), "java.lang.String");
        TripleValueCodec limited = new TripleValueCodec(wrapper.length - 1, this.getClass().getClassLoader());
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> limited.decodeResult(wrapper, String.class));
        assertEquals(8, failure.getCode());
    }

    @Test
    public void rejectsOutgoingRequestOverConfiguredLimit() {
        // 写出过程中也受容量限制，不能先生成无限大的对象文档。
        TripleValueCodec limited = new TripleValueCodec(64, this.getClass().getClassLoader());
        String value = "x".repeat(256);
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> limited.encodeArguments(new Object[] { value }, new Class<?>[] { String.class }));
        assertEquals(8, failure.getCode());
    }

    @Test
    public void rejectsOutgoingResponseOverConfiguredLimit() {
        // 返回值编码超出容量时报告 RESOURCE_EXHAUSTED。
        TripleValueCodec limited = new TripleValueCodec(64, this.getClass().getClassLoader());
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> limited.encodeResult("x".repeat(256), String.class));
        assertEquals(8, failure.getCode());
    }

    @Test
    public void rejectsMoreThanJavaMethodArgumentCapacity() throws Exception {
        // 重复的极小字段也不能使参数列表突破 JVM 的方法容量。
        List<byte[]> values = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int index = 0; index < 256; index++) {
            values.add(this.hessian(null));
            names.add("java.lang.String");
        }
        byte[] wrapper = this.request("hessian4", values, names);
        TripleValueCodec large = new TripleValueCodec(16384, this.getClass().getClassLoader());
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> large.argumentTypes(wrapper));
        assertEquals(13, failure.getCode());
    }

    @Test
    public void rejectsHessianArrayClaimingHugeAllocation() throws Exception {
        // 极小正文声明接近 2GB 的数组，必须在创建 Hessian 数组前失败。
        byte[] value = { 'X', 'I', 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff };
        byte[] wrapper = this.request("hessian4", List.of(value), List.of("[I"));
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> this.codec.decodeArguments(wrapper, new Class<?>[] { int[].class }));
        assertEquals(13, failure.getCode());
    }

    @Test
    public void rejectsHessianDocumentNestedBeyondLimit() throws Exception {
        // 连续 65 个单元素列表超过 64 层限制，不能进入库的递归读取。
        byte[] value = new byte[66];
        Arrays.fill(value, (byte) 0x79);
        value[value.length - 1] = 'N';
        byte[] wrapper = this.request("hessian4", List.of(value), List.of(List.class.getName()));
        GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                () -> this.codec.decodeArguments(wrapper, new Class<?>[] { List.class }));
        assertEquals(13, failure.getCode());
    }

    private byte[] request(String serializer, List<byte[]> values, List<String> names) throws Exception {
        // 独立 protobuf 写入器建立对端请求，不借用待测包装编码。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        output.writeString(1, serializer);
        for (byte[] value : values) {
            output.writeByteArray(2, value);
        }
        for (String name : names) {
            output.writeString(3, name);
        }
        output.flush();
        return bytes.toByteArray();
    }

    private byte[] result(String serializer, byte[] value, String name) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        output.writeString(1, serializer);
        output.writeByteArray(2, value);
        output.writeString(3, name);
        output.flush();
        return bytes.toByteArray();
    }

    private byte[] hessian(Object value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Hessian2Output output = new Hessian2Output(bytes);
        output.writeObject(value);
        output.flush();
        return bytes.toByteArray();
    }

    private Object readHessian(byte[] value, Class<?> type) throws Exception {
        Hessian2Input input = new Hessian2Input(new ByteArrayInputStream(value));
        Object decoded = input.readObject(type);
        assertEquals(-1, input.read());
        return decoded;
    }

    private List<byte[]> fields(byte[] bytes, int requested) throws Exception {
        // 按官方字段编号提取数据，不依赖生产代码的字段访问方法。
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        List<byte[]> values = new ArrayList<>();
        int tag;
        while ((tag = input.readTag()) != 0) {
            if (WireFormat.getTagFieldNumber(tag) == requested) {
                values.add(input.readByteArray());
            } else {
                input.skipField(tag);
            }
        }
        return values;
    }

    private byte[] stringField(int field, String value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        output.writeString(field, value);
        output.flush();
        return bytes.toByteArray();
    }

    private byte[] binaryField(int field, byte[] value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        output.writeByteArray(field, value);
        output.flush();
        return bytes.toByteArray();
    }

    private byte[] concat(byte[] first, byte[] second) {
        byte[] bytes = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, bytes, first.length, second.length);
        return bytes;
    }

    private byte[] append(byte[] prefix, int... suffix) {
        byte[] bytes = Arrays.copyOf(prefix, prefix.length + suffix.length);
        for (int index = 0; index < suffix.length; index++) {
            bytes[prefix.length + index] = (byte) suffix[index];
        }
        return bytes;
    }
}

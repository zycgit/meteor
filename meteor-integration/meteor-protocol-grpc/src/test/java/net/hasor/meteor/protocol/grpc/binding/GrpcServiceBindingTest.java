/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.binding;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.DynamicMessage;
import net.hasor.meteor.annotation.MetEnumValue;
import net.hasor.meteor.annotation.MetField;
import net.hasor.meteor.annotation.MetMethod;
import net.hasor.meteor.annotation.MetType;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class GrpcServiceBindingTest {
    @Rule
    public TemporaryFolder directory = new TemporaryFolder();

    @Test
    public void generatedDescriptionUsesActualProtobufFieldNamesAndNumbers() throws Exception {
        // 描述读取已编译结果，不调用 DTO 构造方法或重新生成 proto。
        GrpcServiceBinding binding = this.binding(Notes.class, null);
        ObjectNode description = binding.describeContract(JsonNodeFactory.instance.objectNode());

        // 公共 Java 名称为 text，线上字段名称和编号来自实际消息图。
        assertEquals("mapping.Notes", description.path("name").asText());
        assertEquals("Echo", description.path("methods").get(0).path("name").asText());
        JsonNode fields = description.path("types").path("mapping.Note").path("fields");
        JsonNode field = null;
        for (JsonNode candidate : fields) {
            if (candidate.path("fieldId").asInt() == 7) {
                field = candidate;
            }
        }
        assertNotNull(field);
        assertEquals("description", field.path("name").asText());
    }

    @Test
    public void contractDescriptionPlacesFieldIdsBeforeNamesAndMappingDetails() throws Exception {
        // 编译同一份公共注解，保留实际 Protobuf 字段和枚举编号。
        GrpcServiceBinding binding = this.binding(Notes.class, null);
        ObjectNode description = binding.describeContract(JsonNodeFactory.instance.objectNode());

        // 操作先展示协议名称，再展示 Java 定位和请求响应主体。
        JsonNode method = description.path("methods").get(0);
        List<String> methodFields = this.fieldNames(method);
        assertEquals("name", methodFields.get(0));
        assertEquals("javaSignature", methodFields.get(1));
        assertFalse(description.has("serviceName"));

        // 字段先展示 fieldId/name/type，再展示 JSON 名称和 Protobuf 扩展标记。
        JsonNode fields = description.path("types").path("mapping.Note").path("fields");
        JsonNode number = null;
        for (JsonNode field : fields) {
            if (field.path("fieldId").asInt() == 2) {
                number = field;
            }
        }
        assertNotNull(number);
        assertEquals("number", number.path("name").asText());
        assertEquals("INT32", number.path("type").asText());
        String[] fieldOrder = { "fieldId", "name", "type", "jsonName", "repeated", "required" };
        assertArrayEquals(fieldOrder, this.fieldNames(number).toArray(String[]::new));
        assertFalse(number.has("id"));

        // 零值同样保留契约编号，枚举成员的编号也位于名称之前。
        JsonNode unset = description.path("types").path("mapping.State").path("values").get(0);
        assertEquals("UNSET", unset.path("name").asText());
        assertEquals(0, unset.path("fieldId").asInt());
        String[] enumOrder = { "fieldId", "name" };
        assertArrayEquals(enumOrder, this.fieldNames(unset).toArray(String[]::new));
    }

    @Test
    public void importedDescriptionUsesTheBoundFileMessageGraph() throws Exception {
        // 文件绑定与自动生成入口共享描述导出，不读取原始 Java 字段猜测协议名。
        GrpcServiceBinding binding = this.binding(Notes.class, this.resource());
        ObjectNode description = binding.describeContract(JsonNodeFactory.instance.objectNode());

        // 请求和返回引用同一个已导出的文件消息，保留嵌套容器类型。
        JsonNode method = description.path("methods").get(0);
        assertEquals("mapping.Note", method.path("requestType").asText());
        assertEquals("mapping.Note", method.path("responseType").asText());
        assertTrue(description.path("types").has("mapping.Note"));
        assertFalse(method.path("clientStreaming").asBoolean());
    }

    @Test
    public void commonAnnotationsGenerateTheStandardDescriptor() throws Exception {
        // 通过共同字段号和名称直接编译，不绑定 proto 文件。
        GrpcServiceBinding binding = this.binding(Notes.class, null);
        Descriptor message = binding.service().findMethodByName("Echo").getInputType();

        // 字段位置无关声明顺序，可空标量保留 presence。
        assertEquals("mapping.Note", message.getFullName());
        assertEquals("description", message.findFieldByNumber(7).getName());
        assertTrue(message.findFieldByNumber(7).hasPresence());
        assertFalse(message.findFieldByNumber(2).hasPresence());
        assertTrue(message.findFieldByNumber(5).isMapField());
    }

    @Test
    public void fileAndAnnotationInputsProduceTheSameWireMessage() throws Exception {
        // 两个入口使用相同 Java 声明，文件使用不同字段声明顺序。
        GrpcServiceBinding generated = this.binding(Notes.class, null);
        GrpcServiceBinding fromFile = this.binding(Notes.class, this.resource());
        Note input = new Note();
        input.text = "note";
        input.count = 3;
        input.state = State.Active;

        // 编号决定二进制编码，Java 属性名和 enum ordinal 不参与线上身份。
        byte[] expected = fromFile.encode(fromFile.javaMethod("mirror"), input, true).toByteArray();
        byte[] actual = generated.encode(generated.javaMethod("mirror"), input, true).toByteArray();
        assertArrayEquals(expected, actual);
    }

    @Test
    public void privatePropertiesAndRecursiveContainersUseTheCompiledMapping() throws Exception {
        // private 属性没有 getter，公共字段注解同时声明其协议身份。
        GrpcServiceBinding binding = this.binding(Notes.class, this.resource());
        Note child = new Note();
        child.text = "child";
        Note input = new Note();
        input.text = "parent";
        input.children = List.of(child);
        input.index = Map.of("first", child);

        // 嵌套消息、map value 与普通字段复用同一张访问映射表。
        byte[] bytes = binding.encode(binding.javaMethod("mirror"), input, true).toByteArray();
        Note result = (Note) binding.decode(binding.javaMethod("mirror"), bytes, true);
        assertEquals("parent", result.text);
        assertEquals("child", result.children.get(0).text);
        assertEquals("child", result.index.get("first").text);
    }

    @Test
    public void enumNumbersDoNotFollowJavaDeclarationOrder() throws Exception {
        // Java 的首项是非零值，生成 proto3 时零值被放在最前面。
        GrpcServiceBinding binding = this.binding(Notes.class, null);
        Note input = new Note();
        input.state = State.Active;
        DynamicMessage message = binding.encode(binding.javaMethod("mirror"), input, true);

        // 使用显式数值 7，而不是首项的 ordinal 0。
        FieldDescriptor field = message.getDescriptorForType().findFieldByNumber(9);
        assertEquals(7, ((com.google.protobuf.Descriptors.EnumValueDescriptor) message.getField(field)).getNumber());
    }

    @Test
    public void optionalTextDistinguishesNullFromAnEmptyString() throws Exception {
        // 同一字段分别编码未设置值和空字符串。
        GrpcServiceBinding binding = this.binding(Notes.class, null);
        Note absent = new Note();
        Note empty = new Note();
        empty.text = "";
        DynamicMessage without = binding.encode(binding.javaMethod("mirror"), absent, true);
        DynamicMessage with = binding.encode(binding.javaMethod("mirror"), empty, true);

        // optional 的存在位独立于字符串默认值。
        assertFalse(without.hasField(without.getDescriptorForType().findFieldByNumber(7)));
        assertTrue(with.hasField(with.getDescriptorForType().findFieldByNumber(7)));
    }

    @Test
    public void annotatedCharactersGenerateStringFieldsWithBoxedPresence() {
        // 直接根据真实 char 和 Character 字段生成契约，不预先绑定 proto。
        GrpcServiceBinding binding = this.binding(CharacterNotes.class, null);
        assertNotNull(binding);
        Descriptor message = binding.descriptor(binding.javaMethod("mirror")).getInputType();

        // 两种 Java 字符使用标准 string，只有包装类型具有 optional 存在位。
        FieldDescriptor primitive = message.findFieldByNumber(1);
        FieldDescriptor boxed = message.findFieldByNumber(2);
        assertEquals(FieldDescriptor.Type.STRING, primitive.getType());
        assertEquals(FieldDescriptor.Type.STRING, boxed.getType());
        assertFalse(primitive.hasPresence());
        assertTrue(boxed.hasPresence());
    }

    @Test
    public void aPrimitiveNulCharacterRetainsItsWireValue() throws Exception {
        // NUL 是有效字符，不能被当作空字符串或缺失字段。
        GrpcServiceBinding binding = this.binding(CharacterNotes.class, null);
        CharacterNote input = new CharacterNote();
        input.letter = '\u0000';
        var method = binding.javaMethod("mirror");

        // string 的一个 NUL 字节经过标准 Protobuf 编解码后仍是 Java NUL。
        DynamicMessage message = binding.encode(method, input, true);
        assertEquals("\u0000", message.getField(message.getDescriptorForType().findFieldByNumber(1)));
        CharacterNote result = (CharacterNote) binding.decode(method, message.toByteArray(), true);
        assertEquals(input.letter, result.letter);
    }

    @Test
    public void unicodeCharactersRetainTheirDeclaredJavaTypes() throws Exception {
        // 普通字符和包装字符同时使用中文，验证自动生成的请求映射。
        GrpcServiceBinding binding = this.binding(CharacterNotes.class, null);
        CharacterNote input = new CharacterNote();
        input.letter = '中';
        input.optional = '文';
        var method = binding.javaMethod("mirror");

        // 返回真实 DTO，不能把 Character 字段退化成 String。
        byte[] bytes = binding.encode(method, input, true).toByteArray();
        CharacterNote result = (CharacterNote) binding.decode(method, bytes, true);
        assertEquals(input.letter, result.letter);
        assertEquals(input.optional, result.optional);
    }

    @Test
    public void anAbsentBoxedCharacterRetainsNull() throws Exception {
        // 包装字符未赋值时，不应强制补成 NUL 或空字符串。
        GrpcServiceBinding binding = this.binding(CharacterNotes.class, null);
        CharacterNote input = new CharacterNote();
        var method = binding.javaMethod("mirror");
        DynamicMessage message = binding.encode(method, input, true);

        // optional 存在位和 Java 空值共同验证缺失语义。
        assertFalse(message.hasField(message.getDescriptorForType().findFieldByNumber(2)));
        CharacterNote result = (CharacterNote) binding.decode(method, message.toByteArray(), true);
        assertNull(result.optional);
    }

    @Test
    public void aBoxedNulCharacterRemainsPresentInAResponse() throws Exception {
        // 显式 NUL 与包装类型的 null 不同，响应映射也必须保留存在位。
        GrpcServiceBinding binding = this.binding(CharacterNotes.class, null);
        CharacterNote input = new CharacterNote();
        input.optional = '\u0000';
        var method = binding.javaMethod("mirror");
        DynamicMessage message = binding.encode(method, input, false);

        // 字段已设置，回读仍是非空 Character。
        assertTrue(message.hasField(message.getDescriptorForType().findFieldByNumber(2)));
        CharacterNote result = (CharacterNote) binding.decode(method, message.toByteArray(), false);
        assertEquals(input.optional, result.optional);
    }

    @Test
    public void anOmittedPrimitiveCharacterUsesTheJavaNulDefault() throws Exception {
        // 外部对端未传普通 string 字段时，Protobuf 默认值为空文本。
        GrpcServiceBinding binding = this.binding(CharacterNotes.class, null);

        // primitive 使用 Java NUL 默认值，包装类型的缺失状态保持为 null。
        CharacterNote result = (CharacterNote) binding.decode(binding.javaMethod("mirror"), new byte[0], true);
        assertEquals('\u0000', result.letter);
        assertNull(result.optional);
    }

    @Test
    public void multipleWireCharactersCannotBindOneJavaCharacter() {
        // 外部 string 虽然有效，但多字符文本不能压缩为一个 Java char。
        GrpcServiceBinding binding = this.binding(CharacterNotes.class, null);
        var method = binding.javaMethod("mirror");
        Descriptor descriptor = binding.descriptor(method).getInputType();
        DynamicMessage message = DynamicMessage.newBuilder(descriptor) //
                .setField(descriptor.findFieldByNumber(1), "中文").build();

        // 解码明确拒绝不匹配长度，不截断或接受首字符。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> binding.decode(method, message.toByteArray(), true));
        assertTrue(failure.getMessage().contains("one non-surrogate character"));
    }

    @Test
    public void anEmptyPresentStringCannotBindABoxedCharacter() {
        // Character 的已设置字段必须包含字符，空文本不能与缺失字段混淆。
        GrpcServiceBinding binding = this.binding(CharacterNotes.class, null);
        var method = binding.javaMethod("mirror");
        Descriptor descriptor = binding.descriptor(method).getInputType();
        DynamicMessage message = DynamicMessage.newBuilder(descriptor) //
                .setField(descriptor.findFieldByNumber(2), "").build();

        // 保留 null 仅表示 optional 未设置的语义。
        assertTrue(message.hasField(descriptor.findFieldByNumber(2)));
        assertThrows(IllegalArgumentException.class, () -> binding.decode(method, message.toByteArray(), true));
    }

    @Test
    public void anIsolatedHighSurrogateIsRejectedBeforeProtobufEncoding() {
        // 单个高 surrogate 没有对应 Unicode 字符，不能写出替换字符。
        GrpcServiceBinding binding = this.binding(CharacterNotes.class, null);
        CharacterNote input = new CharacterNote();
        input.letter = '\uD800';

        // 在写入 Protobuf 之前明确报告字符不合法。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> binding.encode(binding.javaMethod("mirror"), input, true));
        assertTrue(failure.getMessage().contains("one non-surrogate character"));
    }

    @Test
    public void anIsolatedLowSurrogateIsRejectedBeforeProtobufEncoding() {
        // 包装类型同样不能把孤立低 surrogate 写成有损 UTF-8。
        GrpcServiceBinding binding = this.binding(CharacterNotes.class, null);
        CharacterNote input = new CharacterNote();
        input.optional = '\uDC00';

        // 沿响应映射检查包装字符，错误在本地编码阶段可见。
        assertThrows(IllegalArgumentException.class, //
                () -> binding.encode(binding.javaMethod("mirror"), input, false));
    }

    @Test
    public void generatedCharacterMapKeysPreserveAsciiAndChinese() throws Exception {
        // 自动契约使用 string key，Java 声明仍保持 Character。
        GrpcServiceBinding binding = this.binding(CharacterNotes.class, null);
        CharacterNote input = new CharacterNote();
        input.labels = Map.of('A', 1, '中', 2);
        var method = binding.javaMethod("mirror");

        // 普通字符键和中文键都不能被转换成字符串类型的 Java 键。
        byte[] bytes = binding.encode(method, input, true).toByteArray();
        CharacterNote result = (CharacterNote) binding.decode(method, bytes, true);
        assertEquals(input.labels, result.labels);
    }

    @Test
    public void fileCharacterMapKeysPreserveAsciiAndChinese() throws Exception {
        // 已有 proto 文件同样将 string key 绑定到声明的 Character key。
        GrpcServiceBinding binding = this.binding(CharacterNotes.class, this.characterResource("string"));
        CharacterNote input = new CharacterNote();
        input.labels = Map.of('A', 1, '中', 2);
        var method = binding.javaMethod("mirror");

        // 文件入口和自动入口复用同一份 map 键检查及 Java 回读。
        byte[] bytes = binding.encode(method, input, false).toByteArray();
        CharacterNote result = (CharacterNote) binding.decode(method, bytes, false);
        assertEquals(input.labels, result.labels);
    }

    @Test
    public void generatedCharacterMapKeysRejectAnIsolatedHighSurrogate() {
        // map value 为普通 int，没有 value 子映射也必须检查字符键。
        GrpcServiceBinding binding = this.binding(CharacterNotes.class, null);
        CharacterNote input = new CharacterNote();
        input.labels = Map.of('\uD800', 1);

        // 不允许 Protobuf UTF-8 写出阶段静默替换非法键。
        assertThrows(IllegalArgumentException.class, //
                () -> binding.encode(binding.javaMethod("mirror"), input, true));
    }

    @Test
    public void fileCharacterMapKeysRejectAnIsolatedLowSurrogate() throws Exception {
        // 已有文件没有 Java 专属字符约束，绑定层负责检查真实 Character 键。
        GrpcServiceBinding binding = this.binding(CharacterNotes.class, this.characterResource("string"));
        CharacterNote input = new CharacterNote();
        input.labels = Map.of('\uDC00', 1);

        // 文件绑定不能绕开与自动契约相同的有损 UTF-8 检查。
        assertThrows(IllegalArgumentException.class, //
                () -> binding.encode(binding.javaMethod("mirror"), input, false));
    }

    @Test
    public void generatedCharacterMapKeysRejectMultipleWireCharacters() {
        // 外部 map string key 可以有多个字符，但 Java Character 不能承接。
        GrpcServiceBinding binding = this.binding(CharacterNotes.class, null);
        DynamicMessage message = this.characterMapMessage(binding, "中文");

        // 解码拒绝多字符键，不能只取首字符而覆盖另一条记录。
        assertThrows(IllegalArgumentException.class, //
                () -> binding.decode(binding.javaMethod("mirror"), message.toByteArray(), true));
    }

    @Test
    public void fileCharacterMapKeysRejectAnEmptyWireKey() throws Exception {
        // string map 的空 key 合法，但它不能表示一个 Java Character。
        GrpcServiceBinding binding = this.binding(CharacterNotes.class, this.characterResource("string"));
        DynamicMessage message = this.characterMapMessage(binding, "");

        // 空键不能被补成 NUL，也不能被当作不存在的键。
        assertThrows(IllegalArgumentException.class, //
                () -> binding.decode(binding.javaMethod("mirror"), message.toByteArray(), true));
    }

    @Test
    public void aPrimitiveCharacterCannotBindAnInt32FileField() {
        // Java char 只接受 string，不因为属于 primitive 就获得整数映射。
        assertThrows(IllegalArgumentException.class, //
                () -> this.binding(CharacterNotes.class, this.characterResource("int32")));
    }

    @Test
    public void aPrimitiveCharacterCannotBindAnInt64FileField() {
        // int64 文件字段不能隐式承接 Java char 的代码单元数值。
        assertThrows(IllegalArgumentException.class, //
                () -> this.binding(CharacterNotes.class, this.characterResource("int64")));
    }

    @Test
    public void aPrimitiveCharacterCannotBindAFloatFileField() {
        // 浮点契约不接受看起来像数字的字符。
        assertThrows(IllegalArgumentException.class, //
                () -> this.binding(CharacterNotes.class, this.characterResource("float")));
    }

    @Test
    public void aPrimitiveCharacterCannotBindADoubleFileField() {
        // double 契约同样在准备阶段拒绝字符，而不是推迟到某次调用。
        assertThrows(IllegalArgumentException.class, //
                () -> this.binding(CharacterNotes.class, this.characterResource("double")));
    }

    @Test
    public void explicitFieldNameAndNumberMustSelectTheSameFileMember() throws Exception {
        // 字段号 7 指向 description，但注解显式声明另一个名称。
        GrpcProtoResource resource = this.resource();

        // 文件是线上契约，不用注解覆盖冲突编号。
        assertThrows(IllegalArgumentException.class, () -> this.binding(ConflictNotes.class, resource));
    }

    @Test
    public void unnumberedDtoCannotGenerateAnImplicitWireContract() throws Exception {
        // 普通 Bean 没有稳定字段号，也没有提供已有文件。
        GrpcServiceBinding binding = this.binding(UnnumberedNotes.class, null);

        // 只拒绝当前协议，不按字段发现顺序猜测编号。
        assertNull(binding);
    }

    @Test
    public void explicitlyNamedForeignResourceIsRejected() {
        // 当前协议名明确绑定了错误类型，不能回退到注解生成。
        assertThrows(IllegalArgumentException.class, () -> this.binding(Notes.class, new Object()));
    }

    @Test
    public void generatedContractCanBeExportedAndLoadedByTheFileEntry() throws Exception {
        // 保存注解编译出的标准 proto3 文件，不把描述符二进制当作源文件。
        GrpcServiceBinding generated = this.binding(Notes.class, null);
        Path root = this.directory.newFolder().toPath();
        for (Map.Entry<String, String> source : generated.schema().getSources().entrySet()) {
            Files.writeString(root.resolve(source.getKey()), source.getValue());
        }
        GrpcProtoResource resource = new GrpcProtoResource(root.resolve("Notes.proto").toUri().toURL());

        // 文件入口重新解析后仍使用相同线上字段身份。
        GrpcServiceBinding loaded = this.binding(Notes.class, resource);
        Note input = new Note();
        input.state = State.Active;
        input.text = "exported";
        byte[] expected = generated.encode(generated.javaMethod("mirror"), input, true).toByteArray();
        byte[] actual = loaded.encode(loaded.javaMethod("mirror"), input, true).toByteArray();
        assertArrayEquals(expected, actual);
    }

    @Test
    public void fileResourceExportIncludesTheOriginalImportedDocument() throws Exception {
        // 入口文件引用同目录 message.proto。
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/echo.proto"));

        // 导出保留原始文件内容及其依赖名称。
        assertTrue(resource.getSources().get("echo.proto").contains("import \"message.proto\";"));
        assertTrue(resource.getSources().get("message.proto").contains("string value = 7;"));
    }

    @Test
    public void absentRepeatedValuesAreReadAsEmptyContainers() throws Exception {
        // 空消息没有 repeated 或 map 成员，线上无法表示它们的 null presence。
        GrpcServiceBinding binding = this.binding(Notes.class, null);
        Note result = (Note) binding.decode(binding.javaMethod("mirror"), new byte[0], true);

        // 按 proto 约定归一为空，optional 的引用值仍未设置。
        assertTrue(result.children.isEmpty());
        assertTrue(result.index.isEmpty());
        assertNull(result.text);
    }

    @Test
    public void genericDtoPropertiesAreResolvedBeforeDescriptorGeneration() throws Exception {
        // 同一泛型 DTO 的 value 和 List value 使用真实的 Note 类型。
        GrpcServiceBinding binding = this.binding(GenericNotes.class, null);
        Box<Note> input = new Box<>();
        input.value = new Note();
        input.value.text = "generic";

        // 不以 Object 替代泛型，也不创建另一套运行时字段扫描。
        byte[] bytes = binding.encode(binding.javaMethod("mirror"), input, true).toByteArray();
        Box<?> result = (Box<?>) binding.decode(binding.javaMethod("mirror"), bytes, true);
        assertEquals("generic", ((Note) result.value).text);
    }

    @Test
    public void reservedProtobufFieldNumbersAreRejected() {
        // 保留区字段号不能被代码生成入口分配。
        assertThrows(IllegalArgumentException.class, () -> this.binding(ReservedNotes.class, null));
    }

    @Test
    public void enumWithoutExplicitNumbersCannotGenerateAContract() {
        // 没有 MetEnumValue 时不能把声明顺序当作稳定编号。
        assertNull(this.binding(UnnumberedEnumNotes.class, null));
    }

    @Test
    public void fieldAndAccessorCanSupplyDifferentPartsOfOneDeclaration() throws Exception {
        // 字段声明编号，getter 声明名称，两者属于同一个 Java 属性。
        GrpcServiceBinding binding = this.binding(SplitNotes.class, null);
        Split input = new Split();
        input.text = "combined";

        // 合并后得到 description=7，而不是两份线上字段。
        DynamicMessage message = binding.encode(binding.javaMethod("mirror"), input, true);
        FieldDescriptor field = message.getDescriptorForType().findFieldByNumber(7);
        assertEquals("description", field.getName());
        assertEquals("combined", message.getField(field));
    }

    @Test
    public void conflictingFieldAndAccessorIdsAreRejected() {
        // 同一属性上的两份非零编号不允许相互覆盖。
        assertThrows(IllegalArgumentException.class, () -> this.binding(ConflictingAccessorNotes.class, null));
    }

    @Test
    public void fileEnumCanBindAnExplicitNumericJavaProperty() throws Exception {
        // 已有 proto 决定字段为 enum，Java 可以直接承接其数值。
        GrpcServiceBinding binding = this.binding(NumericNotes.class, this.resource());
        NumericNote input = new NumericNote();
        input.state = 7;

        // ProtoJSON 的符号名称回读为契约中的数值，不按 Java ordinal 猜测。
        byte[] bytes = binding.encode(binding.javaMethod("mirror"), input, true).toByteArray();
        NumericNote result = (NumericNote) binding.decode(binding.javaMethod("mirror"), bytes, true);
        assertEquals(7, result.state);
    }

    @Test
    public void commonFieldMappingPreservesTheJacksonCreator() throws Exception {
        // 不可变 DTO 通过正常 Jackson Creator 接收回读值。
        GrpcServiceBinding binding = this.binding(CreatorNotes.class, this.resource());
        ImmutableNote input = new ImmutableNote("creator");

        // 编解码沿同一字段表执行，构造参数名仍属于 Java 属性绑定。
        byte[] bytes = binding.encode(binding.javaMethod("mirror"), input, true).toByteArray();
        ImmutableNote result = (ImmutableNote) binding.decode(binding.javaMethod("mirror"), bytes, true);
        assertEquals("creator", result.text);
    }

    @Test
    public void contractPreparationDoesNotInvokeTheJacksonCreator() throws Exception {
        // 在准备前记录业务构造调用数。
        ImmutableNote.CREATED.set(0);
        GrpcServiceBinding binding = this.binding(CreatorNotes.class, this.resource());

        // 静态定义准备只读取声明，首个业务对象由实际解码创建。
        assertNotNull(binding);
        assertEquals(0, ImmutableNote.CREATED.get());
    }

    private List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private <T> GrpcServiceBinding binding(Class<T> type, Object resource) {
        ServiceDomain<T> service = new ServiceDomain<>(type);
        service.setServiceType(MetServiceType.Provider);
        service.setSerializeType("Protobuf");
        service.putAliasName("grpc", "mapping.Notes");
        ProtocolConfig config = ProtocolConfig.newConfig("grpc", "grpc", Map.of());
        ProtocolMeta meta = new ProtocolMeta(service, null, resource, Map.of());
        return GrpcServiceBinding.prepare(config, meta, "Protobuf");
    }

    private GrpcProtoResource resource() throws Exception {
        return new GrpcProtoResource(this.getClass().getResource("/proto/mapping/service.proto"));
    }

    private GrpcProtoResource characterResource(String primitiveType) throws Exception {
        // 同一份外部声明用于正常 string 绑定和不兼容数值字段校验。
        String source = """
                syntax = "proto3";
                package mapping;
                message CharacterNote {
                  %s letter = 1;
                  optional string optional = 2;
                  map<string, int32> labels = 3;
                }
                service Notes { rpc mirror (CharacterNote) returns (CharacterNote); }
                """.formatted(primitiveType);
        Path file = this.directory.newFolder().toPath().resolve("characters.proto");
        Files.writeString(file, source);
        return new GrpcProtoResource(file.toUri().toURL());
    }

    private DynamicMessage characterMapMessage(GrpcServiceBinding binding, String key) {
        // 使用官方消息模型模拟外部对端的原始 map key。
        Descriptor descriptor = binding.descriptor(binding.javaMethod("mirror")).getInputType();
        FieldDescriptor labels = descriptor.findFieldByNumber(3);
        Descriptor entry = labels.getMessageType();
        DynamicMessage item = DynamicMessage.newBuilder(entry) //
                .setField(entry.findFieldByNumber(1), key) //
                .setField(entry.findFieldByNumber(2), 1).build();
        return DynamicMessage.newBuilder(descriptor).addRepeatedField(labels, item).build();
    }

    interface Notes {
        @MetMethod(name = "Echo")
        Note mirror(Note input);
    }

    interface ConflictNotes {
        @MetMethod(name = "Echo")
        Conflict mirror(Conflict input);
    }

    interface UnnumberedNotes {
        Plain mirror(Plain input);
    }

    interface GenericNotes {
        @MetMethod(name = "Echo")
        Box<Note> mirror(Box<Note> input);
    }

    interface ReservedNotes {
        Reserved mirror(Reserved input);
    }

    interface UnnumberedEnumNotes {
        UnnumberedEnum mirror(UnnumberedEnum input);
    }

    interface SplitNotes {
        Split mirror(Split input);
    }

    interface ConflictingAccessorNotes {
        ConflictingAccessor mirror(ConflictingAccessor input);
    }

    interface NumericNotes {
        @MetMethod(name = "Echo")
        NumericNote mirror(NumericNote input);
    }

    interface CreatorNotes {
        @MetMethod(name = "Echo")
        ImmutableNote mirror(ImmutableNote input);
    }

    interface CharacterNotes {
        CharacterNote mirror(CharacterNote input);
    }

    public static class CharacterNote {
        @MetField(id = 1)
        public char                    letter;
        @MetField(id = 2)
        public Character               optional;
        @MetField(id = 3)
        public Map<Character, Integer> labels;
    }

    @MetType(name = "Note")
    public static class Note {
        @MetField(name = "description", id = 7)
        private String            text;
        @MetField(name = "number", id = 2)
        private int               count;
        @MetField(id = 4)
        private List<Note>        children;
        @MetField(name = "by_name", id = 5)
        private Map<String, Note> index;
        @MetField(name = "status", id = 9)
        private State             state;
    }

    @MetType(name = "State")
    public enum State {
        @MetEnumValue(name = "READY", id = 7) Active,
        @MetEnumValue(name = "UNSET", id = 0) None
    }

    @MetType(name = "Note")
    public static class Conflict {
        @MetField(name = "number", id = 7)
        private String text;
    }

    public static class Plain {
        public String text;
    }

    public static class Box<T> {
        @MetField(id = 1)
        private T value;
    }

    public static class Reserved {
        @MetField(id = 19000)
        private String value;
    }

    public static class UnnumberedEnum {
        @MetField(id = 1)
        private MissingIds value;
    }

    public enum MissingIds {
        First,
        Second
    }

    public static class Split {
        @MetField(id = 7)
        private String text;

        @MetField(name = "description")
        public String getText() {
            return this.text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }

    public static class ConflictingAccessor {
        @MetField(id = 1)
        private String text;

        @MetField(id = 2)
        public String getText() {
            return this.text;
        }
    }

    @MetType(name = "Note")
    public static class NumericNote {
        @MetField(name = "status", id = 9)
        private int state;
    }

    @MetType(name = "Note")
    public static class ImmutableNote {
        private static final AtomicInteger CREATED = new AtomicInteger();
        @MetField(name = "description", id = 7)
        private final        String        text;

        @JsonCreator
        public ImmutableNote(@JsonProperty("text") String text) {
            CREATED.incrementAndGet();
            this.text = text;
        }
    }
}

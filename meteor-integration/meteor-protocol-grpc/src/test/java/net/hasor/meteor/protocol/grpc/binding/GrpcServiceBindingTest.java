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
        assertEquals("mapping.Notes", description.path("serviceName").asText());
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

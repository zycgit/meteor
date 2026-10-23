/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.codec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.cobble.reflect.resolvable.ResolvableType;
import net.hasor.meteor.annotation.MetField;
import net.hasor.meteor.annotation.MetMethod;
import net.hasor.meteor.annotation.MetParam;
import net.hasor.meteor.annotation.MetType;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.protocol.thrift.ThriftIdlResource;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TField;
import org.apache.thrift.protocol.TStruct;
import org.apache.thrift.protocol.TType;
import org.apache.thrift.transport.TMemoryBuffer;
import org.junit.Test;
import static org.junit.Assert.*;

/** 文件声明与 Java 绑定的边界；不启动网络或外部编译器。 */
public class ThriftIdlBindingTest {
    @Test
    public void annotatedDescriptionSeparatesJavaIndexAndContractFieldId() {
        // 注解编号为 3，Java 参数位于下标 0。
        ThriftServiceSchema schema = ThriftServiceSchema.annotated(new ServiceDomain<>(Records.class));
        ObjectNode description = schema.describeContract(JsonNodeFactory.instance.objectNode());
        JsonNode parameter = description.path("methods").get(0).path("parameters").get(0);

        // 编号和名称来自真实 Thrift 字段，不跟随 Java 参数位置变化。
        assertEquals("Records", description.path("name").asText());
        assertFalse(description.has("serviceName"));
        assertEquals(0, parameter.path("argIndex").asInt());
        assertEquals(3, parameter.path("fieldId").asInt());
        assertFalse(parameter.has("id"));
        List<String> parameterFields = new ArrayList<>();
        parameter.fieldNames().forEachRemaining(parameterFields::add);
        assertEquals(List.of("fieldId", "name", "argIndex", "type", "requirement"), parameterFields);
        String type = parameter.path("type").asText();
        assertEquals("WireRecord", description.path("types").path(type).path("name").asText());
    }

    @Test
    public void importedDescriptionUsesTheCompiledIdlFields() throws Exception {
        // 通过已有文件创建与运行期编解码相同的定义。
        ThriftServiceSchema schema = this.resource("binding").bind(new ServiceDomain<>(Records.class));
        ObjectNode description = schema.describeContract(JsonNodeFactory.instance.objectNode());
        JsonNode method = description.path("methods").get(0);
        JsonNode parameter = method.path("parameters").get(0);
        JsonNode fields = description.path("types").path(parameter.path("type").asText()).path("fields");

        // 文件字段号 7 对应 remote_count，不复制 Java 的 count 属性名。
        assertEquals("BindingService", description.path("name").asText());
        assertEquals("Echo", method.path("name").asText());
        List<String> methodFields = new ArrayList<>();
        method.fieldNames().forEachRemaining(methodFields::add);
        assertEquals(List.of("name", "javaSignature", "parameters", "responseType", "message"), methodFields);
        JsonNode field = null;
        for (JsonNode candidate : fields) {
            if (candidate.path("fieldId").asInt() == 7) {
                field = candidate;
            }
        }
        assertNotNull(field);
        assertEquals("remote_count", field.path("name").asText());
        assertEquals("required", field.path("requirement").asText());
        assertFalse(field.has("id"));
        List<String> propertyFields = new ArrayList<>();
        field.fieldNames().forEachRemaining(propertyFields::add);
        assertEquals(List.of("fieldId", "name", "type", "requirement"), propertyFields);
    }

    @Test
    public void differentGenericInstancesExportDistinctReadableTypeNames() throws Exception {
        // 同一个泛型 DTO 分别绑定 String 和 Integer，不通过 hash 区分契约身份。
        ServiceDomain<GenericRecords> service = new ServiceDomain<>(GenericRecords.class);
        ThriftServiceSchema original = ThriftServiceSchema.annotated(service);
        Path directory = Path.of("build", "contracts", "generics");
        Files.createDirectories(directory);
        Path file = directory.resolve("GenericRecords.thrift");
        Files.writeString(file, original.getSources().get("GenericRecords.thrift"));

        // 生成的名称同时接受 MetType 基名与泛型实参，文件入口恢复同一组字段类型。
        ThriftServiceSchema restored = new ThriftIdlResource(file.toUri().toURL()).bind(service);
        ThriftType pair = restored.javaMethod("mirror").args.fields.get(0).type;
        assertEquals("BoxOfString", pair.fieldsById.get((short) 1).type.name);
        assertEquals("BoxOfInteger", pair.fieldsById.get((short) 2).type.name);
    }

    @Test
    public void invalidDefaultIsRejectedBeforeAnyDtoIsCreated() throws Exception {
        // 文件将整数作为 string 默认值，不能等到首次缺失字段时才发现契约错误。
        ThriftIdlResource resource = this.resource("invalid-default");
        ServiceDomain<Records> service = new ServiceDomain<>(Records.class);

        assertThrows(IllegalArgumentException.class, () -> resource.bind(service));
    }

    @Test
    public void annotationDefinitionExportsARebindableIdlFile() throws Exception {
        // 注解直接生成运行期定义，导出时不重新编译 Java 声明。
        ServiceDomain<Records> service = new ServiceDomain<>(Records.class);
        ThriftServiceSchema original = ThriftServiceSchema.annotated(service);
        Map<String, String> sources = original.getSources();
        Path directory = Path.of("build", "contracts", "annotations");
        Files.createDirectories(directory);
        Path file = directory.resolve("Records.thrift");
        Files.writeString(file, sources.get("Records.thrift"));

        // 导出的文件再次绑定原接口，保留字段号、名称和参数位置。
        ThriftServiceSchema restored = new ThriftIdlResource(file.toUri().toURL()).bind(service);
        ThriftType value = restored.wireMethod("Echo").args.fields.get(0).type;
        assertEquals("WireRecord", value.name);
        assertEquals("remote_count", value.fieldsById.get((short) 7).field.name);
        assertEquals(3, restored.wireMethod("Echo").args.fields.get(0).field.id);
    }

    @Test
    public void importedDefinitionExportsItsOriginalIncludeGraph() throws Exception {
        // 文件入口保留原有 include、requirement、默认值及全部文档内容。
        ThriftIdlResource resource = this.resource("child");
        Map<String, String> sources = resource.getSources();

        // 重新放置资源图仍可由同一个入口加载，不扁平化或丢掉依赖。
        Path directory = Path.of("build", "contracts", "imported");
        Files.createDirectories(directory);
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Files.writeString(directory.resolve(source.getKey()), source.getValue());
        }
        ThriftIdlResource restored = new ThriftIdlResource(directory.resolve("child.thrift").toUri().toURL());
        assertEquals(sources, restored.getSources());
        ThriftServiceSchema schema = restored.bind(new ServiceDomain<>(Records.class));
        assertEquals("Child", schema.getName());
    }

    @Test
    public void methodAliasResolvesToTheOriginalJavaMethod() throws Exception {
        // 方法名、参数编号和 DTO 字段名同时与 Java 声明解耦。
        ThriftServiceSchema schema = this.resource("binding").bind(new ServiceDomain<>(Records.class));

        // 线上名称映回原方法，参数数组仍使用 Java 下标。
        ThriftMethodBinding method = schema.wireMethod("Echo");
        assertEquals("echo", method.method.getName());
        assertEquals(3, method.args.fields.get(0).field.id);
        assertEquals(0, method.args.fields.get(0).position);
        ThriftType dto = method.args.fields.get(0).type;
        assertEquals("WireRecord", dto.name);
        assertEquals("remote_count", dto.fieldsById.get((short) 7).field.name);
        assertEquals("count", dto.fieldsById.get((short) 7).property.getName());
    }

    @Test
    public void fileAndAnnotationBindingsUseTheSameWireCodec() throws Exception {
        // 同一 DTO 分别从文件和注解建立定义，不复制编码实现。
        ThriftServiceSchema schema = this.resource("binding").bind(new ServiceDomain<>(Records.class));
        ThriftType fileType = schema.javaMethod("echo").args.fields.get(0).type;
        ThriftType annotationType = new ThriftSchemaBuilder().javaType( //
                ResolvableType.forClass(RecordDto.class));
        RecordDto input = new RecordDto();
        input.count = 9;
        input.description = "same";
        input.values = List.of(3, 4);

        // Binary 报文中的字段顺序、编号和实际内容一致。
        TMemoryBuffer fromFile = new TMemoryBuffer(512);
        ThriftValueCodec.write(new TBinaryProtocol(fromFile), fileType, input);
        TMemoryBuffer fromAnnotation = new TMemoryBuffer(512);
        ThriftValueCodec.write(new TBinaryProtocol(fromAnnotation), annotationType, input);
        byte[] expected = Arrays.copyOf(fromFile.getArray(), fromFile.length());
        byte[] actual = Arrays.copyOf(fromAnnotation.getArray(), fromAnnotation.length());
        assertArrayEquals(expected, actual);
    }

    @Test
    public void omittedFieldsUseTheIdlDefaults() throws Exception {
        // 报文只携带必填计数，文本和集合默认值来自 IDL。
        ThriftServiceSchema schema = this.resource("binding").bind(new ServiceDomain<>(Records.class));
        ThriftType type = schema.javaMethod("echo").result.fields.get(0).type;

        RecordDto decoded = this.readCount(type);

        assertEquals("initial", decoded.description);
        assertEquals(List.of(1, 2), decoded.values);
    }

    @Test
    public void mutableDefaultsAreIndependentForEachDecodedObject() throws Exception {
        // 两次解码得到同一个契约默认值，但不能共享同一个 List 实例。
        ThriftServiceSchema schema = this.resource("binding").bind(new ServiceDomain<>(Records.class));
        ThriftType type = schema.javaMethod("echo").result.fields.get(0).type;
        RecordDto first = this.readCount(type);
        RecordDto second = this.readCount(type);

        first.values.add(9);

        assertEquals(List.of(1, 2), second.values);
        assertNotSame(first.values, second.values);
    }

    @Test
    public void includeAndServiceInheritanceKeepTheParentContract() throws Exception {
        // 子文档只声明继承，方法和递归类型引用仍归父文档。
        ThriftIdlResource resource = this.resource("child");
        ThriftServiceSchema schema = resource.bind(new ServiceDomain<>(Records.class));

        assertEquals("Child", schema.getName());
        assertEquals("echo", schema.wireMethod("Echo").method.getName());
        assertTrue(resource.getDocument().includes.containsKey("binding"));
    }

    @Test
    public void explicitParameterNameAndNumberMustSelectTheSameField() throws Exception {
        // 参数编号存在，但显式名称指向另一个目标时不能回退到编号。
        ThriftIdlResource resource = this.resource("binding");
        ServiceDomain<WrongParameter> service = new ServiceDomain<>(WrongParameter.class);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> resource.bind(service));
        assertTrue(error.getMessage().contains("name/id"));
    }

    @Test
    public void malformedIdlReportsTheSourceAndLine() {
        // 语法损坏属于显式契约错误，不是协议不支持。
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, //
                () -> this.resource("invalid"));

        assertTrue(error.getMessage().contains("invalid.thrift:2:"));
    }

    private ThriftIdlResource resource(String name) throws Exception {
        return new ThriftIdlResource(this.getClass().getResource("/thrift/" + name + ".thrift"));
    }

    private RecordDto readCount(ThriftType type) throws Exception {
        TMemoryBuffer buffer = new TMemoryBuffer(128);
        TBinaryProtocol protocol = new TBinaryProtocol(buffer);
        protocol.writeStructBegin(new TStruct("WireRecord"));
        protocol.writeFieldBegin(new TField("remote_count", TType.I32, (short) 7));
        protocol.writeI32(9);
        protocol.writeFieldEnd();
        protocol.writeFieldStop();
        protocol.writeStructEnd();
        return (RecordDto) ThriftValueCodec.read(protocol, type);
    }

    public interface Records {
        @MetMethod(name = "Echo")
        RecordDto echo(@MetParam(id = 3) RecordDto input);
    }

    public interface WrongParameter {
        @MetMethod(name = "Echo")
        RecordDto echo(@MetParam(name = "other", id = 3) RecordDto input);
    }

    @MetType(name = "WireRecord")
    public static class RecordDto {
        @MetField(name = "remote_count", id = 7)
        private int           count;
        @MetField(id = 2)
        private String        description;
        @MetField(id = 4)
        private List<Integer> values;
    }

    public interface GenericRecords {
        Pair mirror(@MetParam(id = 1) Pair value);
    }

    public static class Pair {
        @MetField(id = 1)
        public Box<String>  text;
        @MetField(id = 2)
        public Box<Integer> number;
    }

    @MetType(name = "Box")
    public static class Box<T> {
        @MetField(id = 1)
        public T value;
    }

}

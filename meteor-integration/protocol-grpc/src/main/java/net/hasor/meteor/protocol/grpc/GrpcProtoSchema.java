/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.*;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import com.squareup.wire.schema.*;
import com.squareup.wire.schema.internal.SchemaEncoder;
import com.squareup.wire.schema.internal.parser.ProtoFileElement;
import com.squareup.wire.schema.internal.parser.ProtoParser;
import net.hasor.cobble.StringUtils;

/** gRPC、Connect 和 Triple 内部共享的已解析契约；仅为协议实现协作公开，不属于业务配置 API。 */
public final class GrpcProtoSchema {
    private static final String                         RESOURCE_ROOT = "/META-INF/meteor/protocol/";
    private static final ObjectReader                   JSON          = JsonMapper.builder() //
            .disable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build().readerFor(JsonNode.class);
    private final        Map<String, ServiceDescriptor> services;
    private final        List<FileDescriptor>           fileDescriptors;
    private final        Map<String, String>            sources;

    private GrpcProtoSchema(Collection<FileDescriptor> files, Map<String, String> sources) {
        Map<String, ServiceDescriptor> services = new LinkedHashMap<>();
        for (FileDescriptor file : files) {
            for (ServiceDescriptor service : file.getServices()) {
                services.put(service.getFullName(), service);
            }
        }
        this.services = Map.copyOf(services);
        Comparator<FileDescriptor> byName = Comparator.comparing(FileDescriptor::getName);
        this.fileDescriptors = files.stream().sorted(byName).toList();
        this.sources = Map.copyOf(sources);
    }

    /** 将已编译的标准描述图交给文件入口与注解入口共同使用。 */
    public static GrpcProtoSchema fromDescriptors(Collection<FileDescriptor> files, Map<String, String> sources) {
        return new GrpcProtoSchema(files, sources);
    }

    static GrpcProtoSchema load(URL source) throws IOException {
        if (source == null) {
            throw new IllegalArgumentException("gRPC proto resource URL is required");
        }

        // 内置定义以协议资源目录为导入根，业务定义沿用入口文件所在目录。
        String location = source.toExternalForm();
        int resourceRoot = location.lastIndexOf(RESOURCE_ROOT);
        URL root;
        String name;
        if (resourceRoot >= 0) {
            int nameStart = resourceRoot + RESOURCE_ROOT.length();
            root = new URL(location.substring(0, nameStart));
            name = location.substring(nameStart);
        } else {
            root = new URL(source, ".");
            String path = source.getPath();
            name = path.substring(path.lastIndexOf('/') + 1);
        }
        if (StringUtils.isBlank(name)) {
            throw new IOException("gRPC proto resource must identify an entry file: " + source);
        }

        Path directory = Files.createTempDirectory("meteor-proto-");
        try {
            // 将 URL 资源及相对导入交给同一个 schema 根目录，兼容文件和 jar 资源。
            copy(root, name, directory, new HashSet<>());
            SchemaLoader loader = new SchemaLoader(FileSystems.getDefault());
            loader.setLoadExhaustively(true);
            Location entry = Location.get(directory.toString(), name);
            loader.initRoots(List.of(entry), List.of(Location.get(directory.toString())));
            Schema schema = loader.loadSchema();

            // Wire 负责语法和引用校验，Protobuf 负责标准描述信息与二进制编码。
            SchemaEncoder encoder = new SchemaEncoder(schema);
            Map<String, FileDescriptorProto> files = new HashMap<>();
            for (ProtoFile file : schema.getProtoFiles()) {
                byte[] descriptorBytes = encoder.encode(file).toByteArray();
                FileDescriptorProto.Builder encoded = FileDescriptorProto.parseFrom(descriptorBytes).toBuilder();
                nestedExtensions(file, file.getTypes(), encoded.getMessageTypeBuilderList(), encoder);
                FileDescriptorProto descriptor = encoded.build();
                files.put(descriptor.getName(), descriptor);
            }

            Map<String, FileDescriptor> linked = new LinkedHashMap<>();
            for (String file : files.keySet()) {
                link(file, files, linked);
            }
            Map<String, String> sources = new LinkedHashMap<>();
            try (Stream<Path> paths = Files.walk(directory)) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    String relative = directory.relativize(path).toString().replace('\\', '/');
                    sources.put(relative, Files.readString(path));
                }
            }
            return fromDescriptors(linked.values(), sources);
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("Invalid gRPC proto resource: " + source, failure);
        } finally {
            try (Stream<Path> paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    /** 获取资源已持有的只读契约，不重复解析。 */
    public static GrpcProtoSchema fromResource(GrpcProtoResource resource) {
        return resource.schema;
    }

    /** 按完整服务名查询描述，未声明的服务返回 null。 */
    public ServiceDescriptor service(String name) {
        return this.services.get(name);
    }

    public MethodDescriptor method(String serviceName, String methodName) {
        ServiceDescriptor service = this.services.get(serviceName);
        MethodDescriptor method = service == null ? null : service.findMethodByName(methodName);
        if (method == null) {
            throw new IllegalArgumentException("No proto RPC: " + serviceName + "/" + methodName);
        }
        return method;
    }

    /** 返回入口资源解析到的全部文件描述，用于完整解析 Any 等跨文件消息。 */
    public List<FileDescriptor> fileDescriptors() {
        return this.fileDescriptors;
    }

    /** 返回可保存的 proto 资源图，已有文件保留原始内容。 */
    public Map<String, String> getSources() {
        return this.sources;
    }

    /** 保留官方 ProtoJSON 校验，并弥补其 double 经 BigDecimal 转换时丢失负零的问题。 */
    public static void mergeJson(String json, DynamicMessage.Builder builder, JsonFormat.TypeRegistry registry) //
            throws IOException {
        JsonFormat.parser().usingTypeRegistry(registry).merge(json, builder);
        if (json.indexOf('-') >= 0 || json.contains("\\u")) {
            restoreNegativeZero(readJson(json), builder, registry);
        }
    }

    private static JsonNode readJson(String json) throws IOException {
        // 只观察原始符号，不用 BigDecimal 节点；整数词法 -0 也保留为负零浮点节点。
        try (JsonParser parser = JSON.getFactory().createParser(json); //
             TokenBuffer tokens = new TokenBuffer(parser)) {
            while (parser.nextToken() != null) {
                boolean negativeZero = parser.currentToken() == JsonToken.VALUE_NUMBER_INT //
                        && StringUtils.equals(parser.getText(), "-0");
                if (negativeZero) {
                    tokens.writeNumber(-0.0d);
                } else {
                    tokens.copyCurrentEvent(parser);
                }
            }

            try (JsonParser buffered = tokens.asParser()) {
                return JSON.readTree(buffered);
            }
        }
    }

    private static void restoreNegativeZero(JsonNode source, Message.Builder builder, //
            JsonFormat.TypeRegistry registry) throws IOException {
        String type = builder.getDescriptorForType().getFullName();
        if (StringUtils.equals(type, "google.protobuf.DoubleValue")) {
            FieldDescriptor value = builder.getDescriptorForType().findFieldByNumber(1);
            Object current = builder.getField(value);
            Object restored = restoreValue(source, value, current, registry);
            if (restored != current) {
                builder.setField(value, restored);
            }
            return;
        }

        if (StringUtils.equals(type, "google.protobuf.Any")) {
            restoreAny(source, builder, registry);
            return;
        }

        // Value 的 number_value 使用 getAsDouble，Struct/ListValue 只包含 Value，原解析已保真。
        boolean valueTree = StringUtils.equals(type, "google.protobuf.Value") //
                || StringUtils.equals(type, "google.protobuf.Struct") //
                || StringUtils.equals(type, "google.protobuf.ListValue");
        if (source == null || !source.isObject() || valueTree) {
            return;
        }

        Map<String, FieldDescriptor> fields = new HashMap<>();
        for (FieldDescriptor field : builder.getDescriptorForType().getFields()) {
            fields.put(field.getName(), field);
            fields.put(field.getJsonName(), field);
        }

        Map<FieldDescriptor, JsonNode> originals = new LinkedHashMap<>();
        var members = source.fields();
        while (members.hasNext()) {
            Map.Entry<String, JsonNode> member = members.next();
            FieldDescriptor field = fields.get(member.getKey());
            if (field != null && !member.getValue().isNull()) {
                // 官方解析允许无 presence 的默认值被后续别名覆盖，观察同样的最后成员。
                originals.put(field, member.getValue());
            }
        }

        for (Map.Entry<FieldDescriptor, JsonNode> entry : originals.entrySet()) {
            FieldDescriptor field = entry.getKey();
            JsonNode original = entry.getValue();
            if (original == null || original.isNull()) {
                continue;
            }

            if (field.isMapField()) {
                restoreMap(original, builder, field, registry);
            } else if (field.isRepeated()) {
                for (int index = 0; index < builder.getRepeatedFieldCount(field); index++) {
                    Object current = builder.getRepeatedField(field, index);
                    Object restored = restoreValue(original.get(index), field, current, registry);
                    builder.setRepeatedField(field, index, restored);
                }
            } else {
                Object current = builder.getField(field);
                Object restored = restoreValue(original, field, current, registry);
                if (restored != current) {
                    builder.setField(field, restored);
                }
            }
        }
    }

    private static Object restoreValue(JsonNode source, FieldDescriptor field, Object current, //
            JsonFormat.TypeRegistry registry) throws IOException {
        if (source == null || source.isNull()) {
            return current;
        }

        if (field.getType() == FieldDescriptor.Type.DOUBLE) {
            // 只恢复原文为负零、且官方解析结果为正零的 double；正零和非零值保持原样。
            long actual = Double.doubleToRawLongBits((Double) current);
            String text = source.asText();
            if (actual == 0L && text.startsWith("-") && new BigDecimal(text).signum() == 0) {
                return -0.0d;
            }
        } else if (field.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
            Message.Builder nested = ((Message) current).toBuilder();
            restoreNegativeZero(source, nested, registry);
            return nested.build();
        }
        return current;
    }

    private static void restoreMap(JsonNode source, Message.Builder builder, FieldDescriptor field, //
            JsonFormat.TypeRegistry registry) throws IOException {
        FieldDescriptor key = field.getMessageType().findFieldByNumber(1);
        FieldDescriptor value = field.getMessageType().findFieldByNumber(2);
        Map<Object, JsonNode> originals = new LinkedHashMap<>();
        var entries = source.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            Object typedKey = switch (key.getJavaType()) {
                case STRING -> entry.getKey();
                case BOOLEAN -> Boolean.valueOf(entry.getKey());
                case INT -> new BigDecimal(entry.getKey()).toBigIntegerExact().intValue();
                case LONG -> new BigDecimal(entry.getKey()).toBigIntegerExact().longValue();
                default -> throw new IllegalArgumentException("Unsupported Protobuf map key: " + key.getType());
            };
            // 不同合法词法可能表示同一 typed key，遵循 Protobuf Map 的最后一个值语义。
            originals.put(typedKey, entry.getValue());
        }

        for (int index = 0; index < builder.getRepeatedFieldCount(field); index++) {
            Message entry = (Message) builder.getRepeatedField(field, index);
            JsonNode original = originals.get(entry.getField(key));
            Object restored = restoreValue(original, value, entry.getField(value), registry);
            builder.setRepeatedField(field, index, entry.toBuilder().setField(value, restored).build());
        }
    }

    private static void restoreAny(JsonNode source, Message.Builder builder, JsonFormat.TypeRegistry registry) throws IOException {
        FieldDescriptor typeUrl = builder.getDescriptorForType().findFieldByNumber(1);
        FieldDescriptor value = builder.getDescriptorForType().findFieldByNumber(2);
        String url = (String) builder.getField(typeUrl);
        Descriptor descriptor = registry.find(url.substring(url.lastIndexOf('/') + 1));
        if (descriptor == null) {
            return;
        }

        boolean scalar = StringUtils.equals(descriptor.getFullName(), "google.protobuf.DoubleValue") ||//
                StringUtils.equals(descriptor.getFullName(), "google.protobuf.Any");
        JsonNode original;
        if (scalar) {
            original = source.get("value");
        } else {
            ObjectNode payload = source.deepCopy();
            payload.remove("@type");
            original = payload;
        }

        DynamicMessage.Builder nested = DynamicMessage.parseFrom(descriptor, (ByteString) builder.getField(value)).toBuilder();
        restoreNegativeZero(original, nested, registry);
        builder.setField(value, nested.build().toByteString());
    }

    private static void nestedExtensions(ProtoFile file, List<Type> types, List<DescriptorProto.Builder> messages, SchemaEncoder encoder) throws IOException {
        // Wire 的 SchemaEncoder 未写入 message 内的 extend；复用其顶层字段编码，再放回声明作用域。
        for (Type type : types) {
            for (DescriptorProto.Builder message : messages) {
                if (!StringUtils.equals(type.getName(), message.getName())) {
                    continue;
                }

                if (!type.getNestedExtendList().isEmpty()) {
                    ProtoFile extensions = new ProtoFile(file.getLocation(), List.of(), List.of(), //
                            file.getPackageName(), List.of(), List.of(), type.getNestedExtendList(), //
                            file.getOptions(), file.getSyntax());

                    byte[] extensionBytes = encoder.encode(extensions).toByteArray();
                    FileDescriptorProto fields = FileDescriptorProto.parseFrom(extensionBytes);
                    message.addAllExtension(fields.getExtensionList());
                }
                nestedExtensions(file, type.getNestedTypes(), message.getNestedTypeBuilderList(), encoder);
            }
        }
    }

    private static void copy(URL root, String name, Path directory, Set<String> copied) throws IOException {
        if (!copied.add(name)) {
            return;
        }

        // Wire 扩展由解析器提供；标准类型使用 Protobuf 自带的完整定义，业务导入相对资源根目录。
        if (name.startsWith("wire/")) {
            return;
        }
        Path target = directory.resolve(name).normalize();
        if (!target.startsWith(directory)) {
            throw new IOException("Proto import escapes its resource root: " + name);
        }

        URL source = new URL(root, name);
        if (name.startsWith("google/protobuf/")) {
            source = DescriptorProtos.class.getResource("/" + name);
            if (source == null) {
                throw new IOException("Unknown standard Protobuf resource: " + name);
            }
        }

        byte[] bytes;
        try (InputStream input = source.openStream()) {
            bytes = input.readNBytes(4 * 1024 * 1024 + 1);
        }
        if (bytes.length > 4 * 1024 * 1024) {
            throw new IOException("Proto resource exceeds 4 MiB: " + name);
        }

        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
        String text = new String(bytes, StandardCharsets.UTF_8);
        ProtoFileElement file = ProtoParser.Companion.parse(Location.get(name), text);
        List<String> imports = new ArrayList<>(file.getImports());
        imports.addAll(file.getPublicImports());
        for (String dependency : imports) {
            copy(root, dependency, directory, copied);
        }
    }

    private static FileDescriptor link(String name, Map<String, FileDescriptorProto> files, //
            Map<String, FileDescriptor> linked) throws Exception {
        FileDescriptor existing = linked.get(name);
        if (existing != null) {
            return existing;
        }

        FileDescriptorProto file = files.get(name);
        List<FileDescriptor> dependencies = new ArrayList<>();
        for (String dependency : file.getDependencyList()) {
            dependencies.add(link(dependency, files, linked));
        }

        FileDescriptor descriptor = FileDescriptor.buildFrom(file, dependencies.toArray(FileDescriptor[]::new));
        linked.put(name, descriptor);
        return descriptor;
    }
}

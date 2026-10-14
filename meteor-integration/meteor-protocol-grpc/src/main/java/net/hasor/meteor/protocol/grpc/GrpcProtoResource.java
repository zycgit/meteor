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
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.google.protobuf.util.JsonFormat;
import com.squareup.wire.schema.*;
import com.squareup.wire.schema.internal.SchemaEncoder;
import com.squareup.wire.schema.internal.parser.ProtoFileElement;
import com.squareup.wire.schema.internal.parser.ProtoParser;
import net.hasor.cobble.StringUtils;

/** 本地 .proto 资源及其导入文件；解析一次，不生成 Java 类，也不参与注册中心同步。 */
public final class GrpcProtoResource {
    private final Map<String, ServiceDescriptor> services;
    private final JsonFormat.TypeRegistry        types;

    public GrpcProtoResource(URL source) throws IOException {
        this(new URL(source, "."), source.getPath().substring(source.getPath().lastIndexOf('/') + 1));
    }

    /** 从指定导入根目录加载入口文件，保留描述文件的相对名称。 */
    public GrpcProtoResource(URL root, String name) throws IOException {
        URL source = new URL(root, name);
        Path directory = Files.createTempDirectory("meteor-proto-");
        try {
            // 将 URL 资源及相对导入交给同一个 schema 根目录，兼容文件和 jar 资源。
            this.copy(root, name, directory, new HashSet<>());
            SchemaLoader loader = new SchemaLoader(FileSystems.getDefault());
            loader.setLoadExhaustively(true);
            Location entry = Location.get(directory.toString(), name);
            loader.initRoots(List.of(entry), List.of(Location.get(directory.toString())));
            Schema schema = loader.loadSchema();

            // Wire 负责语法和引用校验，Protobuf 负责标准描述信息与二进制编码。
            SchemaEncoder encoder = new SchemaEncoder(schema);
            Map<String, FileDescriptorProto> files = new HashMap<>();
            for (ProtoFile file : schema.getProtoFiles()) {
                FileDescriptorProto.Builder encoded = FileDescriptorProto.parseFrom(encoder.encode(file).toByteArray()).toBuilder();
                this.nestedExtensions(file, file.getTypes(), encoded.getMessageTypeBuilderList(), encoder);
                FileDescriptorProto descriptor = encoded.build();
                files.put(descriptor.getName(), descriptor);
            }
            Map<String, FileDescriptor> linked = new HashMap<>();
            Map<String, ServiceDescriptor> services = new HashMap<>();
            for (String file : files.keySet()) {
                FileDescriptor descriptor = this.link(file, files, linked);
                for (ServiceDescriptor service : descriptor.getServices()) {
                    services.put(service.getFullName(), service);
                }
            }

            this.services = Map.copyOf(services);
            JsonFormat.TypeRegistry.Builder registry = JsonFormat.TypeRegistry.newBuilder();
            for (FileDescriptor file : linked.values()) {
                registry.add(file.getMessageTypes());
            }

            this.types = registry.build();
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

    /** 按完整服务名查询描述，未声明的服务返回 null。 */
    public ServiceDescriptor service(String name) {
        return this.services.get(name);
    }

    JsonFormat.TypeRegistry types() {
        return this.types;
    }

    public MethodDescriptor method(String serviceName, String methodName) {
        ServiceDescriptor service = this.services.get(serviceName);
        MethodDescriptor method = service == null ? null : service.findMethodByName(methodName);
        if (method == null) {
            throw new IllegalArgumentException("No proto RPC: " + serviceName + "/" + methodName);
        }
        return method;
    }

    private void nestedExtensions(ProtoFile file, List<Type> types, List<DescriptorProto.Builder> messages, SchemaEncoder encoder) throws IOException {
        // Wire 的 SchemaEncoder 未写入 message 内的 extend；复用其顶层字段编码，再放回声明作用域。
        for (Type type : types) {
            for (DescriptorProto.Builder message : messages) {
                if (!StringUtils.equals(type.getName(), message.getName())) {
                    continue;
                }
                if (!type.getNestedExtendList().isEmpty()) {
                    ProtoFile extensions = new ProtoFile(file.getLocation(), List.of(), List.of(), file.getPackageName(), List.of(), List.of(), type.getNestedExtendList(), file.getOptions(), file.getSyntax());
                    FileDescriptorProto fields = FileDescriptorProto.parseFrom(encoder.encode(extensions).toByteArray());
                    message.addAllExtension(fields.getExtensionList());
                }
                this.nestedExtensions(file, type.getNestedTypes(), message.getNestedTypeBuilderList(), encoder);
            }
        }
    }

    private void copy(URL root, String name, Path directory, Set<String> copied) throws IOException {
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
            this.copy(root, dependency, directory, copied);
        }
    }

    private FileDescriptor link(String name, Map<String, FileDescriptorProto> files, Map<String, FileDescriptor> linked) throws Exception {
        FileDescriptor existing = linked.get(name);
        if (existing != null) {
            return existing;
        }

        FileDescriptorProto file = files.get(name);
        List<FileDescriptor> dependencies = new ArrayList<>();
        for (String dependency : file.getDependencyList()) {
            dependencies.add(this.link(dependency, files, linked));
        }

        FileDescriptor descriptor = FileDescriptor.buildFrom(file, dependencies.toArray(FileDescriptor[]::new));
        linked.put(name, descriptor);
        return descriptor;
    }
}

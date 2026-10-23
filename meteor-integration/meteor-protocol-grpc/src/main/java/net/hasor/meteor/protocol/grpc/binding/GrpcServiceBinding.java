/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.binding;
import java.lang.reflect.Method;
import java.util.*;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.Descriptors.*;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.meta.MethodMeta;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.domain.meta.ServiceMeta;
import net.hasor.meteor.domain.meta.ServiceMetaParser;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.protocol.grpc.GrpcProtoSchema;

/** gRPC 家族共享的静态服务绑定；不持有会话、网络资源或业务对象。 */
public final class GrpcServiceBinding {
    private final GrpcProtoSchema               schema;
    private final ServiceDescriptor             service;
    private final Map<String, Method>           javaMethods;
    private final Map<String, Method>           remoteMethods = new LinkedHashMap<>();
    private final Map<Method, MethodDescriptor> descriptors   = new LinkedHashMap<>();
    private final Map<Method, GrpcMethodShape>  shapes        = new LinkedHashMap<>();
    private final Map<Method, GrpcValueMapping> requests      = new LinkedHashMap<>();
    private final Map<Method, GrpcValueMapping> responses     = new LinkedHashMap<>();
    private final JsonFormat.TypeRegistry       registry;

    private GrpcServiceBinding(GrpcProtoSchema schema, String alias, Map<String, Method> methods, Class<?> type) {
        this.schema = schema;
        this.service = schema == null ? null : schema.service(alias);
        this.javaMethods = methods;
        Map<String, GrpcValueMapping> mappings = new LinkedHashMap<>();
        for (Method method : methods.values()) {
            this.shapes.put(method, new GrpcMethodShape(method, type));
            String remoteName = MetRuntimeUtils.getProtocolMethodName(method);
            this.remoteMethods.put(remoteName, method);
            if (this.service == null) {
                continue;
            }
            MethodDescriptor descriptor = this.service.findMethodByName(remoteName);
            this.descriptors.put(method, descriptor);
            JavaType request = GrpcValueMapping.messageType(type, method, true);
            JavaType response = GrpcValueMapping.messageType(type, method, false);
            this.requests.put(method, GrpcValueMapping.compile(request, descriptor.getInputType(), mappings));
            this.responses.put(method, GrpcValueMapping.compile(response, descriptor.getOutputType(), mappings));
        }

        JsonFormat.TypeRegistry.Builder registry = JsonFormat.TypeRegistry.newBuilder();
        if (schema != null) {
            Set<String> visited = new HashSet<>();
            for (FileDescriptor file : schema.fileDescriptors()) {
                addTypes(file, registry, visited);
            }
        }
        this.registry = registry.build();
    }

    /** 无文件时按公共编号编译；显式资源类型或映射冲突立即报错。 */
    public static GrpcServiceBinding prepare(ProtocolConfig config, ProtocolMeta meta, String defaultCoding) {
        MetBindInfo<?> service = meta.getService();
        String alias = service.getAliasName(config.name());
        if (service.isMessage() || alias == null //
                || !alias.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")) {
            return null;
        }
        Object source = meta.getBindResource();
        if (source != null && !(source instanceof GrpcProtoResource)) {
            throw new IllegalArgumentException("Protocol " + config.name() + " requires a GrpcProtoResource");
        }
        String coding = service.getSerializeType();
        if (StringUtils.isBlank(coding)) {
            coding = config.option("serializeType", defaultCoding);
        }
        GrpcProtoSchema schema = source instanceof GrpcProtoResource resource //
                ? GrpcProtoSchema.fromResource(resource) : null;
        ServiceMeta description = meta.getServiceMeta();
        Map<String, Method> methods = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        for (MethodMeta methodMeta : description.getMethods()) {
            Method method = methodMeta.getJavaMethod();
            if (method.isBridge() || method.isSynthetic()) {
                continue;
            }
            String name = methodMeta.getName();
            boolean shape = GrpcMethodShape.supports(method, service.getBindType());
            if (!shape || methodMeta.isMessage() //
                    || methods.putIfAbsent(method.getName(), method) != null || !names.add(name)) {
                return null;
            }
            GrpcProtoCompiler.identifier(name);
        }
        if (schema == null && StringUtils.equalsIgnoreCase(coding, "Protobuf")) {
            schema = GrpcProtoCompiler.compile(alias, methods.values(), description);
            if (schema == null) {
                return null;
            }
        }
        if (schema != null) {
            ServiceDescriptor declared = schema.service(alias);
            if (declared == null) {
                return null;
            }
            for (Method method : methods.values()) {
                MethodDescriptor target = declared.findMethodByName(MetRuntimeUtils.getProtocolMethodName(method));
                if (target == null) {
                    if (ServiceMetaParser.method(method).getDeclaredName() != null) {
                        throw new IllegalArgumentException("Annotated method is not declared by proto: " + method);
                    }
                    return null;
                }
                boolean request = GrpcValueMapping.streaming(method, true) == target.isClientStreaming();
                boolean response = GrpcValueMapping.streaming(method, false) == target.isServerStreaming();
                if (!request || !response) {
                    return null;
                }
            }
        }
        return new GrpcServiceBinding(schema, alias, methods, service.getBindType());
    }

    public GrpcProtoSchema schema() {
        return this.schema;
    }

    /** 从已编译的操作和消息图导出端点契约，外部 proto 和自动生成入口共用。 */
    public ObjectNode describeContract(ObjectNode description) {
        if (this.service != null) {
            description.put("name", this.service.getFullName());
        }
        ArrayNode methods = description.putArray("methods");
        ObjectNode types = description.putObject("types");
        for (Method method : this.javaMethods.values()) {
            ObjectNode operation = methods.addObject();
            operation.put("name", this.remoteName(method));
            operation.put("javaSignature", ServiceMetaParser.method(method).getJavaSignature());
            GrpcMethodShape shape = this.shape(method);
            MethodDescriptor descriptor = this.descriptors.get(method);
            if (descriptor == null) {
                operation.put("requestType", shape.requestType().getTypeName());
                operation.put("responseType", shape.responseType().getTypeName());
                operation.put("clientStreaming", shape.streamingRequest());
                operation.put("serverStreaming", shape.streamingResponse());
            } else {
                operation.put("requestType", describeMessage(descriptor.getInputType(), types));
                operation.put("responseType", describeMessage(descriptor.getOutputType(), types));
                operation.put("clientStreaming", descriptor.isClientStreaming());
                operation.put("serverStreaming", descriptor.isServerStreaming());
            }
        }
        return description;
    }

    private static String describeMessage(Descriptor descriptor, ObjectNode types) {
        String name = descriptor.getFullName();
        if (types.has(name)) {
            return name;
        }
        ObjectNode message = types.putObject(name);
        message.put("kind", "message");
        ArrayNode fields = message.putArray("fields");
        for (FieldDescriptor field : descriptor.getFields()) {
            ObjectNode member = fields.addObject();
            member.put("fieldId", field.getNumber());
            member.put("name", field.getName());
            if (field.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                member.put("type", describeMessage(field.getMessageType(), types));
            } else if (field.getJavaType() == FieldDescriptor.JavaType.ENUM) {
                EnumDescriptor enumeration = field.getEnumType();
                String enumName = enumeration.getFullName();
                member.put("type", enumName);
                if (!types.has(enumName)) {
                    ObjectNode values = types.putObject(enumName);
                    values.put("kind", "enum");
                    ArrayNode constants = values.putArray("values");
                    for (EnumValueDescriptor constant : enumeration.getValues()) {
                        ObjectNode value = constants.addObject();
                        value.put("fieldId", constant.getNumber());
                        value.put("name", constant.getName());
                    }
                }
            } else {
                member.put("type", field.getType().name());
            }
            member.put("jsonName", field.getJsonName());
            member.put("repeated", field.isRepeated());
            member.put("required", field.isRequired());
            if (field.getContainingOneof() != null) {
                member.put("oneof", field.getContainingOneof().getName());
            }
        }
        return name;
    }

    public ServiceDescriptor service() {
        return this.service;
    }

    public Collection<Method> methods() {
        return this.javaMethods.values();
    }

    public GrpcMethodShape shape(Method method) {
        return this.shapes.get(method);
    }

    public Method javaMethod(String name) {
        Method method = this.javaMethods.get(name);
        if (method == null) {
            throw new UnsupportedOperationException("Unknown Java RPC method: " + name);
        }
        return method;
    }

    public Method remoteMethod(String name) {
        Method method = this.remoteMethods.get(name);
        if (method == null) {
            throw new UnsupportedOperationException("Unknown protocol RPC method: " + name);
        }
        return method;
    }

    public String remoteName(Method method) {
        MethodDescriptor descriptor = this.descriptors.get(method);
        return descriptor == null ? MetRuntimeUtils.getProtocolMethodName(method) : descriptor.getName();
    }

    public MethodDescriptor descriptor(Method method) {
        MethodDescriptor descriptor = this.descriptors.get(method);
        if (descriptor == null) {
            throw new IllegalArgumentException("Protobuf service must bind proto file or annotated message fields.");
        }
        return descriptor;
    }

    public DynamicMessage encode(Method method, Object value, boolean request) throws Exception {
        Descriptor descriptor = this.message(method, request);
        if (value instanceof Message message) {
            if (!StringUtils.equals(message.getDescriptorForType().getFullName(), descriptor.getFullName())) {
                throw new IllegalArgumentException("Protobuf value has a different message type");
            }
            return DynamicMessage.parseFrom(descriptor, message.toByteArray());
        }
        GrpcValueMapping mapping = this.mapping(method, request);
        JsonNode json = mapping.toProto(GrpcValueMapping.MAPPER.valueToTree(value));
        DynamicMessage.Builder result = DynamicMessage.newBuilder(descriptor);
        GrpcProtoSchema.mergeJson(GrpcValueMapping.MAPPER.writeValueAsString(json), result, this.registry);
        return result.build();
    }

    public DynamicMessage parseJson(Method method, String json, boolean request) throws Exception {
        DynamicMessage.Builder result = DynamicMessage.newBuilder(this.message(method, request));
        GrpcProtoSchema.mergeJson(json, result, this.registry);
        return result.build();
    }

    public String json(DynamicMessage message) throws Exception {
        return JsonFormat.printer().usingTypeRegistry(this.registry) //
                .omittingInsignificantWhitespace().sortingMapKeys().print(message);
    }

    public Object decode(Method method, byte[] bytes, boolean request) throws Exception {
        GrpcValueMapping mapping = this.mapping(method, request);
        Class<?> type = mapping.javaType().getRawClass();
        if (Message.class.isAssignableFrom(type) && type != DynamicMessage.class && type != Message.class) {
            return type.getMethod("parseFrom", byte[].class).invoke(null, bytes);
        }
        DynamicMessage message = this.parse(method, bytes, request);
        if (type == DynamicMessage.class || type == Message.class) {
            return message;
        }
        JsonNode json = GrpcValueMapping.MAPPER.readTree(this.json(message));
        JsonNode javaValue = mapping.fromProto(json);
        return GrpcValueMapping.MAPPER.readerFor(mapping.javaType()).readValue(javaValue);
    }

    public DynamicMessage parse(Method method, byte[] bytes, boolean request) throws Exception {
        return DynamicMessage.parseFrom(this.message(method, request), bytes);
    }

    private Descriptor message(Method method, boolean request) {
        MethodDescriptor descriptor = this.descriptor(method);
        return request ? descriptor.getInputType() : descriptor.getOutputType();
    }

    private GrpcValueMapping mapping(Method method, boolean request) {
        this.descriptor(method);
        return request ? this.requests.get(method) : this.responses.get(method);
    }

    private static void addTypes(FileDescriptor file, JsonFormat.TypeRegistry.Builder registry, Set<String> visited) {
        if (!visited.add(file.getName())) {
            return;
        }
        for (FileDescriptor dependency : file.getDependencies()) {
            addTypes(dependency, registry, visited);
        }
        registry.add(file.getMessageTypes());
    }
}

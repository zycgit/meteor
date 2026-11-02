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
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.google.protobuf.DescriptorProtos.*;
import com.google.protobuf.Descriptors.*;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.meta.*;
import net.hasor.meteor.protocol.grpc.GrpcProtoSchema;

/** 根据稳定字段号编译 proto3 描述；业务实例不参与定义生成。 */
final class GrpcProtoCompiler {
    private final String                                 packageName;
    private final ServiceMeta                            service;
    private final Map<JavaType, DescriptorProto.Builder> messages  = new LinkedHashMap<>();
    private final Map<JavaType, EnumDescriptorProto>     enums     = new LinkedHashMap<>();
    private final Map<String, JavaType>                  names     = new LinkedHashMap<>();
    private final Map<JavaType, String>                  typeNames = new LinkedHashMap<>();

    private GrpcProtoCompiler(String packageName, ServiceMeta service) {
        this.packageName = packageName;
        this.service = service;
    }

    static GrpcProtoSchema compile(String alias, Collection<Method> methods, ServiceMeta service) {
        int separator = alias.lastIndexOf('.');
        String packageName = separator < 0 ? "" : alias.substring(0, separator);
        String serviceName = alias.substring(separator + 1);
        GrpcProtoCompiler compiler = new GrpcProtoCompiler(packageName, service);
        ServiceDescriptorProto.Builder api = ServiceDescriptorProto.newBuilder().setName(serviceName);
        for (Method method : methods) {
            JavaType request = GrpcValueMapping.messageType(service.getJavaType(), method, true);
            JavaType response = GrpcValueMapping.messageType(service.getJavaType(), method, false);
            String input = compiler.message(request);
            String output = compiler.message(response);
            if (input == null || output == null) {
                return null;
            }

            String name = MetRuntimeUtils.getProtocolMethodName(method);
            identifier(name);
            api.addMethod(MethodDescriptorProto.newBuilder().setName(name) //
                    .setInputType(input).setOutputType(output) //
                    .setClientStreaming(GrpcValueMapping.streaming(method, true)) //
                    .setServerStreaming(GrpcValueMapping.streaming(method, false)));
        }

        // 先收集整个引用图，再让官方描述符校验编号、命名空间和递归引用。
        FileDescriptorProto.Builder file = FileDescriptorProto.newBuilder() //
                .setName(serviceName + ".proto").setPackage(packageName).setSyntax("proto3");
        for (DescriptorProto.Builder message : compiler.messages.values()) {
            file.addMessageType(message);
        }
        file.addAllEnumType(compiler.enums.values()).addService(api);
        try {
            FileDescriptor descriptor = FileDescriptor.buildFrom(file.build(), new FileDescriptor[0]);
            Map<String, String> sources = Map.of(descriptor.getName(), export(descriptor));
            return GrpcProtoSchema.fromDescriptors(List.of(descriptor), sources);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Invalid annotated Protobuf service: " + alias, failure);
        }
    }

    private String message(JavaType type) {
        if (type == null || type.isPrimitive() || type.isEnumType() || type.isContainerType()) {
            return null;
        }
        Class<?> raw = type.getRawClass();
        if (raw.getName().startsWith("java.") || raw.getName().startsWith("com.google.protobuf.")) {
            return null;
        }
        if (this.messages.containsKey(type)) {
            return this.qualified(this.name(type));
        }

        String name = this.name(type);
        DescriptorProto.Builder message = DescriptorProto.newBuilder().setName(name);
        this.messages.put(type, message);
        List<BeanPropertyDefinition> properties = GrpcValueMapping.properties(type);
        Set<Integer> ids = new HashSet<>();
        Set<String> fields = new HashSet<>();
        for (BeanPropertyDefinition property : properties) {
            PropertyMeta mapping = ServiceMetaParser.property(property);
            int id = mapping.getFieldId();
            if (id == 0) {
                return null;
            }
            boolean reserved = id >= 19000 && id <= 19999;
            if (id < 1 || id > 536870911 || reserved || !ids.add(id)) {
                throw new IllegalArgumentException("Invalid or duplicate Protobuf field id: " + type + "#" + id);
            }
            String explicitName = mapping.getDeclaredName();
            String fieldName = StringUtils.isEmpty(explicitName) ? property.getName() : explicitName;
            identifier(fieldName);
            if (!fields.add(fieldName)) {
                throw new IllegalArgumentException("Duplicate Protobuf field name: " + type + "." + fieldName);
            }

            FieldDescriptorProto.Builder field = FieldDescriptorProto.newBuilder().setName(fieldName).setNumber(id);
            JavaType valueType = property.getPrimaryType();
            if (!this.field(field, valueType, message)) {
                return null;
            }
            message.addField(field);
        }
        return this.qualified(name);
    }

    private boolean field(FieldDescriptorProto.Builder field, JavaType type, DescriptorProto.Builder owner) {
        if (type.isMapLikeType()) {
            String entryName = "Entry" + field.getNumber();
            DescriptorProto.Builder entry = DescriptorProto.newBuilder().setName(entryName) //
                    .setOptions(MessageOptions.newBuilder().setMapEntry(true));
            FieldDescriptorProto.Builder key = FieldDescriptorProto.newBuilder().setName("key").setNumber(1);
            FieldDescriptorProto.Builder value = FieldDescriptorProto.newBuilder().setName("value").setNumber(2);
            if (!this.singular(key, type.getKeyType()) || !this.singular(value, type.getContentType())) {
                return false;
            }
            FieldDescriptorProto.Type keyType = key.getType();
            boolean allowed = keyType == FieldDescriptorProto.Type.TYPE_STRING //
                    || keyType == FieldDescriptorProto.Type.TYPE_BOOL //
                    || keyType == FieldDescriptorProto.Type.TYPE_INT32 //
                    || keyType == FieldDescriptorProto.Type.TYPE_INT64;
            if (!allowed) {
                throw new IllegalArgumentException("Unsupported Protobuf map key: " + type.getKeyType());
            }
            entry.addField(key).addField(value);
            owner.addNestedType(entry);
            field.setLabel(FieldDescriptorProto.Label.LABEL_REPEATED) //
                    .setType(FieldDescriptorProto.Type.TYPE_MESSAGE) //
                    .setTypeName(this.qualified(owner.getName() + "." + entryName));
            return true;
        }
        boolean repeated = type.isContainerType() && type.getRawClass() != byte[].class;
        JavaType element = repeated ? type.getContentType() : type;
        if (!this.singular(field, element)) {
            return false;
        }
        if (repeated) {
            field.setLabel(FieldDescriptorProto.Label.LABEL_REPEATED);
        } else if (!type.isPrimitive() && field.getType() != FieldDescriptorProto.Type.TYPE_MESSAGE) {
            // 包装 scalar、String、bytes 保留 null 与默认值之间的差异。
            field.setProto3Optional(true).setOneofIndex(owner.getOneofDeclCount());
            owner.addOneofDecl(OneofDescriptorProto.newBuilder().setName("_" + field.getName()));
        }
        return true;
    }

    private boolean singular(FieldDescriptorProto.Builder field, JavaType type) {
        if (type == null) {
            return false;
        }
        Class<?> raw = type.getRawClass();
        FieldDescriptorProto.Type scalar;
        if (raw == int.class || raw == Integer.class || raw == short.class || raw == Short.class //
                || raw == byte.class || raw == Byte.class) {
            scalar = FieldDescriptorProto.Type.TYPE_INT32;
        } else if (raw == long.class || raw == Long.class) {
            scalar = FieldDescriptorProto.Type.TYPE_INT64;
        } else if (raw == boolean.class || raw == Boolean.class) {
            scalar = FieldDescriptorProto.Type.TYPE_BOOL;
        } else if (raw == float.class || raw == Float.class) {
            scalar = FieldDescriptorProto.Type.TYPE_FLOAT;
        } else if (raw == double.class || raw == Double.class) {
            scalar = FieldDescriptorProto.Type.TYPE_DOUBLE;
        } else if (raw == String.class || raw == char.class || raw == Character.class) {
            scalar = FieldDescriptorProto.Type.TYPE_STRING;
        } else if (raw == byte[].class) {
            scalar = FieldDescriptorProto.Type.TYPE_BYTES;
        } else if (raw.isEnum()) {
            String name = this.enumeration(type);
            if (name == null) {
                return false;
            }
            field.setType(FieldDescriptorProto.Type.TYPE_ENUM).setTypeName(this.qualified(name));
            return true;
        } else {
            String message = this.message(type);
            if (message == null) {
                return false;
            }
            field.setType(FieldDescriptorProto.Type.TYPE_MESSAGE).setTypeName(message);
            return true;
        }
        field.setType(scalar);
        return true;
    }

    private String enumeration(JavaType type) {
        if (this.enums.containsKey(type)) {
            return this.enums.get(type).getName();
        }
        String name = this.name(type);
        Map<Integer, EnumValueDescriptorProto> values = new LinkedHashMap<>();
        Set<String> names = new HashSet<>();
        for (Object constant : type.getRawClass().getEnumConstants()) {
            Enum<?> item = (Enum<?>) constant;
            EnumValueMeta annotation = ServiceMetaParser.enumValue(item);
            if (!annotation.isDeclared()) {
                return null;
            }
            String symbol = annotation.getName();
            identifier(symbol);
            EnumValueDescriptorProto value = EnumValueDescriptorProto.newBuilder() //
                    .setName(symbol).setNumber(annotation.getFieldId()).build();
            if (values.putIfAbsent(annotation.getFieldId(), value) != null || !names.add(symbol)) {
                throw new IllegalArgumentException("Duplicate Protobuf enum value: " + type + "." + symbol);
            }
        }
        EnumValueDescriptorProto zero = values.remove(0);
        if (zero == null) {
            throw new IllegalArgumentException("Proto3 enum requires a zero value: " + type);
        }
        // proto3 要求首项为零；其余声明保持 Java 顺序，不使用 ordinal 作为编号。
        EnumDescriptorProto.Builder descriptor = EnumDescriptorProto.newBuilder().setName(name).addValue(zero);
        descriptor.addAllValue(values.values());
        this.enums.put(type, descriptor.build());
        return name;
    }

    private String name(JavaType type) {
        String existing = this.typeNames.get(type);
        if (existing != null) {
            return existing;
        }
        TypeMeta declaration = this.service.getType(ServiceMetaParser.resolvableType(type));
        String name = typeName(declaration, type);
        JavaType previous = this.names.get(name);
        boolean differentType = previous != null && !previous.equals(type);
        boolean declared = declaration.isDeclared();
        if (differentType && !declared) {
            Class<?> parent = type.getRawClass().getEnclosingClass();
            if (parent != null) {
                name = parent.getSimpleName() + "_" + name;
            }
        }
        identifier(name);
        previous = this.names.putIfAbsent(name, type);
        if (previous != null && !previous.equals(type)) {
            throw new IllegalArgumentException("Conflicting Protobuf type name: " + name + "; bind a proto resource");
        }
        this.typeNames.put(type, name);
        return name;
    }

    static String typeName(JavaType type) {
        return typeName(ServiceMetaParser.type(type.getRawClass()), type);
    }

    private static String typeName(TypeMeta declaration, JavaType type) {
        String name = declaration.getName();
        for (int index = 0; index < type.containedTypeCount(); index++) {
            JavaType argument = type.containedType(index);
            name += "Of" + typeName(argument);
        }
        return name;
    }

    private String qualified(String name) {
        return this.packageName.isEmpty() ? "." + name : "." + this.packageName + "." + name;
    }

    private static String export(FileDescriptor descriptor) {
        StringBuilder text = new StringBuilder("syntax = \"proto3\";\n");
        if (!descriptor.getPackage().isEmpty()) {
            text.append("package ").append(descriptor.getPackage()).append(";\n");
        }
        for (EnumDescriptor enumeration : descriptor.getEnumTypes()) {
            text.append("\nenum ").append(enumeration.getName()).append(" {\n");
            for (EnumValueDescriptor value : enumeration.getValues()) {
                text.append("  ").append(value.getName()).append(" = ").append(value.getNumber()).append(";\n");
            }
            text.append("}\n");
        }
        for (Descriptor message : descriptor.getMessageTypes()) {
            text.append("\nmessage ").append(message.getName()).append(" {\n");
            for (FieldDescriptor field : message.getFields()) {
                text.append("  ");
                if (field.isMapField()) {
                    var key = field.getMessageType().findFieldByNumber(1);
                    var value = field.getMessageType().findFieldByNumber(2);
                    text.append("map<").append(typeName(key)).append(", ").append(typeName(value)).append("> ");
                } else {
                    if (field.isRepeated()) {
                        text.append("repeated ");
                    } else if (field.toProto().getProto3Optional()) {
                        text.append("optional ");
                    }
                    text.append(typeName(field)).append(' ');
                }
                text.append(field.getName()).append(" = ").append(field.getNumber()).append(";\n");
            }
            text.append("}\n");
        }
        for (ServiceDescriptor service : descriptor.getServices()) {
            text.append("\nservice ").append(service.getName()).append(" {\n");
            for (MethodDescriptor method : service.getMethods()) {
                text.append("  rpc ").append(method.getName()).append('(');
                if (method.isClientStreaming()) {
                    text.append("stream ");
                }
                text.append('.').append(method.getInputType().getFullName()).append(") returns (");
                if (method.isServerStreaming()) {
                    text.append("stream ");
                }
                text.append('.').append(method.getOutputType().getFullName()).append(");\n");
            }
            text.append("}\n");
        }
        return text.toString();
    }

    private static String typeName(FieldDescriptor field) {
        return switch (field.getJavaType()) {
            case MESSAGE -> "." + field.getMessageType().getFullName();
            case ENUM -> "." + field.getEnumType().getFullName();
            default -> field.getType().name().toLowerCase(Locale.ROOT);
        };
    }

    static void identifier(String name) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid Protobuf identifier: " + name);
        }
    }
}

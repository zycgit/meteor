/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.codec;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.WildcardType;
import java.nio.ByteBuffer;
import java.util.*;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.reflect.resolvable.ResolvableType;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.meta.EnumValueMeta;
import net.hasor.meteor.domain.meta.PropertyMeta;
import net.hasor.meteor.domain.meta.ServiceMetaParser;
import net.hasor.meteor.protocol.thrift.annotation.ThriftUnion;
import org.apache.thrift.*;
import org.apache.thrift.meta_data.*;
import org.apache.thrift.protocol.TType;

/** 只在发布时解析注解或官方元数据；两种来源生成相同的值与字段定义。 */
final class ThriftSchemaBuilder {
    private final Map<String, ThriftType> types = new HashMap<>();

    ThriftSchemaBuilder() {
    }

    ThriftType javaType(ResolvableType source) {
        Class<?> raw = source.resolve();
        if (raw == null || raw == Object.class || source.getType() instanceof WildcardType) {
            throw new IllegalArgumentException("Thrift requires a concrete declared type: " + source);
        }
        String key = source.toString();
        ThriftType existing = this.types.get(key);
        if (existing != null) {
            return existing;
        }

        // 标量和容器使用明确的 Java 类型；不把任意 Number 或 Object 猜成线协议类型。
        byte wireType = this.wireType(raw);
        ThriftType type = new ThriftType(wireType, raw);
        this.types.put(key, type);
        if (wireType == TType.MAP) {
            ResolvableType map = source.as(Map.class);
            type.key = this.javaType(map.getGeneric(0));
            type.element = this.javaType(map.getGeneric(1));
            if (!raw.isAssignableFrom(LinkedHashMap.class)) {
                throw new IllegalArgumentException("Unsupported Thrift map implementation: " + raw.getName());
            }
        } else if (wireType == TType.LIST || wireType == TType.SET) {
            ResolvableType collection = source.as(Collection.class);
            type.element = this.javaType(collection.getGeneric(0));
            Class<?> implementation = wireType == TType.LIST ? ArrayList.class : LinkedHashSet.class;
            if (!raw.isAssignableFrom(implementation)) {
                throw new IllegalArgumentException("Unsupported Thrift collection implementation: " + raw.getName());
            }
        } else if (raw.isEnum()) {
            this.enumeration(type);
        } else if (wireType == TType.STRUCT) {
            this.constructor(type);
            if (TBase.class.isAssignableFrom(raw)) {
                this.generatedFields(type);
            } else {
                type.name = typeName(source);
                this.annotatedFields(source, type);
            }
        }
        type.binary = raw == byte[].class || raw == ByteBuffer.class;
        return type;
    }

    static String typeName(ResolvableType type) {
        Class<?> raw = type.resolve();
        if (raw.isArray()) {
            return "ArrayOf" + typeName(type.getComponentType());
        }
        String name = MetRuntimeUtils.getProtocolTypeName(raw);
        for (ResolvableType argument : type.getGenerics()) {
            name += "Of" + typeName(argument);
        }
        return name;
    }

    private byte wireType(Class<?> type) {
        if (type == boolean.class || type == Boolean.class) {
            return TType.BOOL;
        }
        if (type == byte.class || type == Byte.class) {
            return TType.BYTE;
        }
        if (type == short.class || type == Short.class) {
            return TType.I16;
        }
        if (type == int.class || type == Integer.class || type.isEnum()) {
            return TType.I32;
        }
        if (type == long.class || type == Long.class) {
            return TType.I64;
        }
        if (type == double.class || type == Double.class) {
            return TType.DOUBLE;
        }
        if (type == String.class || type == byte[].class || type == ByteBuffer.class) {
            return TType.STRING;
        }
        if (type == UUID.class) {
            return TType.UUID;
        }
        if (List.class.isAssignableFrom(type)) {
            return TType.LIST;
        }
        if (Set.class.isAssignableFrom(type)) {
            return TType.SET;
        }
        if (Map.class.isAssignableFrom(type)) {
            return TType.MAP;
        }
        boolean unsupported = type.isArray() || type.isPrimitive() || type.isInterface();
        if (unsupported || type.getName().startsWith("java.")) {
            throw new IllegalArgumentException("Unsupported Thrift Java type: " + type.getName());
        }
        return TType.STRUCT;
    }

    private void enumeration(ThriftType type) {
        for (Object constant : type.javaType.getEnumConstants()) {
            int number;
            if (constant instanceof TEnum generated) {
                number = generated.getValue();
            } else {
                EnumValueMeta mapping = ServiceMetaParser.enumValue((Enum<?>) constant);
                if (!mapping.isDeclared()) {
                    throw new IllegalArgumentException("Thrift enum requires @MetEnumValue: " + constant);
                }
                number = mapping.getId();
                if (type.enumNames.putIfAbsent(mapping.getName(), constant) != null) {
                    throw new IllegalArgumentException("Duplicate Thrift enum name: " + mapping.getName());
                }
            }
            if (type.enumValues.putIfAbsent(number, constant) != null) {
                throw new IllegalArgumentException("Duplicate Thrift enum value: " + number);
            }
            type.enumNumbers.put(constant, number);
        }
    }

    private void constructor(ThriftType type) {
        if (Modifier.isAbstract(type.javaType.getModifiers())) {
            throw new IllegalArgumentException("Thrift DTO must be concrete: " + type.javaType.getName());
        }
        try {
            type.constructor = type.javaType.getDeclaredConstructor();
            if (!type.constructor.trySetAccessible()) {
                String message = "Thrift DTO constructor is inaccessible: " + type.javaType.getName();
                throw new IllegalArgumentException(message);
            }
        } catch (NoSuchMethodException failure) {
            throw new IllegalArgumentException("Thrift DTO requires a no-arg constructor: " + type.javaType.getName());
        }
    }

    private void annotatedFields(ResolvableType source, ThriftType type) {
        type.union = type.javaType.isAnnotationPresent(ThriftUnion.class);
        for (PropertyMeta property : ServiceMetaParser.type(source).getProperties().values()) {
            if (!property.isDeclared()) {
                continue;
            }
            ThriftFieldBinding binding = this.annotatedField(property.getId(), property.getDeclaredName(), //
                    property.getJavaName(), property.getType(), type.union);
            Field field = property.getField();
            if (field != null) {
                if (Modifier.isFinal(field.getModifiers()) || !field.trySetAccessible()) {
                    throw new IllegalArgumentException("Thrift field must be writable: " + field);
                }
                binding.property = field;
            } else {
                var getter = property.getGetter();
                var setter = property.getSetter();
                if (getter == null || setter == null || !getter.trySetAccessible() || !setter.trySetAccessible()) {
                    String message = "Thrift property requires an accessible setter: " + property.getJavaName();
                    throw new IllegalArgumentException(message);
                }
                binding.getter = getter;
                binding.setter = setter;
            }
            type.add(binding);
        }
    }

    ThriftFieldBinding annotatedField(int id, String name, String fallback, ResolvableType source, boolean union) {
        if (id <= 0 || id > Short.MAX_VALUE) {
            throw new IllegalArgumentException("Thrift parameters and properties require an id between 1 and 32767");
        }
        boolean nullable = source.resolve() != null && !source.resolve().isPrimitive();
        if (union && !nullable) {
            throw new IllegalArgumentException("Thrift union fields require nullable Java types: " + source);
        }
        byte requirement = nullable ? TFieldRequirementType.OPTIONAL : TFieldRequirementType.DEFAULT;
        String fieldName = name.isEmpty() ? fallback : name;
        if (StringUtils.isBlank(fieldName)) {
            throw new IllegalArgumentException("Thrift field name must not be blank");
        }
        return new ThriftFieldBinding((short) id, fieldName, requirement, this.javaType(source));
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    List<Map.Entry<TFieldIdEnum, FieldMetaData>> generatedFields(Class<?> type) {
        Map<TFieldIdEnum, FieldMetaData> metadata = (Map) FieldMetaData.getStructMetaDataMap((Class) type);
        List<Map.Entry<TFieldIdEnum, FieldMetaData>> fields = new ArrayList<>(metadata.entrySet());
        // 生成器枚举保持 IDL 声明顺序，参数位置不能按 field id 排序。
        fields.sort(Comparator.comparingInt(field -> ((Enum<?>) field.getKey()).ordinal()));
        return fields;
    }

    private void generatedFields(ThriftType type) {
        type.union = TUnion.class.isAssignableFrom(type.javaType);
        for (Map.Entry<TFieldIdEnum, FieldMetaData> entry : this.generatedFields(type.javaType)) {
            FieldMetaData metadata = entry.getValue();
            ThriftType value = this.generatedType(metadata.valueMetaData);
            byte requirement = type.union ? TFieldRequirementType.OPTIONAL : metadata.requirementType;
            ThriftFieldBinding binding = new ThriftFieldBinding(entry.getKey().getThriftFieldId(), //
                    metadata.fieldName, requirement, value);
            binding.generated = entry.getKey();
            type.add(binding);
        }
    }

    ThriftType generatedType(FieldValueMetaData metadata) {
        if (metadata instanceof StructMetaData struct) {
            return this.javaType(ResolvableType.forClass(struct.structClass));
        }
        if (metadata instanceof EnumMetaData enumeration) {
            return this.javaType(ResolvableType.forClass(enumeration.enumClass));
        }
        if (metadata instanceof MapMetaData map) {
            ThriftType type = new ThriftType(TType.MAP, Map.class);
            type.key = this.generatedType(map.keyMetaData);
            type.element = this.generatedType(map.valueMetaData);
            return type;
        }
        if (metadata instanceof ListMetaData list) {
            ThriftType type = new ThriftType(TType.LIST, List.class);
            type.element = this.generatedType(list.elemMetaData);
            return type;
        }
        if (metadata instanceof SetMetaData set) {
            ThriftType type = new ThriftType(TType.SET, Set.class);
            type.element = this.generatedType(set.elemMetaData);
            return type;
        }
        Class<?> javaType = switch (metadata.type) {
            case TType.BOOL -> Boolean.class;
            case TType.BYTE -> Byte.class;
            case TType.I16 -> Short.class;
            case TType.I32 -> Integer.class;
            case TType.I64 -> Long.class;
            case TType.DOUBLE -> Double.class;
            case TType.STRING -> metadata.isBinary() ? ByteBuffer.class : String.class;
            case TType.UUID -> UUID.class;
            default -> throw new IllegalArgumentException("Unsupported Thrift metadata type: " + metadata.type);
        };
        return this.javaType(ResolvableType.forClass(javaType));
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.binding;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.Flow;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.reflect.resolvable.ResolvableType;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.meta.EnumValueMeta;
import net.hasor.meteor.domain.meta.PropertyMeta;
import net.hasor.meteor.domain.meta.ServiceMetaParser;
import net.hasor.meteor.domain.meta.TypeMeta;
import net.hasor.meteor.protocol.oncrpc.codec.*;
import net.hasor.meteor.protocol.oncrpc.definition.XdrIdlDocument;
import net.hasor.meteor.protocol.oncrpc.definition.XdrIdlMember;
import net.hasor.meteor.protocol.oncrpc.definition.XdrIdlType;

/** 发布阶段编译声明类型，缓存仅属于当前端点的准备动作。 */
final class XdrSchemaBuilder {
    private final OncRpcServiceResource    resource;
    private final int                      maxBytes;
    private final int                      maxElements;
    private final Map<String, XdrCodec<?>> types       = new HashMap<>();
    private final Map<String, XdrIdlType>  definitions = new HashMap<>();

    XdrSchemaBuilder(OncRpcServiceResource resource, int maxBytes, int maxElements) {
        this.resource = resource;
        this.maxBytes = maxBytes;
        this.maxElements = maxElements;
    }

    @SuppressWarnings("unchecked")
    XdrCodec<Object> codec(ResolvableType type) {
        Class<?> raw = type.resolve();
        if (raw == null || raw == Object.class || stream(raw)) {
            return null;
        }
        XdrCodec<?> custom = this.resource == null ? null : this.resource.getCodec(raw);
        if (custom != null) {
            return (XdrCodec<Object>) custom;
        }
        XdrCodec<?> existing = this.types.get(type.toString());
        if (existing != null) {
            return (XdrCodec<Object>) existing;
        }
        XdrCodec<?> scalar = this.scalar(raw);
        if (scalar != null) {
            this.types.put(type.toString(), scalar);
            this.definitions.put(type.toString(), this.scalarDefinition(raw));
            return (XdrCodec<Object>) scalar;
        }
        if (raw == List.class) {
            ResolvableType component = type.as(List.class).getGeneric(0);
            XdrCodec<?> element = this.codec(component);
            this.arrayDefinition(type, component);
            if (element == null) {
                return null;
            }
            XdrCodec<?> codec = XdrCodecs.list(element, this.maxElements);
            this.types.put(type.toString(), codec);
            return (XdrCodec) codec;
        }
        if (raw.isArray()) {
            XdrCodec<Object> element = this.codec(type.getComponentType());
            this.arrayDefinition(type, type.getComponentType());
            if (element == null) {
                return null;
            }
            XdrCodec<Object> codec = array(raw.getComponentType(), element, this.maxElements);
            this.types.put(type.toString(), codec);
            return codec;
        }
        if (raw.isEnum()) {
            XdrCodec<Object> enumeration = this.enumeration(raw);
            if (enumeration != null) {
                this.types.put(type.toString(), enumeration);
            }
            return enumeration;
        }
        if (raw.isInterface() || raw.isPrimitive() || raw.getName().startsWith("java.")) {
            return null;
        }
        return this.struct(type, raw);
    }

    /** 文件模式只绑定既有结构，固定长度、unsigned 和 optional 不从 Java 类型猜测。 */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    XdrCodec<Object> idlCodec(ResolvableType source, XdrIdlType reference, XdrIdlDocument document) {
        Class<?> raw = source.resolve();
        if (raw == null || raw == Object.class || stream(raw)) {
            return null;
        }
        XdrIdlType type = document.resolve(reference);
        String identity = "idl:" + type.kind + ":" + type.name + ":" + source;
        boolean named = StringUtils.equals(type.kind, "struct") || StringUtils.equals(type.kind, "union");
        XdrCodec<?> cached = named ? this.types.get(identity) : null;
        if (cached != null) {
            return (XdrCodec<Object>) cached;
        }

        // 文件内的宽度和有符号性决定可接受的 Java 类型。
        XdrCodec<?> scalar = switch (type.kind) {
            case "int" -> this.integer(raw);
            case "hyper" -> raw == long.class || raw == Long.class ? XdrCodecs.LONG : null;
            case "unsigned_int" -> raw == long.class || raw == Long.class ? XdrCodecs.UNSIGNED_INT : null;
            case "unsigned_hyper" -> raw == BigInteger.class ? XdrCodecs.UNSIGNED_LONG : null;
            case "bool" -> raw == boolean.class || raw == Boolean.class ? XdrCodecs.BOOLEAN : null;
            case "float" -> raw == float.class || raw == Float.class ? XdrCodecs.FLOAT : null;
            case "double" -> raw == double.class || raw == Double.class ? XdrCodecs.DOUBLE : null;
            case "quadruple" -> raw == byte[].class ? XdrCodecs.QUADRUPLE : null;
            case "void" -> raw == void.class || raw == Void.class ? XdrCodecs.VOID : null;
            case "string" -> raw == String.class ? XdrCodecs.string(this.bound(type, this.maxBytes)) : null;
            case "opaque" -> raw == byte[].class ? this.opaque(type) : null;
            default -> null;
        };
        if (scalar != null) {
            return (XdrCodec<Object>) scalar;
        }
        if (StringUtils.equals(type.kind, "optional")) {
            if (raw.isPrimitive()) {
                throw new IllegalArgumentException("XDR optional requires a nullable Java type: " + source);
            }
            XdrCodec<Object> value = this.idlCodec(source, type.element, document);
            return value == null ? null : XdrCodecs.optional(value);
        }
        if (StringUtils.equals(type.kind, "array")) {
            boolean list = raw == List.class;
            if (!list && !raw.isArray()) {
                throw new IllegalArgumentException("XDR array requires a Java List or array: " + source);
            }
            ResolvableType component = list ? source.getGeneric(0) : source.getComponentType();
            XdrCodec<Object> element = this.idlCodec(component, type.element, document);
            if (element == null) {
                return null;
            }
            XdrCodec<List<Object>> values;
            if (type.fixed) {
                values = XdrCodecs.fixedArray(element, this.fixedLength(type));
            } else {
                values = XdrCodecs.list(element, this.bound(type, this.maxElements));
            }
            return list ? (XdrCodec) values : array(raw.getComponentType(), values);
        }

        TypeMeta mapping = ServiceMetaParser.type(raw);
        boolean renamed = mapping.isDeclared() && !StringUtils.equals(mapping.getName(), type.name);
        if (renamed && !StringUtils.equals(typeName(source), type.name)) {
            throw new IllegalArgumentException("Java type name differs from XDR contract: " + source);
        }
        if (StringUtils.equals(type.kind, "enum")) {
            return this.idlEnum(raw, type);
        }
        if (named && !raw.isInterface() && !Modifier.isAbstract(raw.getModifiers())) {
            if (StringUtils.equals(type.kind, "struct")) {
                XdrStructCodec codec = new XdrStructCodec(raw);
                codec.name = type.name;
                this.types.put(identity, codec);
                // 绑定文件时保留契约成员顺序，公共字段号不参与位置匹配。
                for (XdrIdlMember member : type.members) {
                    codec.fields.add(this.idlProperty(source, member, document));
                }
                return codec;
            }
            XdrUnionCodec codec = new XdrUnionCodec(raw);
            codec.value.name = type.name;
            this.types.put(identity, codec);
            codec.discriminator = this.idlProperty(source, type.discriminator, document);
            Class<?> tagType = this.propertyType(source, codec.discriminator).resolve();
            for (XdrIdlMember member : type.members) {
                XdrPropertyBinding property = null;
                if (!StringUtils.equals(document.resolve(member.type).kind, "void")) {
                    property = this.idlProperty(source, member, document);
                    if (this.propertyType(source, property).resolve().isPrimitive()) {
                        String message = "XDR union arms require nullable Java types: " + member.name;
                        throw new IllegalArgumentException(message);
                    }
                    codec.members.add(property);
                }
                if (member.defaultArm) {
                    codec.defaultDeclared = true;
                    codec.fallback = property;
                }
                for (Long label : member.cases) {
                    Object tag = this.caseValue(tagType, type.discriminator.type, label, document);
                    codec.cases.put(tag, property);
                }
            }
            return codec;
        }
        String message = document.source + ": Java type differs from XDR " + type.kind + ": " + source;
        throw new IllegalArgumentException(message);
    }

    private int bound(XdrIdlType type, int maximum) {
        return type.length < 0 ? maximum : (int) Math.min(type.length, maximum);
    }

    private int fixedLength(XdrIdlType type) {
        if (type.length < 0 || type.length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Fixed XDR length cannot be represented by a Java array");
        }
        return (int) type.length;
    }

    private XdrCodec<byte[]> opaque(XdrIdlType type) {
        if (type.fixed) {
            return XdrCodecs.fixedOpaque(this.fixedLength(type));
        }
        int maximum = this.bound(type, this.maxBytes);
        return XdrCodecs.opaque(maximum);
    }

    private XdrCodec<Object> idlEnum(Class<?> raw, XdrIdlType type) {
        if (!raw.isEnum()) {
            throw new IllegalArgumentException("XDR enum requires a Java enum: " + raw.getName());
        }
        Map<Integer, Object> values = new LinkedHashMap<>();
        Map<Object, Integer> numbers = new LinkedHashMap<>();
        for (Object constant : raw.getEnumConstants()) {
            EnumValueMeta mapping = ServiceMetaParser.enumValue((Enum<?>) constant);
            Integer id = type.enumeration.get(mapping.getName());
            if (id == null || mapping.isDeclared() && mapping.getFieldId() != id) {
                throw new IllegalArgumentException("Java enum name/id differs from XDR: " + constant);
            }
            if (values.putIfAbsent(id, constant) != null) {
                throw new IllegalArgumentException("Ambiguous XDR enum number: " + id);
            }
            numbers.put(constant, id);
        }
        return XdrCodecs.enumeration(values::get, value -> numbers.get(value));
    }

    private Object caseValue(Class<?> raw, XdrIdlType reference, long value, XdrIdlDocument document) {
        XdrIdlType type = document.resolve(reference);
        if (StringUtils.equals(type.kind, "enum")) {
            for (Object constant : raw.getEnumConstants()) {
                EnumValueMeta mapping = ServiceMetaParser.enumValue((Enum<?>) constant);
                Integer number = type.enumeration.get(mapping.getName());
                if (number != null && number.longValue() == value) {
                    return constant;
                }
            }
            throw new IllegalArgumentException("Union case has no Java enum constant: " + value);
        }
        if (StringUtils.equals(type.kind, "bool") && (value == 0 || value == 1)) {
            return value != 0;
        }
        if (StringUtils.equals(type.kind, "int")) {
            return javaInteger(raw, Math.toIntExact(value));
        }
        if (StringUtils.equals(type.kind, "unsigned_int") && value >= 0 && value <= 0xffffffffL) {
            return value;
        }
        throw new IllegalArgumentException("Invalid XDR union discriminator or case: " + type.kind);
    }

    private XdrPropertyBinding idlProperty(ResolvableType source, XdrIdlMember member, XdrIdlDocument document) {
        XdrPropertyBinding selected = null;
        for (PropertyMeta property : ServiceMetaParser.type(source).getProperties().values()) {
            if (!StringUtils.equals(member.name, property.getName())) {
                continue;
            }
            if (selected != null) {
                throw new IllegalArgumentException("Ambiguous XDR member: " + member.name);
            }
            selected = this.access(property);
        }
        if (selected == null) {
            throw new IllegalArgumentException(document.source + ": no Java property binds XDR member " + member.name);
        }
        selected.name = member.name;
        ResolvableType property = this.propertyType(source, selected);
        selected.codec = this.idlCodec(property, member.type, document);
        if (selected.codec == null) {
            throw new UnsupportedOperationException("No Java mapping for XDR member: " + member.name);
        }
        return selected;
    }

    private ResolvableType propertyType(ResolvableType owner, XdrPropertyBinding property) {
        if (property.field != null) {
            return ResolvableType.forField(property.field, owner);
        }
        ResolvableType declaring = owner.as(property.getter.getDeclaringClass());
        return ResolvableType.forType(property.getter.getGenericReturnType(), declaring);
    }

    private XdrCodec<?> scalar(Class<?> raw) {
        XdrCodec<?> integer = this.integer(raw);
        if (integer != null) {
            return integer;
        }
        if (raw == long.class || raw == Long.class) {
            return XdrCodecs.LONG;
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return XdrCodecs.BOOLEAN;
        }
        if (raw == float.class || raw == Float.class) {
            return XdrCodecs.FLOAT;
        }
        if (raw == double.class || raw == Double.class) {
            return XdrCodecs.DOUBLE;
        }
        if (raw == void.class || raw == Void.class) {
            return XdrCodecs.VOID;
        }
        if (raw == String.class) {
            return XdrCodecs.string(this.maxBytes);
        }
        if (raw == byte[].class) {
            return XdrCodecs.opaque(this.maxBytes);
        }
        return null;
    }

    private XdrCodec<?> integer(Class<?> raw) {
        if (raw == int.class || raw == Integer.class) {
            return XdrCodecs.INT;
        }
        if (raw == byte.class || raw == Byte.class) {
            return boundedInteger(Byte.class);
        }
        if (raw == short.class || raw == Short.class) {
            return boundedInteger(Short.class);
        }
        if (raw == char.class || raw == Character.class) {
            // Java char 是一个 UTF-16 code unit，使用 int 保留完整范围，不按 ASCII string 编码。
            return boundedInteger(Character.class);
        }
        return null;
    }

    private static XdrCodec<Object> boundedInteger(Class<?> type) {
        return new XdrCodec<>() {
            @Override
            public Object read(XdrInput input) {
                // XDR int 的范围比 Java 窄整数大，拒绝溢出而不做截断转换。
                return javaInteger(type, input.readInt());
            }

            @Override
            public void write(XdrOutput output, Object value) {
                // 非 optional 的 XDR 标量没有 null 表示，不在这里隐式增加指针判别值。
                if (value == null) {
                    throw new IllegalArgumentException("XDR " + type.getSimpleName() + " cannot be null");
                }
                int encoded;
                if (type == Character.class) {
                    encoded = (Character) value;
                } else {
                    encoded = ((Number) type.cast(value)).intValue();
                }
                output.writeInt(encoded);
            }
        };
    }

    private static Object javaInteger(Class<?> type, int value) {
        int minimum;
        int maximum;
        if (type == byte.class || type == Byte.class) {
            minimum = Byte.MIN_VALUE;
            maximum = Byte.MAX_VALUE;
        } else if (type == short.class || type == Short.class) {
            minimum = Short.MIN_VALUE;
            maximum = Short.MAX_VALUE;
        } else if (type == char.class || type == Character.class) {
            minimum = Character.MIN_VALUE;
            maximum = Character.MAX_VALUE;
        } else {
            return value;
        }
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException("XDR int is outside Java " + type.getSimpleName() + " range");
        }
        if (type == byte.class || type == Byte.class) {
            return (byte) value;
        }
        if (type == short.class || type == Short.class) {
            return (short) value;
        }
        return (char) value;
    }

    private XdrCodec<Object> enumeration(Class<?> raw) {
        Map<Integer, Object> values = new LinkedHashMap<>();
        Map<Object, Integer> numbers = new LinkedHashMap<>();
        XdrIdlType definition = new XdrIdlType("enum", ServiceMetaParser.type(raw).getName());
        for (Object constant : raw.getEnumConstants()) {
            EnumValueMeta mapping = ServiceMetaParser.enumValue((Enum<?>) constant);
            if (!mapping.isDeclared()) {
                return null;
            }
            if (values.putIfAbsent(mapping.getFieldId(), constant) != null) {
                throw new IllegalArgumentException("Duplicate XDR enum id: " + mapping.getFieldId());
            }
            numbers.put(constant, mapping.getFieldId());
            if (definition.enumeration.putIfAbsent(mapping.getName(), mapping.getFieldId()) != null) {
                throw new IllegalArgumentException("Duplicate XDR enum symbol: " + mapping.getName());
            }
        }
        this.definitions.put(ResolvableType.forClass(raw).toString(), definition);
        return XdrCodecs.enumeration(values::get, value -> numbers.get(value));
    }

    private XdrStructCodec struct(ResolvableType source, Class<?> raw) {
        if (Modifier.isAbstract(raw.getModifiers())) {
            return null;
        }
        ServiceMetaParser.type(raw).getName();
        // 公共字段号只决定生成顺序，不作为 XDR 的零起始位置写入线上数据。
        List<PropertyMeta> properties = new ArrayList<>();
        Set<Integer> fieldIds = new HashSet<>();
        for (PropertyMeta property : ServiceMetaParser.type(source).getProperties().values()) {
            if (!property.isDeclared()) {
                continue;
            }
            int id = property.getFieldId();
            if (id <= 0) {
                String message = "XDR properties require a positive @MetField id: ";
                throw new IllegalArgumentException(message + raw.getName() + "." + property.getJavaName());
            }
            if (!fieldIds.add(id)) {
                throw new IllegalArgumentException("Duplicate XDR field id " + id + " on " + raw.getName());
            }
            properties.add(property);
        }
        if (properties.isEmpty()) {
            return null;
        }
        properties.sort(Comparator.comparingInt(PropertyMeta::getFieldId));

        // 递归回边先登记，全部成员准备成功后再返回当前协议绑定。
        XdrStructCodec codec = new XdrStructCodec(raw);
        codec.name = typeName(source);
        XdrIdlType definition = new XdrIdlType("struct", codec.name);
        this.definitions.put(source.toString(), definition);
        this.types.put(source.toString(), codec);
        for (PropertyMeta property : properties) {
            XdrPropertyBinding binding = this.access(property);
            binding.name = property.getName();
            binding.codec = this.codec(property.getType());
            if (binding.codec == null) {
                return null;
            }
            codec.fields.add(binding);
        }
        for (XdrPropertyBinding property : codec.fields) {
            ResolvableType value = this.propertyType(source, property);
            definition.members.add(new XdrIdlMember(property.name, this.definition(value)));
        }
        return codec;
    }

    private XdrPropertyBinding access(PropertyMeta property) {
        XdrPropertyBinding binding = new XdrPropertyBinding();
        Field field = property.getField();
        if (field != null) {
            if (Modifier.isFinal(field.getModifiers()) || !field.trySetAccessible()) {
                throw new IllegalArgumentException("XDR property must be writable: " + field);
            }
            binding.field = field;
        } else {
            Method getter = property.getGetter();
            Method setter = property.getSetter();
            if (getter == null || setter == null || !getter.trySetAccessible() || !setter.trySetAccessible()) {
                throw new IllegalArgumentException("XDR Bean property requires a setter: " + property.getJavaName());
            }
            binding.getter = getter;
            binding.setter = setter;
        }
        return binding;
    }

    XdrIdlType definition(ResolvableType type) {
        return this.definitions.get(type.toString());
    }

    private void arrayDefinition(ResolvableType type, ResolvableType component) {
        XdrIdlType element = this.definition(component);
        String elementName = element == null ? "Custom" : element.name;
        if (element != null && elementName == null) {
            elementName = element.kind;
        }
        String name = (type.resolve().isArray() ? "ArrayOf_" : "ListOf_") + elementName;
        XdrIdlType definition = new XdrIdlType("array", name);
        definition.element = element;
        definition.length = this.maxElements;
        this.definitions.put(type.toString(), definition);
    }

    private XdrIdlType scalarDefinition(Class<?> raw) {
        String kind;
        if (raw == String.class) {
            kind = "string";
        } else if (raw == byte[].class) {
            kind = "opaque";
        } else if (raw == long.class || raw == Long.class) {
            kind = "hyper";
        } else if (raw == boolean.class || raw == Boolean.class) {
            kind = "bool";
        } else if (raw == float.class || raw == Float.class) {
            kind = "float";
        } else if (raw == double.class || raw == Double.class) {
            kind = "double";
        } else if (raw == void.class || raw == Void.class) {
            kind = "void";
        } else {
            kind = "int";
        }
        String name = raw == String.class ? "MetString" : null;
        if (raw == byte[].class) {
            name = "MetOpaque";
        }
        XdrIdlType definition = new XdrIdlType(kind, name);
        if (raw == String.class || raw == byte[].class) {
            definition.length = this.maxBytes;
        }
        return definition;
    }

    private static String typeName(ResolvableType type) {
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

    private static XdrCodec<Object> array(Class<?> component, XdrCodec<Object> element, int maximum) {
        XdrCodec<List<Object>> values = XdrCodecs.list(element, maximum);
        return array(component, values);
    }

    private static XdrCodec<Object> array(Class<?> component, XdrCodec<List<Object>> values) {
        return new XdrCodec<>() {
            @Override
            public Object read(XdrInput input) {
                List<Object> items = input.read(values);
                Object array = Array.newInstance(component, items.size());
                for (int index = 0; index < items.size(); index++) {
                    Array.set(array, index, items.get(index));
                }
                return array;
            }

            @Override
            public void write(XdrOutput output, Object value) {
                int length = Array.getLength(value);
                List<Object> items = new ArrayList<>(length);
                for (int index = 0; index < length; index++) {
                    items.add(Array.get(value, index));
                }
                output.write(values, items);
            }
        };
    }

    private static boolean stream(Class<?> type) {
        boolean input = InputStream.class.isAssignableFrom(type) || Reader.class.isAssignableFrom(type);
        boolean output = OutputStream.class.isAssignableFrom(type) || Writer.class.isAssignableFrom(type);
        return input || output || Flow.Publisher.class.isAssignableFrom(type);
    }
}

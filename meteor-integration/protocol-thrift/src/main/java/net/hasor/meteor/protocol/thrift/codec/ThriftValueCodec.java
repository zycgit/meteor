/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.codec;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.thrift.TException;
import org.apache.thrift.TFieldRequirementType;
import org.apache.thrift.protocol.*;

/** 注解 DTO 和官方生成类型共用的正文读写，不调用生成对象的 read/write。 */
final class ThriftValueCodec {
    private ThriftValueCodec() {
    }

    static Object read(TProtocol input, ThriftType type) throws TException {
        if (type.javaType.isEnum()) {
            return type.enumValues.get(input.readI32());
        }
        return switch (type.wireType) {
            case TType.BOOL -> input.readBool();
            case TType.BYTE -> input.readByte();
            case TType.I16 -> input.readI16();
            case TType.I32 -> input.readI32();
            case TType.I64 -> input.readI64();
            case TType.DOUBLE -> readDouble(input.readDouble(), type);
            case TType.UUID -> input.readUuid();
            case TType.STRING -> readString(input, type);
            case TType.STRUCT -> readStruct(input, type);
            case TType.LIST, TType.SET -> readCollection(input, type);
            case TType.MAP -> readMap(input, type);
            default -> throw invalid("Unsupported Thrift value type");
        };
    }

    static void write(TProtocol output, ThriftType type, Object value) throws TException {
        if (value == null) {
            throw invalid("Thrift container values cannot be null");
        }
        if (type.javaType.isEnum()) {
            Integer number = type.enumNumbers.get(value);
            if (number == null) {
                throw invalid("Unexpected Thrift enum value: " + value);
            }
            output.writeI32(number);
            return;
        }
        try {
            switch (type.wireType) {
                case TType.BOOL -> output.writeBool((Boolean) value);
                case TType.BYTE -> output.writeByte((Byte) value);
                case TType.I16 -> output.writeI16((Short) value);
                case TType.I32 -> output.writeI32((Integer) value);
                case TType.I64 -> output.writeI64((Long) value);
                case TType.DOUBLE -> {
                    if (type.javaType == float.class || type.javaType == Float.class) {
                        output.writeDouble((Float) value);
                    } else {
                        output.writeDouble((Double) value);
                    }
                }
                case TType.UUID -> output.writeUuid((UUID) value);
                case TType.STRING -> writeString(output, type, value);
                case TType.STRUCT -> writeStruct(output, type, value);
                case TType.LIST, TType.SET -> writeCollection(output, type, value);
                case TType.MAP -> writeMap(output, type, (Map<?, ?>) value);
                default -> throw invalid("Unsupported Thrift value type");
            }
        } catch (ClassCastException failure) {
            throw new TException("Value does not match Thrift type " + type.javaType.getName(), failure);
        }
    }

    private static Object readDouble(double value, ThriftType type) throws TException {
        if (type.javaType != float.class && type.javaType != Float.class) {
            return value;
        }

        // Java float 可精确提升为 double；接收时拒绝会溢出、下溢或舍入的反向转换。
        float narrowed = (float) value;
        if (!Double.isNaN(value) && (double) narrowed != value) {
            throw invalid("Thrift double cannot be represented exactly as Java float");
        }
        return narrowed;
    }

    private static Object readText(String text, ThriftType type) throws TException {
        if (type.javaType != char.class && type.javaType != Character.class) {
            return text;
        }

        // 单个代理项不能独立编码为 UTF-8，不能在传输时被替换成另一个字符。
        if (text.length() != 1 || Character.isSurrogate(text.charAt(0))) {
            throw invalid("Thrift string must contain one UTF-8 representable Java character");
        }
        return text.charAt(0);
    }

    private static Object readString(TProtocol input, ThriftType type) throws TException {
        if (!type.binary) {
            return readText(input.readString(), type);
        }
        ByteBuffer buffer = input.readBinary();
        if (type.javaType == ByteBuffer.class) {
            return buffer;
        }
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    private static void writeString(TProtocol output, ThriftType type, Object value) throws TException {
        if (!type.binary) {
            if (type.javaType == char.class || type.javaType == Character.class) {
                String text = Character.toString((Character) value);
                readText(text, type);
                output.writeString(text);
            } else {
                output.writeString((String) value);
            }
        } else if (value instanceof byte[] bytes) {
            output.writeBinary(ByteBuffer.wrap(bytes));
        } else {
            output.writeBinary(((ByteBuffer) value).duplicate());
        }
    }

    private static Object readStruct(TProtocol input, ThriftType type) throws TException {
        Object value = type.newValue();
        Set<Short> present = new HashSet<>();
        int unionFields = 0;
        input.readStructBegin();
        while (true) {
            TField header = input.readFieldBegin();
            if (header.type == TType.STOP) {
                break;
            }
            if (type.union && ++unionFields > 1) {
                throw invalid("Thrift union must contain exactly one field");
            }
            ThriftFieldBinding field = type.fieldsById.get(header.id);
            if (field == null || field.type.wireType != header.type) {
                TProtocolUtil.skip(input, header.type);
            } else {
                Object member = read(input, field.type);
                if (member != null) {
                    field.set(value, member);
                    present.add(header.id);
                }
            }
            input.readFieldEnd();
        }
        input.readStructEnd();

        // 必填依据报文实际携带字段判断，不能由构造方法的默认值掩盖缺失。
        for (ThriftFieldBinding field : type.fields) {
            if (field.requirement == TFieldRequirementType.REQUIRED && !present.contains(field.field.id)) {
                throw invalid("Missing required Thrift field: " + field.field.name);
            }
            if (!present.contains(field.field.id) && field.defaultValue != null) {
                Object declared = field.defaultValue.evaluate(new HashSet<>());
                field.set(value, defaultValue(field.type, declared));
            }
        }
        if (type.union && unionFields == 0) {
            throw invalid("Thrift union must contain exactly one field");
        }
        return value;
    }

    /** 按同一份协议定义恢复默认值，每个 DTO 获得独立的可变容器。 */
    static Object defaultValue(ThriftType type, Object value) throws TException {
        if (type.javaType.isEnum()) {
            int number = new BigDecimal(value.toString()).intValueExact();
            Object constant = type.enumValues.get(number);
            if (constant == null) {
                throw invalid("Unknown enum default: " + number);
            }
            return constant;
        }
        if (value instanceof Number number) {
            BigDecimal decimal = new BigDecimal(number.toString());
            try {
                return switch (type.wireType) {
                    case TType.BYTE -> decimal.byteValueExact();
                    case TType.I16 -> decimal.shortValueExact();
                    case TType.I32 -> decimal.intValueExact();
                    case TType.I64 -> decimal.longValueExact();
                    case TType.DOUBLE -> {
                        double declared = decimal.doubleValue();
                        boolean floatType = type.javaType == float.class || type.javaType == Float.class;
                        if (floatType && !Double.isFinite(declared)) {
                            throw invalid("Numeric default exceeds Java float range");
                        }
                        boolean underflow = declared == 0 && decimal.signum() != 0;
                        if (floatType && underflow) {
                            throw invalid("Numeric default underflows Java float range");
                        }
                        yield readDouble(declared, type);
                    }
                    case TType.BOOL -> decimal.intValueExact() != 0;
                    default -> throw invalid("Numeric default differs from field type");
                };
            } catch (ArithmeticException failure) {
                throw invalid("Numeric default exceeds field range");
            }
        }
        if (type.wireType == TType.STRING && value instanceof String text) {
            if (!type.binary) {
                return readText(text, type);
            }
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            return type.javaType == byte[].class ? bytes : ByteBuffer.wrap(bytes);
        }
        if (type.wireType == TType.UUID && value instanceof String text) {
            return UUID.fromString(text);
        }
        if (type.wireType == TType.BOOL && value instanceof Boolean) {
            return value;
        }
        if ((type.wireType == TType.LIST || type.wireType == TType.SET) && value instanceof Collection<?> values) {
            if (type.javaType.isArray()) {
                Object result = Array.newInstance(type.javaType.getComponentType(), values.size());
                int index = 0;
                for (Object element : values) {
                    Array.set(result, index++, defaultValue(type.element, element));
                }
                return result;
            }
            Collection<Object> result = type.wireType == TType.LIST ? new ArrayList<>() : new LinkedHashSet<>();
            for (Object element : values) {
                result.add(defaultValue(type.element, element));
            }
            return result;
        }
        if (type.wireType == TType.MAP && value instanceof Map<?, ?> values) {
            Map<Object, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                result.put(defaultValue(type.key, entry.getKey()), defaultValue(type.element, entry.getValue()));
            }
            return result;
        }
        if (type.wireType == TType.STRUCT && value instanceof Map<?, ?> values) {
            Object result = type.newValue();
            for (ThriftFieldBinding field : type.fields) {
                if (values.containsKey(field.field.name)) {
                    field.set(result, defaultValue(field.type, values.get(field.field.name)));
                }
            }
            return result;
        }
        throw invalid("IDL default differs from Java field type: " + type.name);
    }

    private static void writeStruct(TProtocol output, ThriftType type, Object value) throws TException {
        output.writeStructBegin(new TStruct(type.name));
        int written = 0;
        for (ThriftFieldBinding field : type.fields) {
            Object member = field.get(value);
            if (member == null) {
                if (field.requirement == TFieldRequirementType.REQUIRED) {
                    throw invalid("Missing required Thrift field: " + field.field.name);
                }
                continue;
            }
            if (type.union && written != 0) {
                throw invalid("Thrift union must contain exactly one field");
            }
            output.writeFieldBegin(field.field);
            write(output, field.type, member);
            output.writeFieldEnd();
            written++;
        }
        if (type.union && written == 0) {
            throw invalid("Thrift union must contain exactly one field");
        }
        output.writeFieldStop();
        output.writeStructEnd();
    }

    private static Object readCollection(TProtocol input, ThriftType type) throws TException {
        byte elementType;
        int size;
        if (type.wireType == TType.LIST) {
            TList list = input.readListBegin();
            elementType = list.elemType;
            size = list.size;
        } else {
            TSet set = input.readSetBegin();
            elementType = set.elemType;
            size = set.size;
        }
        if (size != 0 && elementType != type.element.wireType) {
            throw invalid("Thrift collection element type differs from its declaration");
        }

        // 数组按声明组件类型直接创建，保留 primitive 数组和嵌套数组的真实 Java 类型。
        Object array = null;
        Collection<Object> values = null;
        if (type.javaType.isArray()) {
            array = Array.newInstance(type.javaType.getComponentType(), size);
        } else if (type.wireType == TType.LIST) {
            values = new ArrayList<>();
        } else {
            values = new LinkedHashSet<>();
        }
        for (int index = 0; index < size; index++) {
            Object element = read(input, type.element);
            if (array != null) {
                Array.set(array, index, element);
            } else {
                values.add(element);
            }
        }
        if (type.wireType == TType.LIST) {
            input.readListEnd();
        } else {
            input.readSetEnd();
        }
        return array == null ? values : array;
    }

    private static void writeCollection(TProtocol output, ThriftType type, Object value) throws TException {
        // Java 数组与 List 使用相同线协议，byte[] 已在 binary 分支单独处理。
        if (type.javaType.isArray()) {
            Object array = type.javaType.cast(value);
            int length = Array.getLength(array);
            output.writeListBegin(new TList(type.element.wireType, length));
            for (int index = 0; index < length; index++) {
                write(output, type.element, Array.get(array, index));
            }
            output.writeListEnd();
            return;
        }

        Collection<?> values = (Collection<?>) value;
        if (type.wireType == TType.LIST) {
            output.writeListBegin(new TList(type.element.wireType, values.size()));
        } else {
            output.writeSetBegin(new TSet(type.element.wireType, values.size()));
        }
        for (Object member : values) {
            write(output, type.element, member);
        }
        if (type.wireType == TType.LIST) {
            output.writeListEnd();
        } else {
            output.writeSetEnd();
        }
    }

    private static Object readMap(TProtocol input, ThriftType type) throws TException {
        TMap map = input.readMapBegin();
        boolean keyMatches = map.keyType == type.key.wireType;
        boolean valueMatches = map.valueType == type.element.wireType;
        if (map.size != 0 && (!keyMatches || !valueMatches)) {
            throw invalid("Thrift map entry types differ from their declaration");
        }
        Map<Object, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < map.size; index++) {
            Object key = read(input, type.key);
            Object value = read(input, type.element);
            values.put(key, value);
        }
        input.readMapEnd();
        return values;
    }

    private static void writeMap(TProtocol output, ThriftType type, Map<?, ?> values) throws TException {
        output.writeMapBegin(new TMap(type.key.wireType, type.element.wireType, values.size()));
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            write(output, type.key, entry.getKey());
            write(output, type.element, entry.getValue());
        }
        output.writeMapEnd();
    }

    private static TProtocolException invalid(String message) {
        return new TProtocolException(TProtocolException.INVALID_DATA, message);
    }
}

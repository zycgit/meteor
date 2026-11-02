/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.codec;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.reflect.resolvable.ResolvableType;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.meta.*;
import net.hasor.meteor.protocol.thrift.definition.ThriftIdlField;
import net.hasor.meteor.protocol.thrift.definition.ThriftIdlMethod;
import net.hasor.meteor.protocol.thrift.definition.ThriftIdlType;
import net.hasor.meteor.protocol.thrift.definition.ThriftIdlValue;
import org.apache.thrift.TException;
import org.apache.thrift.TFieldRequirementType;
import org.apache.thrift.protocol.TType;

/** 把已解析 IDL 绑定到 Java 声明；运行期继续使用原有 Thrift 值编解码。 */
final class ThriftIdlBinding {
    private final ThriftIdlType           service;
    private final ThriftSchemaBuilder     scalars = new ThriftSchemaBuilder();
    private final Map<String, ThriftType> types   = new HashMap<>();

    ThriftIdlBinding(ThriftIdlType service) {
        this.service = service;
    }

    ThriftServiceSchema bind(MetBindInfo<?> service) {
        Map<String, ThriftIdlMethod> methods = new LinkedHashMap<>();
        this.methods(this.service, methods, new HashSet<>());
        ThriftServiceSchema schema = new ThriftServiceSchema(this.service.name);
        List<ThriftType> roots = new ArrayList<>();
        for (Method method : MetRuntimeUtils.getServiceMethods(service.getBindType())) {
            if (method.isBridge() || method.isSynthetic()) {
                continue;
            }
            MethodMeta methodMeta = ServiceMetaParser.method(service.getBindType(), method);
            String name = methodMeta.getName();
            ThriftIdlMethod definition = methods.get(name);
            if (definition == null || definition.parameters.size() != method.getParameterCount()) {
                throw this.error("Java method does not match IDL: " + method);
            }
            boolean message = service.isMessage() || methodMeta.isMessage();
            if (message != definition.oneway || message && method.getReturnType() != void.class) {
                throw this.error("Java message mode differs from IDL oneway: " + method);
            }

            // Java 参数位置与 IDL 字段号分别保存；显式名称和编号必须指向同一个成员。
            ThriftType args = new ThriftType(TType.STRUCT, Object[].class);
            Set<Short> used = new HashSet<>();
            for (int index = 0; index < method.getParameterCount(); index++) {
                ParameterMeta mapping = methodMeta.getParameters().get(index);
                int id = !mapping.isDeclared() ? 0 : mapping.getFieldId();
                String parameterName = mapping.getName();
                if (id != 0 && mapping.getDeclaredName().isEmpty()) {
                    parameterName = null;
                }
                ThriftIdlField target = this.field(definition.parameters, id, parameterName);
                if (!used.add(target.id)) {
                    throw this.error("IDL parameter bound more than once: " + target.name);
                }
                ResolvableType type = mapping.getType();
                ThriftFieldBinding binding = this.field(target, type);
                binding.position = index;
                args.add(binding);
            }

            // 成功字段 0 及 throws 字段来自 IDL，不从 throws 声明顺序分配编号。
            ThriftType result = null;
            if (!message) {
                result = new ThriftType(TType.STRUCT, Object[].class);
                boolean emptyResult = StringUtils.equals(this.resolve(definition.result).kind, "void");
                if (emptyResult != (method.getReturnType() == void.class)) {
                    throw this.error("Java return type differs from IDL: " + method);
                }
                if (!emptyResult) {
                    ResolvableType source = methodMeta.getReturnType();
                    ThriftType type = this.type(source, definition.result);
                    this.result(result, (short) 0, "success", type);
                }
                Set<Class<?>> failures = new HashSet<>();
                for (ThriftIdlField failure : definition.exceptions) {
                    Class<?> exception = this.exception(method, failure.type);
                    failures.add(exception);
                    ThriftType type = this.type(ResolvableType.forClass(exception), failure.type);
                    this.result(result, failure.id, failure.name, type);
                }
                for (Class<?> exception : method.getExceptionTypes()) {
                    boolean checked = !RuntimeException.class.isAssignableFrom(exception);
                    if (checked && exception != org.apache.thrift.TException.class && !failures.contains(exception)) {
                        throw this.error("Java checked exception has no IDL throws field: " + exception.getName());
                    }
                }
            } else if (!definition.exceptions.isEmpty() || !StringUtils.equals(definition.result.kind, "void")) {
                throw this.error("IDL oneway requires void without throws: " + name);
            }
            schema.add(new ThriftMethodBinding(method, name, message, args, result));
            roots.add(args);
            if (result != null) {
                roots.add(result);
            }
        }
        // 递归引用全部填充后再检查默认值，检查过程不调用 DTO 构造入口。
        Set<ThriftType> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ThriftType type : roots) {
            this.validateDefaults(type, visited);
        }
        return schema;
    }

    private void validateDefaults(ThriftType type, Set<ThriftType> visited) {
        if (!visited.add(type)) {
            return;
        }
        for (ThriftFieldBinding field : type.fields) {
            if (field.defaultValue != null) {
                Object value = field.defaultValue.evaluate(new HashSet<>());
                this.validateDefault(field.type, value);
            }
            this.validateDefaults(field.type, visited);
        }
        if (type.key != null) {
            this.validateDefaults(type.key, visited);
        }
        if (type.element != null) {
            this.validateDefaults(type.element, visited);
        }
    }

    private void validateDefault(ThriftType type, Object value) {
        if (type.wireType == TType.STRUCT && value instanceof Map<?, ?> values) {
            if (type.union && values.size() != 1) {
                throw this.error("Union default must select exactly one field: " + type.name);
            }
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                ThriftFieldBinding selected = null;
                for (ThriftFieldBinding field : type.fields) {
                    if (entry.getKey() instanceof String name && StringUtils.equals(name, field.field.name)) {
                        selected = field;
                    }
                }
                if (selected == null) {
                    throw this.error("Unknown struct default field: " + entry.getKey());
                }
                this.validateDefault(selected.type, entry.getValue());
            }
            return;
        }
        if (type.wireType == TType.MAP && value instanceof Map<?, ?> values) {
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                this.validateDefault(type.key, entry.getKey());
                this.validateDefault(type.element, entry.getValue());
            }
            return;
        }
        boolean collection = type.wireType == TType.LIST || type.wireType == TType.SET;
        if (collection && value instanceof Collection<?> values) {
            for (Object element : values) {
                this.validateDefault(type.element, element);
            }
            return;
        }
        // 这里只处理标量；复合值形态错误直接报告，避免为校验创建 DTO。
        if (type.wireType == TType.STRUCT || type.wireType == TType.MAP || collection) {
            throw this.error("Default value shape differs from IDL type: " + type.name);
        }
        try {
            ThriftValueCodec.defaultValue(type, value);
        } catch (TException | IllegalArgumentException | ArithmeticException failure) {
            throw this.error("Default value differs from IDL type " + type.name + ": " + failure.getMessage());
        }
    }

    private void methods(ThriftIdlType service, Map<String, ThriftIdlMethod> methods, Set<ThriftIdlType> visiting) {
        if (!visiting.add(service) || !StringUtils.equals(service.kind, "service")) {
            throw this.error("Invalid or cyclic service inheritance: " + service.name);
        }
        if (service.element != null) {
            this.methods(this.resolve(service.element), methods, visiting);
        }
        for (ThriftIdlMethod method : service.methods.values()) {
            if (methods.putIfAbsent(method.name, method) != null) {
                throw this.error("Duplicate inherited method: " + method.name);
            }
        }
        visiting.remove(service);
    }

    private ThriftIdlType resolve(ThriftIdlType type) {
        Set<ThriftIdlType> aliases = new HashSet<>();
        while (StringUtils.equals(type.kind, "reference") || StringUtils.equals(type.kind, "typedef")) {
            if (!aliases.add(type)) {
                throw this.error("Cyclic typedef: " + type.name);
            }
            type = StringUtils.equals(type.kind, "reference") ? type.document.resolve(type) : type.element;
        }
        return type;
    }

    private ThriftType type(ResolvableType source, ThriftIdlType reference) {
        ThriftIdlType definition = this.resolve(reference);
        String kind = definition.kind;
        Class<?> raw = source.resolve();
        if (raw == null || raw == Object.class) {
            throw this.error("IDL binding requires a concrete Java type: " + source);
        }
        String key = definition.document.source + "#" + definition.name + ":" + source;
        boolean structured = StringUtils.equals(kind, "struct") || StringUtils.equals(kind, "union");
        boolean namedType = structured || StringUtils.equals(kind, "exception") //
                || StringUtils.equals(kind, "enum");
        ThriftType existing = namedType ? this.types.get(key) : null;
        if (existing != null) {
            return existing;
        }
        byte wireType = this.wireType(kind);
        ThriftType type = new ThriftType(wireType, raw);
        type.name = definition.name;
        if (namedType) {
            this.types.put(key, type);
        }

        // 容器递归绑定声明泛型，不能退化成无类型集合。
        if (wireType == TType.MAP) {
            if (!raw.isAssignableFrom(LinkedHashMap.class)) {
                throw this.error("IDL map requires a compatible Java Map: " + source);
            }
            ResolvableType map = source.as(Map.class);
            type.key = this.type(map.getGeneric(0), definition.key);
            type.element = this.type(map.getGeneric(1), definition.element);
        } else if (wireType == TType.LIST && raw.isArray()) {
            type.element = this.type(source.getComponentType(), definition.element);
        } else if (wireType == TType.LIST || wireType == TType.SET) {
            Class<?> implementation = wireType == TType.LIST ? ArrayList.class : LinkedHashSet.class;
            if (!raw.isAssignableFrom(implementation)) {
                throw this.error("IDL collection requires a compatible Java collection: " + source);
            }
            type.element = this.type(source.as(Collection.class).getGeneric(0), definition.element);
        } else if (StringUtils.equals(kind, "enum")) {
            this.enumeration(type, definition);
        } else if (wireType == TType.STRUCT) {
            TypeMeta mapping = ServiceMetaParser.type(raw);
            boolean renamed = mapping.isDeclared() && !StringUtils.equals(mapping.getDeclaredName(), definition.name);
            if (renamed && !StringUtils.equals(ThriftSchemaBuilder.typeName(source), definition.name)) {
                throw this.error("Java type name differs from IDL: " + source);
            }
            this.properties(source, type, definition);
        } else {
            ThriftType scalar = this.scalars.javaType(source);
            boolean binary = StringUtils.equals(kind, "binary");
            if (scalar.wireType != wireType || scalar.binary != binary) {
                throw this.error("Java value type differs from IDL " + kind + ": " + source);
            }
            return scalar;
        }
        return type;
    }

    private void enumeration(ThriftType type, ThriftIdlType definition) {
        if (!type.javaType.isEnum()) {
            throw this.error("IDL enum requires a Java enum: " + type.javaType.getName());
        }
        for (Object constant : type.javaType.getEnumConstants()) {
            Enum<?> enumeration = (Enum<?>) constant;
            EnumValueMeta mapping = ServiceMetaParser.enumValue(enumeration);
            String name = mapping.getName();
            Integer number = definition.enumeration.get(name);
            if (number == null || mapping.isDeclared() && mapping.getFieldId() != number) {
                throw this.error("Java enum differs from IDL: " + enumeration);
            }
            if (type.enumValues.putIfAbsent(number, constant) != null) {
                throw this.error("Ambiguous enum value: " + number);
            }
            type.enumNames.put(name, constant);
            type.enumNumbers.put(constant, number);
        }
    }

    private void properties(ResolvableType source, ThriftType type, ThriftIdlType definition) {
        Class<?> dto = type.javaType;
        type.union = StringUtils.equals(definition.kind, "union");
        if (Modifier.isAbstract(dto.getModifiers())) {
            throw this.error("IDL DTO must be concrete: " + dto.getName());
        }
        if (type.union) {
            for (ThriftIdlField field : definition.fields) {
                if (field.requirement == TFieldRequirementType.REQUIRED) {
                    throw this.error("IDL union cannot contain required fields: " + field.name);
                }
            }
        }
        try {
            type.constructor = dto.getDeclaredConstructor();
            if (!type.constructor.trySetAccessible()) {
                throw this.error("Inaccessible DTO constructor: " + dto.getName());
            }
            // 公共描述完成 Java 属性发现和注解合并，IDL 负责选择线上字段。
            Map<Short, String> bound = new HashMap<>();
            for (PropertyMeta property : ServiceMetaParser.type(source).getProperties().values()) {
                ThriftIdlField target = this.findField(definition.fields, property);
                if (target == null) {
                    continue;
                }
                ThriftFieldBinding binding = this.field(target, property.getType());
                Field field = property.getField();
                if (field != null) {
                    if (Modifier.isFinal(field.getModifiers()) || !field.trySetAccessible()) {
                        throw this.error("IDL property must be writable: " + field);
                    }
                    binding.property = field;
                } else {
                    Method getter = property.getGetter();
                    Method setter = property.getSetter();
                    if (getter == null || setter == null) {
                        continue;
                    }
                    if (!getter.trySetAccessible() || !setter.trySetAccessible()) {
                        throw this.error("Inaccessible IDL Bean property: " + property.getJavaName());
                    }
                    binding.getter = getter;
                    binding.setter = setter;
                }
                if (type.union && binding.type.javaType.isPrimitive()) {
                    throw this.error("IDL union requires nullable members: " + target.name);
                }
                type.add(binding);
                bound.put(target.id, property.getJavaName());
            }
            if (bound.size() != definition.fields.size()) {
                throw this.error("Java DTO does not bind every IDL field: " + dto.getName());
            }
        } catch (NoSuchMethodException failure) {
            throw this.error("Cannot bind IDL DTO: " + dto.getName());
        }
    }

    private ThriftIdlField findField(List<ThriftIdlField> fields, PropertyMeta property) {
        int id = property.getFieldId();
        String name = property.getName();
        if (property.isDeclared()) {
            boolean onlyNumber = property.getDeclaredName().isEmpty() && id != 0;
            return this.field(fields, id, onlyNumber ? null : name);
        }
        for (ThriftIdlField field : fields) {
            if (StringUtils.equals(field.name, name)) {
                return field;
            }
        }
        return null;
    }

    private ThriftIdlField field(List<ThriftIdlField> fields, int id, String name) {
        ThriftIdlField selected = null;
        for (ThriftIdlField field : fields) {
            boolean match = id != 0 ? field.id == id : StringUtils.equals(field.name, name);
            if (id == 0 && name == null && fields.size() == 1) {
                match = true;
            }
            if (match) {
                selected = field;
            }
        }
        if (selected == null || id != 0 && name != null && !StringUtils.equals(selected.name, name)) {
            throw this.error("IDL field name/id not found or disagree: " + name + "/" + id);
        }
        return selected;
    }

    private ThriftFieldBinding field(ThriftIdlField definition, ResolvableType source) {
        ThriftType type = this.type(source, definition.type);
        if (definition.requirement == TFieldRequirementType.OPTIONAL && type.javaType.isPrimitive()) {
            throw this.error("Optional IDL field requires a nullable Java type: " + definition.name);
        }
        ThriftFieldBinding field = new ThriftFieldBinding(definition.id, definition.name, definition.requirement, type);
        field.defaultValue = (ThriftIdlValue) definition.defaultValue;
        return field;
    }

    private Class<?> exception(Method method, ThriftIdlType reference) {
        ThriftIdlType target = this.resolve(reference);
        Class<?> selected = null;
        for (Class<?> exception : method.getExceptionTypes()) {
            if (StringUtils.equals(MetRuntimeUtils.getProtocolTypeName(exception), target.name)) {
                return exception;
            }
            if (exception != org.apache.thrift.TException.class) {
                if (selected != null) {
                    throw this.error("Ambiguous Java exception for IDL " + target.name);
                }
                selected = exception;
            }
        }
        if (selected == null || ServiceMetaParser.type(selected).getDeclaredName() != null) {
            throw this.error("No Java exception matches IDL " + target.name);
        }
        return selected;
    }

    private void result(ThriftType result, short id, String name, ThriftType type) {
        ThriftFieldBinding field = new ThriftFieldBinding(id, name, TFieldRequirementType.OPTIONAL, type);
        field.position = result.fields.size();
        result.add(field);
    }

    private byte wireType(String kind) {
        return switch (kind) {
            case "bool" -> TType.BOOL;
            case "byte", "i8" -> TType.BYTE;
            case "i16" -> TType.I16;
            case "i32", "enum" -> TType.I32;
            case "i64" -> TType.I64;
            case "double" -> TType.DOUBLE;
            case "string", "binary" -> TType.STRING;
            case "uuid" -> TType.UUID;
            case "list" -> TType.LIST;
            case "set" -> TType.SET;
            case "map" -> TType.MAP;
            case "struct", "union", "exception" -> TType.STRUCT;
            default -> throw this.error("Unsupported value type: " + kind);
        };
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(this.service.document.source + ": " + message);
    }
}

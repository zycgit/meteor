/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.codec;
import java.util.*;
import org.apache.thrift.TFieldRequirementType;
import org.apache.thrift.protocol.TType;

/** 从已编译类型导出 IDL；不重新读取注解，也不创建业务值。 */
final class ThriftContractWriter {
    private final Map<String, ThriftType>  named   = new TreeMap<>();
    private final Map<ThriftType, Boolean> visited = new IdentityHashMap<>();

    Map<String, String> sources(String name, Collection<ThriftMethodBinding> methods) {
        this.identifier(name);
        List<ThriftMethodBinding> ordered = new ArrayList<>(methods);
        ordered.sort(Comparator.comparing(method -> method.name));
        for (ThriftMethodBinding method : ordered) {
            this.collectFields(method.args);
            if (method.result != null) {
                this.collectFields(method.result);
            }
        }

        StringBuilder text = new StringBuilder();
        for (ThriftType type : this.named.values()) {
            if (type.javaType.isEnum()) {
                text.append("enum ").append(type.name).append(" {\n");
                for (Map.Entry<Object, Integer> value : type.enumNumbers.entrySet()) {
                    String symbol = ((Enum<?>) value.getKey()).name();
                    for (Map.Entry<String, Object> alias : type.enumNames.entrySet()) {
                        if (alias.getValue() == value.getKey()) {
                            symbol = alias.getKey();
                        }
                    }
                    text.append("  ").append(this.identifier(symbol)).append(" = ").append(value.getValue());
                    text.append(",\n");
                }
            } else {
                String kind = Throwable.class.isAssignableFrom(type.javaType) ? "exception" : "struct";
                if (type.union) {
                    kind = "union";
                }
                text.append(kind).append(' ').append(type.name).append(" {\n");
                for (ThriftFieldBinding field : type.fields) {
                    text.append("  ").append(this.field(field, true)).append(";\n");
                }
            }
            text.append("}\n\n");
        }

        // 成功字段 0 是回复包装的一部分，不作为用户声明的 throws 导出。
        text.append("service ").append(name).append(" {\n");
        for (ThriftMethodBinding method : ordered) {
            text.append("  ");
            if (method.oneway) {
                text.append("oneway ");
            }
            ThriftFieldBinding success = method.result == null ? null : method.result.fieldsById.get((short) 0);
            text.append(success == null ? "void" : this.type(success.type));
            text.append(' ').append(this.identifier(method.name)).append('(');
            this.fields(text, method.args.fields);
            text.append(')');
            List<ThriftFieldBinding> failures = new ArrayList<>();
            if (method.result != null) {
                for (ThriftFieldBinding field : method.result.fields) {
                    if (field.field.id != 0) {
                        failures.add(field);
                    }
                }
            }
            if (!failures.isEmpty()) {
                text.append(" throws (");
                this.fields(text, failures);
                text.append(')');
            }
            text.append(";\n");
        }
        text.append("}\n");
        return Map.of(name + ".thrift", text.toString());
    }

    private void collectFields(ThriftType type) {
        for (ThriftFieldBinding field : type.fields) {
            this.collect(field.type);
        }
    }

    private void collect(ThriftType type) {
        if (this.visited.put(type, Boolean.TRUE) != null) {
            return;
        }
        if (type.wireType == TType.STRUCT || type.javaType.isEnum()) {
            this.identifier(type.name);
            ThriftType previous = this.named.putIfAbsent(type.name, type);
            if (previous != null && previous != type) {
                throw new IllegalArgumentException("Conflicting Thrift type name: " + type.name);
            }
            this.collectFields(type);
        }
        if (type.key != null) {
            this.collect(type.key);
        }
        if (type.element != null) {
            this.collect(type.element);
        }
    }

    private String type(ThriftType type) {
        if (type.javaType.isEnum() || type.wireType == TType.STRUCT) {
            return type.name;
        }
        return switch (type.wireType) {
            case TType.BOOL -> "bool";
            case TType.BYTE -> "byte";
            case TType.I16 -> "i16";
            case TType.I32 -> "i32";
            case TType.I64 -> "i64";
            case TType.DOUBLE -> "double";
            case TType.UUID -> "uuid";
            case TType.STRING -> type.binary ? "binary" : "string";
            case TType.LIST -> "list<" + this.type(type.element) + ">";
            case TType.SET -> "set<" + this.type(type.element) + ">";
            case TType.MAP -> "map<" + this.type(type.key) + ", " + this.type(type.element) + ">";
            default -> throw new IllegalArgumentException("Cannot export Thrift type: " + type.wireType);
        };
    }

    private void fields(StringBuilder text, List<ThriftFieldBinding> fields) {
        for (int index = 0; index < fields.size(); index++) {
            if (index > 0) {
                text.append(", ");
            }
            text.append(this.field(fields.get(index), false));
        }
    }

    private String field(ThriftFieldBinding field, boolean requirement) {
        String prefix = "";
        if (requirement && field.requirement == TFieldRequirementType.REQUIRED) {
            prefix = "required ";
        } else if (requirement && field.requirement == TFieldRequirementType.OPTIONAL) {
            prefix = "optional ";
        }
        return field.field.id + ": " + prefix + this.type(field.type) + " " + this.identifier(field.field.name);
    }

    private String identifier(String name) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid Thrift identifier: " + name);
        }
        return name;
    }
}

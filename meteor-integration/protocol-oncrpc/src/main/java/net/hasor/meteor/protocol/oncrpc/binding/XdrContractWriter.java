/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.binding;
import java.util.*;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.protocol.oncrpc.definition.OncRpcIdlProcedure;
import net.hasor.meteor.protocol.oncrpc.definition.XdrIdlMember;
import net.hasor.meteor.protocol.oncrpc.definition.XdrIdlType;

/** 导出编译期确定的 XDR 声明；任意自定义 Codec 不被猜成契约。 */
final class XdrContractWriter {
    private final Map<String, XdrIdlType> named   = new LinkedHashMap<>();
    private final Set<XdrIdlType>         visited = Collections.newSetFromMap(new IdentityHashMap<>());
    private final StringBuilder           text    = new StringBuilder();

    Map<String, String> sources(String name, long program, long version, List<OncRpcIdlProcedure> procedures) {
        this.identifier(name);
        List<OncRpcIdlProcedure> ordered = new ArrayList<>(procedures);
        ordered.sort(Comparator.comparingLong(procedure -> procedure.id));
        for (OncRpcIdlProcedure procedure : ordered) {
            this.collect(procedure.input);
            this.collect(procedure.result);
        }
        for (XdrIdlType type : this.named.values()) {
            this.declaration(type);
        }
        this.text.append("program ").append(name).append(" {\n  version ").append(name).append("_V");
        this.text.append(version).append(" {\n");
        this.text.append("    void ").append(name).append("_NULL(void) = 0;\n");
        for (OncRpcIdlProcedure procedure : ordered) {
            this.text.append("    ").append(this.type(procedure.result)).append(' ');
            this.text.append(this.identifier(procedure.name)).append('(').append(this.type(procedure.input));
            this.text.append(") = ").append(procedure.id).append(";\n");
        }
        this.text.append("  } = ").append(version).append(";\n} = ").append(program).append(";\n");
        return Map.of(name + ".x", this.text.toString());
    }

    private void collect(XdrIdlType type) {
        if (type == null) {
            throw new UnsupportedOperationException("Custom XDR codecs require an explicit IDL to export a contract");
        }
        if (!this.visited.add(type)) {
            return;
        }
        // 依赖先导出，rpcgen 不要求用户为普通非递归类型增加前置声明。
        if (StringUtils.equals(type.kind, "array") && type.element == null) {
            throw new UnsupportedOperationException("Custom XDR array elements require an explicit IDL to export");
        }
        if (type.element != null) {
            this.collect(type.element);
        }
        for (XdrIdlMember member : type.members) {
            this.collect(member.type);
        }
        if (type.name != null) {
            this.identifier(type.name);
            XdrIdlType previous = this.named.putIfAbsent(type.name, type);
            if (previous != null && previous != type) {
                throw new IllegalArgumentException("Conflicting XDR type name: " + type.name);
            }
        }
    }

    private void declaration(XdrIdlType type) {
        if (StringUtils.equals(type.kind, "enum")) {
            this.text.append("enum ").append(type.name).append(" {\n");
            int index = 0;
            for (Map.Entry<String, Integer> entry : type.enumeration.entrySet()) {
                if (index++ > 0) {
                    this.text.append(",\n");
                }
                this.text.append("  ").append(this.identifier(entry.getKey())).append(" = ").append(entry.getValue());
            }
            this.text.append("\n};\n\n");
        } else if (StringUtils.equals(type.kind, "struct")) {
            this.text.append("struct ").append(type.name).append(" {\n");
            for (XdrIdlMember member : type.members) {
                this.text.append("  ").append(this.field(member.type, this.identifier(member.name))).append(";\n");
            }
            this.text.append("};\n\n");
        } else {
            this.text.append("typedef ").append(this.field(type, type.name)).append(";\n\n");
        }
    }

    private String type(XdrIdlType type) {
        if (type.name != null) {
            return type.name;
        }
        return switch (type.kind) {
            case "hyper" -> "hyper";
            case "array", "string", "opaque" -> throw new IllegalArgumentException("RPC arrays require a named type");
            default -> type.kind;
        };
    }

    private String field(XdrIdlType type, String name) {
        if (StringUtils.equals(type.kind, "array")) {
            return this.type(type.element) + " " + name + "<" + type.length + ">";
        }
        if (StringUtils.equals(type.kind, "opaque") || StringUtils.equals(type.kind, "string")) {
            return type.kind + " " + name + "<" + type.length + ">";
        }
        return this.type(type) + " " + name;
    }

    private String identifier(String name) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid XDR identifier: " + name);
        }
        return name;
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.definition;
import java.net.URL;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.hasor.cobble.StringUtils;

/** 一个预处理后的 RPC/XDR 文档及其符号表。 */
public final class XdrIdlDocument {
    public final URL                           source;
    public final String                        text;
    public final Map<String, Long>             constants = new LinkedHashMap<>();
    public final Map<String, XdrIdlType>       types     = new LinkedHashMap<>();
    public final Map<String, OncRpcIdlProgram> programs  = new LinkedHashMap<>();

    XdrIdlDocument(URL source, String text) {
        this.source = source;
        this.text = text;
    }

    public XdrIdlType resolve(XdrIdlType type) {
        Set<XdrIdlType> aliases = new HashSet<>();
        while (StringUtils.equals(type.kind, "reference") || StringUtils.equals(type.kind, "typedef")) {
            if (!aliases.add(type)) {
                throw new IllegalArgumentException(this.source + ": cyclic typedef " + type.name);
            }
            if (StringUtils.equals(type.kind, "typedef")) {
                type = type.element;
            } else {
                XdrIdlType target = this.types.get(type.name);
                if (target == null) {
                    throw new IllegalArgumentException(this.source + ": unknown type " + type.name);
                }
                type = target;
            }
        }
        return type;
    }
}

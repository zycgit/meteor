/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.definition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** XDR 声明类型，保留数组界限、枚举值和 union 分支。 */
public final class XdrIdlType {
    public final String               kind;
    public final String               name;
    public       XdrIdlType           element;
    public       boolean              fixed;
    public       long                 length      = -1;
    public       XdrIdlMember         discriminator;
    public final List<XdrIdlMember>   members     = new ArrayList<>();
    public final Map<String, Integer> enumeration = new LinkedHashMap<>();

    public XdrIdlType(String kind, String name) {
        this.kind = kind;
        this.name = name;
    }
}

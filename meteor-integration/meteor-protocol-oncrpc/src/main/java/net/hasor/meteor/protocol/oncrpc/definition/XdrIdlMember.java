/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.definition;
import java.util.ArrayList;
import java.util.List;

/** 结构成员或 union arm；名称和分支编号来自文件。 */
public final class XdrIdlMember {
    public final String     name;
    public final XdrIdlType type;
    public final List<Long> cases = new ArrayList<>();
    public       boolean    defaultArm;

    public XdrIdlMember(String name, XdrIdlType type) {
        this.name = name;
        this.type = type;
    }
}

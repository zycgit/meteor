/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.definition;
import java.util.LinkedHashMap;
import java.util.Map;

/** 文件中的程序与版本集合，不包含 Java 方法或运行状态。 */
public final class OncRpcIdlProgram {
    public final String                                     name;
    public final long                                       id;
    public final Map<Long, Map<String, OncRpcIdlProcedure>> versions     = new LinkedHashMap<>();
    public final Map<Long, String>                          versionNames = new LinkedHashMap<>();

    OncRpcIdlProgram(String name, long id) {
        this.name = name;
        this.id = id;
    }
}

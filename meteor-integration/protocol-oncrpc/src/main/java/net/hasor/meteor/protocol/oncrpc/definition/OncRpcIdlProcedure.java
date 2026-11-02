/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.definition;

/** 一个版本内的过程声明，输入和输出类型独立保留。 */
public final class OncRpcIdlProcedure {
    public final String     name;
    public final long       id;
    public final XdrIdlType input;
    public final XdrIdlType result;

    public OncRpcIdlProcedure(String name, long id, XdrIdlType input, XdrIdlType result) {
        this.name = name;
        this.id = id;
        this.input = input;
        this.result = result;
    }
}

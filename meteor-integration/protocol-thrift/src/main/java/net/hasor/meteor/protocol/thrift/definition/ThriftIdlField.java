/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.definition;
import java.util.LinkedHashMap;
import java.util.Map;

/** IDL 字段声明；不持有 Java 属性或业务对象。 */
public final class ThriftIdlField {
    public final short               id;
    public final String              name;
    public final ThriftIdlType       type;
    public final byte                requirement;
    public final Object              defaultValue;
    public final Map<String, String> annotations = new LinkedHashMap<>();

    public ThriftIdlField(short id, String name, ThriftIdlType type, byte requirement, Object defaultValue) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.requirement = requirement;
        this.defaultValue = defaultValue;
    }
}

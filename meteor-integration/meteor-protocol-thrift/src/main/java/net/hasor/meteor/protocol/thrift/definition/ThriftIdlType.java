/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.definition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** IDL 类型或服务符号；引用由所属文档解析，递归结构不复制展开。 */
public final class ThriftIdlType {
    public final String                           kind;
    public final String                           name;
    public       ThriftIdlDocument                document;
    public       ThriftIdlType                    element;
    public       ThriftIdlType                    key;
    public final List<ThriftIdlField>             fields                 = new ArrayList<>();
    public final Map<String, Map<String, String>> enumerationAnnotations = new LinkedHashMap<>();
    public final Map<String, Integer>             enumeration            = new LinkedHashMap<>();
    public final Map<String, ThriftIdlMethod>     methods                = new LinkedHashMap<>();
    public final Map<String, String>              annotations            = new LinkedHashMap<>();

    public ThriftIdlType(String kind, String name) {
        this.kind = kind;
        this.name = name;
    }
}

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

/** IDL 方法的线上契约；参数序列独立于字段编号。 */
public final class ThriftIdlMethod {
    public final String               name;
    public final ThriftIdlType        result;
    public final boolean              oneway;
    public final List<ThriftIdlField> parameters  = new ArrayList<>();
    public final List<ThriftIdlField> exceptions  = new ArrayList<>();
    public final Map<String, String>  annotations = new LinkedHashMap<>();

    public ThriftIdlMethod(String name, ThriftIdlType result, boolean oneway) {
        this.name = name;
        this.result = result;
        this.oneway = oneway;
    }
}

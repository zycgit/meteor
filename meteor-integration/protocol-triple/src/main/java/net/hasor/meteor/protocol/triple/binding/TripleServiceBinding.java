/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.triple.binding;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 一轮 POJO 服务发布的线上名称与 Java 方法名称映射。 */
public final class TripleServiceBinding {
    private final String              serviceName;
    private final Map<Method, String> methods;

    public TripleServiceBinding(String serviceName, Map<Method, String> methods) {
        this.serviceName = serviceName;
        this.methods = Collections.unmodifiableMap(new LinkedHashMap<>(methods));
    }

    public String serviceName() {
        return this.serviceName;
    }

    public Map<Method, String> methods() {
        return this.methods;
    }
}
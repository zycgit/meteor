/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hessian.codec;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.caucho.services.server.AbstractSkeleton;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.meteor.domain.meta.ServiceMetaParser;

/** 发布时编译普通名称与官方重载名称，保留原始 Java 方法用于派发。 */
public final class HessianServiceBinding {
    private final Map<Method, String>       outbound = new LinkedHashMap<>();
    private final Map<String, Method>       mangled  = new LinkedHashMap<>();
    private final Map<String, List<Method>> plain    = new LinkedHashMap<>();

    HessianServiceBinding(Map<Method, String> names) {
        // 先收集普通名称，同名方法的出站调用才采用官方 mangling 后缀。
        for (Map.Entry<Method, String> entry : names.entrySet()) {
            this.plain.computeIfAbsent(entry.getValue(), ignored -> new ArrayList<>()).add(entry.getKey());
        }

        for (Map.Entry<Method, String> entry : names.entrySet()) {
            Method method = entry.getKey();
            String name = entry.getValue();
            String standard = AbstractSkeleton.mangleName(method, false);
            String mangledName = name + standard.substring(method.getName().length());
            Method previous = this.mangled.putIfAbsent(mangledName, method);
            if (previous != null) {
                throw new IllegalArgumentException("Conflicting Hessian method name: " + mangledName);
            }
            String target = this.plain.get(name).size() > 1 ? mangledName : name;
            this.outbound.put(method, target);
        }
    }

    String name(Method method) {
        return this.outbound.get(method);
    }

    /** 保留实际出站的重载名称，避免把公共方法名当成 Hessian mangling 名称。 */
    public ObjectNode describeContract(ObjectNode description) {
        ArrayNode methods = description.putArray("methods");
        for (Map.Entry<Method, String> entry : this.outbound.entrySet()) {
            ObjectNode method = methods.addObject();
            method.put("name", entry.getValue());
            method.put("javaSignature", ServiceMetaParser.method(entry.getKey()).getJavaSignature());
        }
        return description;
    }

    Method resolve(String name, int argumentCount) {
        // 零参数的标准重载名称就是普通名，完全匹配仍具有优先级。
        Method exact = this.mangled.get(name);
        if (exact != null && (argumentCount < 0 || argumentCount == exact.getParameterCount())) {
            return exact;
        }
        Method selected = null;
        for (Method method : this.plain.getOrDefault(name, List.of())) {
            if (argumentCount >= 0 && argumentCount != method.getParameterCount()) {
                continue;
            }
            if (selected != null) {
                throw new HessianFaultException("NoSuchMethodException", "Ambiguous overloaded Hessian method");
            }
            selected = method;
        }
        if (selected == null) {
            throw new HessianFaultException("NoSuchMethodException", "Hessian method not found: " + name);
        }
        return selected;
    }
}

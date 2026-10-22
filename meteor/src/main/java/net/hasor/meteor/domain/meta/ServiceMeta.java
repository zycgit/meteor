/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.domain.meta;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.hasor.cobble.reflect.resolvable.ResolvableType;
import net.hasor.meteor.annotation.MetService;

/** Java 服务的公共声明；服务配置直接读取注解，最终注册值由 ServiceDomain 管理。 */
public final class ServiceMeta {
    private final Class<?>                                javaType;
    private final MetService                              declaration;
    private final List<MethodMeta>                        exposedMethods;
    private final boolean                                 message;
    private final Map<Method, MethodMeta>                 methods;
    private final ConcurrentMap<ResolvableType, TypeMeta> types = new ConcurrentHashMap<>();

    ServiceMeta(Class<?> javaType, MetService declaration, boolean message, Map<Method, MethodMeta> methods) {
        this.javaType = javaType;
        this.declaration = declaration;
        this.message = message;
        this.methods = Collections.unmodifiableMap(methods);
        List<MethodMeta> exposed = new ArrayList<>();
        for (MethodMeta method : methods.values()) {
            if (!method.isIgnored()) {
                exposed.add(method);
            }
        }
        this.exposedMethods = List.copyOf(exposed);
    }

    public Class<?> getJavaType() {
        return this.javaType;
    }

    public String getName() {
        return this.declaration == null ? "" : this.declaration.name();
    }

    public String getGroup() {
        return this.declaration == null ? "" : this.declaration.group();
    }

    public String getVersion() {
        return this.declaration == null ? "" : this.declaration.version();
    }

    public int getClientTimeout() {
        return this.declaration == null ? -1 : this.declaration.clientTimeout();
    }

    public String getSerializeType() {
        return this.declaration == null ? "" : this.declaration.serializeType();
    }

    public boolean isMessage() {
        return this.message;
    }

    public Collection<MethodMeta> getMethods() {
        return this.exposedMethods;
    }

    public MethodMeta getMethod(Method method) {
        return this.methods.get(method);
    }

    /** DTO 按完整声明类型复用描述，泛型实参不同的类型分别保存。 */
    public TypeMeta getType(ResolvableType type) {
        return this.types.computeIfAbsent(type, ServiceMetaParser::type);
    }
}

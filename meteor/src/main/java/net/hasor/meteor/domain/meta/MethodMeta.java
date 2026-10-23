/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.domain.meta;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.Flow;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.reflect.resolvable.ResolvableType;
import net.hasor.meteor.annotation.MetMethod;
import net.hasor.meteor.annotation.MetReturn;

/** 原始 Java 方法与公共映射；协议操作身份在插件转换时确定。 */
public final class MethodMeta {
    private final long                id;
    private final Class<?>            serviceType;
    private final Method              javaMethod;
    private final String              declaredName;
    private final String              returnName;
    private final boolean             message;
    private final boolean             ignored;
    private final List<ParameterMeta> parameters;
    private final ResolvableType      returnType;

    MethodMeta(Class<?> owner, Method method, MetMethod mapping, MetReturn result, boolean message, boolean ignored) {
        this.serviceType = owner;
        this.javaMethod = method;
        this.declaredName = mapping == null || mapping.name().isEmpty() ? null : mapping.name();
        this.id = mapping == null ? 0L : mapping.id();
        this.returnName = result == null ? null : result.name();
        this.message = message;
        this.ignored = ignored;
        this.returnType = ServiceMetaParser.methodType(owner, method, -1);
        // 按 Java 参数位置逐项加入，注解编号不参与排序。
        List<ParameterMeta> parameters = new ArrayList<>();
        for (int index = 0; index < method.getParameterCount(); index++) {
            parameters.add(ServiceMetaParser.parameter(owner, method, index));
        }
        this.parameters = List.copyOf(parameters);
    }

    public Class<?> getServiceType() {
        return this.serviceType;
    }

    public Method getJavaMethod() {
        return this.javaMethod;
    }

    public String getJavaName() {
        return this.javaMethod.getName();
    }

    /** Java 调用签名用于对应端点操作，公共改名不改变此身份。 */
    public String getJavaSignature() {
        StringJoiner arguments = new StringJoiner(",", this.getJavaName() + "(", ")");
        for (Class<?> argument : this.javaMethod.getParameterTypes()) {
            arguments.add(argument.getTypeName());
        }
        return arguments.toString();
    }

    public String getDeclaredName() {
        return this.declaredName;
    }

    public long getId() {
        return this.id;
    }

    public String getReturnName() {
        return this.returnName;
    }

    public boolean isMessage() {
        return this.message;
    }

    public boolean isIgnored() {
        return this.ignored;
    }

    public List<ParameterMeta> getParameters() {
        return this.parameters;
    }

    public ResolvableType getReturnType() {
        return this.returnType;
    }

    public Class<?>[] getExceptionTypes() {
        return this.javaMethod.getExceptionTypes();
    }

    public String getName() {
        if (this.declaredName == null) {
            return this.getJavaName();
        }
        if (StringUtils.isBlank(this.declaredName)) {
            throw new IllegalArgumentException("Protocol method name must not be blank: " + this.javaMethod);
        }
        return this.declaredName;
    }

    /** 保留 Publisher 本身的签名，转换消息契约时再读取其元素类型。 */
    public ResolvableType getResponseType() {
        if (this.returnType.resolve() == Flow.Publisher.class) {
            return this.returnType.getGeneric(0);
        }
        return this.returnType;
    }
}

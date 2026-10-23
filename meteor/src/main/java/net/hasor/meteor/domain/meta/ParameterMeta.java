/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.domain.meta;
import java.lang.reflect.Parameter;
import java.util.concurrent.Flow;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.reflect.resolvable.ResolvableType;
import net.hasor.meteor.annotation.MetParam;

/** 参数的 Java 位置、完整声明类型与可选的公共名称、编号。 */
public final class ParameterMeta {
    private final int            fieldId;
    private final String         name;
    private final int            argIndex;
    private final Parameter      javaParameter;
    private final ResolvableType type;
    private final boolean        declared;

    ParameterMeta(Parameter parameter, int argIndex, ResolvableType type, MetParam declaration) {
        this.fieldId = declaration == null ? 0 : declaration.id();
        this.name = declaration == null ? "" : declaration.name();
        this.argIndex = argIndex;
        this.javaParameter = parameter;
        this.type = type;
        this.declared = declaration != null;
    }

    public int getFieldId() {
        return this.fieldId;
    }

    public String getDeclaredName() {
        return this.name;
    }

    /** 未提供名称且编译结果没有真实参数名时返回 null，由协议决定位置参数的表达。 */
    public String getName() {
        if (!this.name.isEmpty()) {
            if (StringUtils.isBlank(this.name)) {
                throw new IllegalArgumentException("Protocol parameter name must not be blank: " + this.javaParameter);
            }
            return this.name;
        }
        return this.javaParameter.isNamePresent() ? this.javaParameter.getName() : null;
    }

    public int getArgIndex() {
        return this.argIndex;
    }

    public Parameter getJavaParameter() {
        return this.javaParameter;
    }

    public ResolvableType getType() {
        return this.type;
    }

    public ResolvableType getMessageType() {
        if (this.type.resolve() == Flow.Publisher.class) {
            return this.type.getGeneric(0);
        }
        return this.type;
    }

    public boolean isDeclared() {
        return this.declared;
    }
}

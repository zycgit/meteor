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
    private final Parameter      javaParameter;
    private final int            position;
    private final ResolvableType type;
    private final String         declaredName;
    private final int            id;
    private final boolean        declared;

    ParameterMeta(Parameter parameter, int position, ResolvableType type, MetParam declaration) {
        this.javaParameter = parameter;
        this.position = position;
        this.type = type;
        this.declared = declaration != null;
        this.declaredName = declaration == null ? "" : declaration.name();
        this.id = declaration == null ? 0 : declaration.id();
    }

    public Parameter getJavaParameter() {
        return this.javaParameter;
    }

    public int getPosition() {
        return this.position;
    }

    public ResolvableType getType() {
        return this.type;
    }

    public String getDeclaredName() {
        return this.declaredName;
    }

    public int getId() {
        return this.id;
    }

    public boolean isDeclared() {
        return this.declared;
    }

    /** 未提供名称且编译结果没有真实参数名时返回 null，由协议决定位置参数的表达。 */
    public String getName() {
        if (!this.declaredName.isEmpty()) {
            if (StringUtils.isBlank(this.declaredName)) {
                throw new IllegalArgumentException("Protocol parameter name must not be blank: " + this.javaParameter);
            }
            return this.declaredName;
        }
        return this.javaParameter.isNamePresent() ? this.javaParameter.getName() : null;
    }

    public ResolvableType getMessageType() {
        if (this.type.resolve() == Flow.Publisher.class) {
            return this.type.getGeneric(0);
        }
        return this.type;
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.domain.meta;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import net.hasor.cobble.reflect.resolvable.ResolvableType;

/** 一个 Java 属性上的公共声明合并结果，访问成员保留给各协议选择。 */
public final class PropertyMeta {
    private final int                    fieldId;
    private final String                 name;
    private final String                 javaName;
    private final ResolvableType         type;
    private final List<AnnotatedElement> members;
    private final boolean                declared;

    PropertyMeta(String javaName, ResolvableType type, List<AnnotatedElement> members, //
            String name, int fieldId, boolean declared) {
        this.fieldId = fieldId;
        this.name = name;
        this.javaName = javaName;
        this.type = type;
        this.members = List.copyOf(members);
        this.declared = declared;
    }

    public int getFieldId() {
        return this.fieldId;
    }

    public String getDeclaredName() {
        return this.name;
    }

    public String getName() {
        return this.name.isEmpty() ? this.javaName : this.name;
    }

    public String getJavaName() {
        return this.javaName;
    }

    public ResolvableType getType() {
        return this.type;
    }

    public List<AnnotatedElement> getMembers() {
        return this.members;
    }

    public Field getField() {
        for (AnnotatedElement member : this.members) {
            if (member instanceof Field field) {
                return field;
            }
        }
        return null;
    }

    public Method getGetter() {
        for (AnnotatedElement member : this.members) {
            if (member instanceof Method method && method.getParameterCount() == 0) {
                return method;
            }
        }
        return null;
    }

    public Method getSetter() {
        for (AnnotatedElement member : this.members) {
            if (member instanceof Method method && method.getParameterCount() == 1) {
                return method;
            }
        }
        return null;
    }

    public boolean isDeclared() {
        return this.declared;
    }
}

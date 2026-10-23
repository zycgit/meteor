/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.domain.meta;
import net.hasor.meteor.annotation.MetEnumValue;

/** 枚举常量声明；未标注和显式数值零分别保留。 */
public final class EnumValueMeta {
    private final int     fieldId;
    private final String  name;
    private final Enum<?> javaValue;
    private final boolean declared;

    EnumValueMeta(Enum<?> javaValue, MetEnumValue declaration) {
        this.fieldId = declaration == null ? 0 : declaration.id();
        this.name = declaration == null ? "" : declaration.name();
        this.javaValue = javaValue;
        this.declared = declaration != null;
    }

    public int getFieldId() {
        return this.fieldId;
    }

    public String getDeclaredName() {
        return this.name;
    }

    public String getName() {
        return this.name.isEmpty() ? this.getJavaName() : this.name;
    }

    public Enum<?> getJavaValue() {
        return this.javaValue;
    }

    public String getJavaName() {
        return this.javaValue.name();
    }

    public boolean isDeclared() {
        return this.declared;
    }
}

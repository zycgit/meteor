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
    private final Enum<?> javaValue;
    private final String  declaredName;
    private final int     id;
    private final boolean declared;

    EnumValueMeta(Enum<?> javaValue, MetEnumValue declaration) {
        this.javaValue = javaValue;
        this.declaredName = declaration == null ? "" : declaration.name();
        this.id = declaration == null ? 0 : declaration.id();
        this.declared = declaration != null;
    }

    public Enum<?> getJavaValue() {
        return this.javaValue;
    }

    public String getJavaName() {
        return this.javaValue.name();
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

    public String getName() {
        return this.declaredName.isEmpty() ? this.getJavaName() : this.declaredName;
    }
}

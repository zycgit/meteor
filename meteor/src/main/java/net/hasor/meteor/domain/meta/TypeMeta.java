/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.domain.meta;
import java.util.List;
import java.util.Map;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.reflect.resolvable.ResolvableType;
import net.hasor.meteor.annotation.MetType;

/** Java 类型声明；属性类型以引用保存，递归 DTO 不展开为无限嵌套对象。 */
public final class TypeMeta {
    private final    String                     name;
    private final    ResolvableType             javaType;
    private volatile Map<String, PropertyMeta>  properties;
    private volatile Map<String, EnumValueMeta> enumValues;

    TypeMeta(ResolvableType javaType, MetType declaration) {
        this.name = declaration == null ? null : declaration.name();
        this.javaType = javaType;
    }

    public String getDeclaredName() {
        return this.name;
    }

    public String getName() {
        if (this.name == null) {
            return this.getRawType().getSimpleName();
        }
        if (StringUtils.isBlank(this.name)) {
            throw new IllegalArgumentException("Protocol type name must not be blank: " + this.getRawType().getName());
        }
        return this.name;
    }

    public ResolvableType getJavaType() {
        return this.javaType;
    }

    public Class<?> getRawType() {
        return this.javaType.resolve(Object.class);
    }

    public List<ResolvableType> getArguments() {
        return List.of(this.javaType.getGenerics());
    }

    /** 按需发现 Java 属性，端点的序列化器继续决定最终参与编码的成员。 */
    public Map<String, PropertyMeta> getProperties() {
        Map<String, PropertyMeta> result = this.properties;
        if (result == null) {
            result = ServiceMetaParser.properties(this.javaType);
            this.properties = result;
        }
        return result;
    }

    public Map<String, EnumValueMeta> getEnumValues() {
        Map<String, EnumValueMeta> result = this.enumValues;
        if (result == null) {
            result = ServiceMetaParser.enumeration(this.getRawType());
            this.enumValues = result;
        }
        return result;
    }

    public boolean isDeclared() {
        return this.name != null;
    }
}

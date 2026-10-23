/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.admin.description;
import java.util.LinkedHashMap;
import java.util.Map;

/** 参数、返回值、对象属性及枚举项的共同声明；不适用的成员为 null。 */
public class MetFieldDescription {
    /** 契约字段或枚举编号；未声明时为 null。 */
    private Long                fieldId    = null;
    /** 公共契约中的名称。 */
    private String              name       = null;
    /** Java 参数位置，从零开始，仅参数适用。 */
    private Integer             argIndex   = null;
    /** 原始属性名或枚举值名称。 */
    private String              javaName   = null;
    /** 完整类型引用，枚举项不适用。 */
    private String              type       = null;
    /** 未类型化的扩展字段；值仅包含 Map、List、标量及 null。 */
    private Map<String, Object> extensions = new LinkedHashMap<>();

    public Long getFieldId() {
        return this.fieldId;
    }

    public void setFieldId(Long fieldId) {
        this.fieldId = fieldId;
    }

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getArgIndex() {
        return this.argIndex;
    }

    public void setArgIndex(Integer argIndex) {
        this.argIndex = argIndex;
    }

    public String getJavaName() {
        return this.javaName;
    }

    public void setJavaName(String javaName) {
        this.javaName = javaName;
    }

    public String getType() {
        return this.type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Map<String, Object> getExtensions() {
        return this.extensions;
    }

    public void setExtensions(Map<String, Object> extensions) {
        this.extensions = extensions;
    }
}

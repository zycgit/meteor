/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.admin.description;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 公共类型图中的一个节点；泛型、容器及递归关系通过字符串引用连接。 */
public class MetTypeDescription {
    /** 公共契约中的类型名称。 */
    private String                    name        = null;
    /** 原始 Java 类型名称，不加载对应类。 */
    private String                    javaType    = null;
    /** 类型类别；保留未知类别以兼容新增形状。 */
    private String                    kind        = null;
    /** 数组元素的完整类型引用。 */
    private String                    elementType = null;
    /** 按声明顺序排列的泛型实参引用。 */
    private List<String>              arguments   = new ArrayList<>();
    /** 对象属性声明。 */
    private List<MetFieldDescription> properties  = new ArrayList<>();
    /** 枚举值声明。 */
    private List<MetFieldDescription> values      = new ArrayList<>();
    /** 未类型化的扩展字段；值仅包含 Map、List、标量及 null。 */
    private Map<String, Object>       extensions  = new LinkedHashMap<>();

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getJavaType() {
        return this.javaType;
    }

    public void setJavaType(String javaType) {
        this.javaType = javaType;
    }

    public String getKind() {
        return this.kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getElementType() {
        return this.elementType;
    }

    public void setElementType(String elementType) {
        this.elementType = elementType;
    }

    public List<String> getArguments() {
        return this.arguments;
    }

    public void setArguments(List<String> arguments) {
        this.arguments = arguments;
    }

    public List<MetFieldDescription> getProperties() {
        return this.properties;
    }

    public void setProperties(List<MetFieldDescription> properties) {
        this.properties = properties;
    }

    public List<MetFieldDescription> getValues() {
        return this.values;
    }

    public void setValues(List<MetFieldDescription> values) {
        this.values = values;
    }

    public Map<String, Object> getExtensions() {
        return this.extensions;
    }

    public void setExtensions(Map<String, Object> extensions) {
        this.extensions = extensions;
    }
}

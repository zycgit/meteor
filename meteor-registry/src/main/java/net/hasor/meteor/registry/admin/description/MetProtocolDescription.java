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

/** 一个实际端点的协议契约；协议专属方法和类型形状保留为 JDK 数据。 */
public class MetProtocolDescription {
    /** 端点实际使用的协议服务名称。 */
    private String                    name          = null;
    /** 端点实际使用的序列化名称。 */
    private String                    serializeType = null;
    /** 协议操作声明，不强套公共 Java 方法形状。 */
    private List<Map<String, Object>> methods       = new ArrayList<>();
    /** 协议专属类型图，保持协议自己的字段形状。 */
    private Map<String, Object>       types         = new LinkedHashMap<>();
    /** 未类型化的扩展字段；值仅包含 Map、List、标量及 null。 */
    private Map<String, Object>       extensions    = new LinkedHashMap<>();

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSerializeType() {
        return this.serializeType;
    }

    public void setSerializeType(String serializeType) {
        this.serializeType = serializeType;
    }

    public List<Map<String, Object>> getMethods() {
        return this.methods;
    }

    public void setMethods(List<Map<String, Object>> methods) {
        this.methods = methods;
    }

    public Map<String, Object> getTypes() {
        return this.types;
    }

    public void setTypes(Map<String, Object> types) {
        this.types = types;
    }

    public Map<String, Object> getExtensions() {
        return this.extensions;
    }

    public void setExtensions(Map<String, Object> extensions) {
        this.extensions = extensions;
    }
}

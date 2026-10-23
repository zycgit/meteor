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
import net.hasor.meteor.domain.MetServiceType;

/** 一个实例上报的完整服务声明；类型引用和协议端点分别保留。 */
public class MetServiceDescription {
    /** 数值契约编号；未声明时为 null。 */
    private Long                                             contractServiceId = null;
    /** 服务接口的 Java 类型名称。 */
    private String                                           javaType          = null;
    /** 按声明顺序保存的方法列表。 */
    private List<MetMethodDescription>                       methods           = new ArrayList<>();
    /** 以完整类型引用为键的公共类型图。 */
    private Map<String, MetTypeDescription>                  types             = new LinkedHashMap<>();
    /** 按协议名、连接器名保存实际端点契约。 */
    private Map<String, Map<String, MetProtocolDescription>> protocols         = new LinkedHashMap<>();
    /** 当前实例的提供或消费角色。 */
    private MetServiceType                                   role              = null;
    /** 是否为单向消息服务。 */
    private boolean                                          message           = false;
    /** 未类型化的扩展字段；值仅包含 Map、List、标量及 null。 */
    private Map<String, Object>                              extensions        = new LinkedHashMap<>();

    public Long getContractServiceId() {
        return this.contractServiceId;
    }

    public void setContractServiceId(Long contractServiceId) {
        this.contractServiceId = contractServiceId;
    }

    public String getJavaType() {
        return this.javaType;
    }

    public void setJavaType(String javaType) {
        this.javaType = javaType;
    }

    public List<MetMethodDescription> getMethods() {
        return this.methods;
    }

    public void setMethods(List<MetMethodDescription> methods) {
        this.methods = methods;
    }

    public Map<String, MetTypeDescription> getTypes() {
        return this.types;
    }

    public void setTypes(Map<String, MetTypeDescription> types) {
        this.types = types;
    }

    public Map<String, Map<String, MetProtocolDescription>> getProtocols() {
        return this.protocols;
    }

    public void setProtocols(Map<String, Map<String, MetProtocolDescription>> protocols) {
        this.protocols = protocols;
    }

    public MetServiceType getRole() {
        return this.role;
    }

    public void setRole(MetServiceType role) {
        this.role = role;
    }

    public boolean isMessage() {
        return this.message;
    }

    public void setMessage(boolean message) {
        this.message = message;
    }

    public Map<String, Object> getExtensions() {
        return this.extensions;
    }

    public void setExtensions(Map<String, Object> extensions) {
        this.extensions = extensions;
    }
}

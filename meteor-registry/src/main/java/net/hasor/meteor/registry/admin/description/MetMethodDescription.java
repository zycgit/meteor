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

/** 公共 Java 方法的纯数据声明，不持有反射对象或运行状态。 */
public class MetMethodDescription {
    /** 数值方法编号；未声明时为 null。 */
    private Long                      methodId      = null;
    /** 公共契约中的方法名称。 */
    private String                    name          = null;
    /** 用于关联协议操作的 Java 方法签名。 */
    private String                    javaSignature = null;
    /** 原始 Java 方法名称。 */
    private String                    javaName      = null;
    /** 按 Java 调用位置排列的参数。 */
    private List<MetFieldDescription> parameters    = new ArrayList<>();
    /** 返回值的名称和类型引用。 */
    private MetFieldDescription       returns       = null;
    /** 声明异常的完整类型引用。 */
    private List<String>              exceptions    = new ArrayList<>();
    /** 是否为单向消息方法。 */
    private boolean                   message       = false;
    /** 未类型化的扩展字段；值仅包含 Map、List、标量及 null。 */
    private Map<String, Object>       extensions    = new LinkedHashMap<>();

    public Long getMethodId() {
        return this.methodId;
    }

    public void setMethodId(Long methodId) {
        this.methodId = methodId;
    }

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getJavaSignature() {
        return this.javaSignature;
    }

    public void setJavaSignature(String javaSignature) {
        this.javaSignature = javaSignature;
    }

    public String getJavaName() {
        return this.javaName;
    }

    public void setJavaName(String javaName) {
        this.javaName = javaName;
    }

    public List<MetFieldDescription> getParameters() {
        return this.parameters;
    }

    public void setParameters(List<MetFieldDescription> parameters) {
        this.parameters = parameters;
    }

    public MetFieldDescription getReturns() {
        return this.returns;
    }

    public void setReturns(MetFieldDescription returns) {
        this.returns = returns;
    }

    public List<String> getExceptions() {
        return this.exceptions;
    }

    public void setExceptions(List<String> exceptions) {
        this.exceptions = exceptions;
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

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared.storage.domain;
import java.util.LinkedHashMap;
import java.util.Map;

/** 实例服务记录中的声明；不存储实例身份、服务 ID 或临时统计数量。 */
public final class EntServiceDeclarationData {
    private String              group            = null; // 服务分组
    private String              name             = null; // 服务名称
    private String              version          = null; // 服务版本
    private String              type             = null; // 服务接口类型
    private String              role             = null; // Provider 或 Consumer
    private String[]            protocols        = new String[0]; // 支持的协议
    private Map<String, String> aliases          = new LinkedHashMap<>(); // 服务类型别名
    private String[]            methods          = new String[0]; // 已发布的方法签名
    private int                 timeout          = 0; // 调用超时毫秒数
    private String              serializeType    = null; // 序列化器名称
    private boolean             message          = false; // 是否单向消息服务
    private boolean             sharedThreadPool = false; // 是否共享执行线程池

    public String getGroup() {
        return this.group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return this.version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getType() {
        return this.type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getRole() {
        return this.role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String[] getProtocols() {
        return this.protocols;
    }

    public void setProtocols(String[] protocols) {
        this.protocols = protocols;
    }

    public Map<String, String> getAliases() {
        return this.aliases;
    }

    public void setAliases(Map<String, String> aliases) {
        this.aliases = aliases;
    }

    public String[] getMethods() {
        return this.methods;
    }

    public void setMethods(String[] methods) {
        this.methods = methods;
    }

    public int getTimeout() {
        return this.timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public String getSerializeType() {
        return this.serializeType;
    }

    public void setSerializeType(String serializeType) {
        this.serializeType = serializeType;
    }

    public boolean isMessage() {
        return this.message;
    }

    public void setMessage(boolean message) {
        this.message = message;
    }

    public boolean isSharedThreadPool() {
        return this.sharedThreadPool;
    }

    public void setSharedThreadPool(boolean sharedThreadPool) {
        this.sharedThreadPool = sharedThreadPool;
    }
}

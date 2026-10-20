/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.domain;
import java.util.Map;
import net.hasor.meteor.MetBindInfo;

/** 一个服务在某个协议下的原始路径声明和各连接器发布结果。 */
public final class ProtocolMeta {
    private final MetBindInfo<?>               service;
    private final ContextPath                  contextPath;
    private final Map<String, ProtocolBinding> endpoints;

    public ProtocolMeta(MetBindInfo<?> service, ContextPath contextPath, Map<String, ProtocolBinding> endpoints) {
        this.service = service;
        this.contextPath = contextPath;
        this.endpoints = endpoints;
    }

    public MetBindInfo<?> getService() {
        return this.service;
    }

    /** 原始路径声明；未指定时为 null，与各端点的有效默认路径分别保存。 */
    public ContextPath getContextPath() {
        return this.contextPath;
    }

    /** 按连接器名称返回发布结果。 */
    public Map<String, ProtocolBinding> getEndpoints() {
        return this.endpoints;
    }

    public ProtocolBinding getEndpoint(String connectorName) {
        return this.endpoints.get(connectorName);
    }
}

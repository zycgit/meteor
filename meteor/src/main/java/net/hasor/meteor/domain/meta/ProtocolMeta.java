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
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.domain.ContextPath;
import net.hasor.meteor.domain.ProtocolBinding;
import net.hasor.meteor.domain.ProtocolConfig;

/** 一个服务在某个协议下的原始路径声明和各连接器发布结果。 */
public final class ProtocolMeta {
    private final MetBindInfo<?>               service;
    private final ContextPath                  contextPath;
    private final Object                       bindResource;
    private final Map<String, ProtocolBinding> endpoints;

    public ProtocolMeta(MetBindInfo<?> service, ContextPath contextPath, //
            Object bindResource, Map<String, ProtocolBinding> endpoints) {
        this.service = service;
        this.contextPath = contextPath;
        this.bindResource = bindResource;
        this.endpoints = endpoints;
    }

    public MetBindInfo<?> getService() {
        return this.service;
    }

    /** 公共 Java 契约由所属服务持有，各端点共享同一份描述。 */
    public ServiceMeta getServiceMeta() {
        return ServiceMetaParser.parse(this.service.getBindType());
    }

    /** 原始路径声明；未指定时为 null，与各端点的有效默认路径分别保存。 */
    public ContextPath getContextPath() {
        return this.contextPath;
    }

    /** 当前协议绑定的本地资源；未绑定时返回 null。 */
    public Object getBindResource() {
        return this.bindResource;
    }

    /** 合并当前端点的默认路径，一次创建绑定；原始路径声明保持不变。 */
    public ProtocolBinding build(ProtocolConfig config, Object definition) {
        ContextPath effective = this.contextPath;
        if (effective == null) {
            String defaultPath = config.option("contextPath", null);
            if (defaultPath != null) {
                effective = new ContextPath(List.of(defaultPath), false);
            }
        }

        return new ProtocolBinding(effective, definition);
    }

    /** 按连接器名称返回发布结果。 */
    public Map<String, ProtocolBinding> getEndpoints() {
        return this.endpoints;
    }

    public ProtocolBinding getEndpoint(String connectorName) {
        return this.endpoints.get(connectorName);
    }
}

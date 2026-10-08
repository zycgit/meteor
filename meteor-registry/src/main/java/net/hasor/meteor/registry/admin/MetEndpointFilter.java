/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin;

/** 非空条件共同匹配同一个端点；null 或空白字段不参与筛选。 */
public final class MetEndpointFilter {
    private String instanceId    = null; // 精确匹配实例 ID
    private String connectorName = null; // 实例内的连接器名称
    private String transport     = null; // 传输 SPI 名称，如 tcp、http
    private String protocol      = null; // 协议 SPI 名称，如 rsf、hprose
    private String scheme        = null; // 协议地址 scheme
    private String contextPath   = null; // 协议上下文路径；未配置时为空
    private String address       = null; // 精确匹配配置、监听或发布地址之一

    public String getInstanceId() {
        return this.instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public String getConnectorName() {
        return this.connectorName;
    }

    public void setConnectorName(String connectorName) {
        this.connectorName = connectorName;
    }

    public String getTransport() {
        return this.transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }

    public String getProtocol() {
        return this.protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getScheme() {
        return this.scheme;
    }

    public void setScheme(String scheme) {
        this.scheme = scheme;
    }

    public String getContextPath() {
        return this.contextPath;
    }

    public void setContextPath(String contextPath) {
        this.contextPath = contextPath;
    }

    public String getAddress() {
        return this.address;
    }

    public void setAddress(String address) {
        this.address = address;
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared.storage.domain;

/** 实例服务及服务成员中的协议挂载正文；仅保存上报的连接器和协议端点字段。 */
public final class RefProtocolMountData {
    private String  connectorName     = null; // 实例内的连接器名称
    private String  transport         = null; // 传输 SPI 名称，如 tcp、http
    private String  protocolName      = null; // 协议挂载名称
    private String  protocol          = null; // 协议 SPI 名称，如 rsf、hprose
    private String  scheme            = null; // 协议地址 scheme
    private String  contextPath       = null; // 协议上下文路径；未配置时为空
    private boolean bindEnabled       = false; // 是否配置监听
    private String  configuredAddress = null; // 连接器配置地址，不表示已建立连接的本地地址
    private String  listenAddress     = null; // 实际协议监听地址；无监听时为空
    private String  publishedAddress  = null; // 当前服务已发布的地址；订阅端或未发布时为空

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

    public String getProtocolName() {
        return this.protocolName;
    }

    public void setProtocolName(String protocolName) {
        this.protocolName = protocolName;
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

    public boolean isBindEnabled() {
        return this.bindEnabled;
    }

    public void setBindEnabled(boolean bindEnabled) {
        this.bindEnabled = bindEnabled;
    }

    public String getConfiguredAddress() {
        return this.configuredAddress;
    }

    public void setConfiguredAddress(String configuredAddress) {
        this.configuredAddress = configuredAddress;
    }

    public String getListenAddress() {
        return this.listenAddress;
    }

    public void setListenAddress(String listenAddress) {
        this.listenAddress = listenAddress;
    }

    public String getPublishedAddress() {
        return this.publishedAddress;
    }

    public void setPublishedAddress(String publishedAddress) {
        this.publishedAddress = publishedAddress;
    }
}

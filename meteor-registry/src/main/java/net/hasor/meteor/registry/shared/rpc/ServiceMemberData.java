/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared.rpc;

public final class ServiceMemberData {
    private ServiceEndpointData[] endpoints              = new ServiceEndpointData[0];
    private String[]              addresses              = new String[0];
    private InstanceData          instance               = null;
    private ServiceData           service                = null;
    private boolean               enabled                = true;
    private ConfigurationData     effectiveConfiguration = null;

    /** 消费者当前服务地址本中的协议，去重排序；不表示历史调用统计。 */
    private String[] usedProtocols = new String[0];

    public String[] getUsedProtocols() {
        return this.usedProtocols;
    }

    public void setUsedProtocols(String[] usedProtocols) {
        this.usedProtocols = usedProtocols;
    }

    /** 获取当前服务使用的发布或订阅端点，包含尚未发布的端点。 */
    public ServiceEndpointData[] getEndpoints() {
        return this.endpoints;
    }

    public void setEndpoints(ServiceEndpointData[] endpoints) {
        this.endpoints = endpoints;
    }

    public String[] getAddresses() {
        return this.addresses;
    }

    public void setAddresses(String[] addresses) {
        this.addresses = addresses;
    }

    public InstanceData getInstance() {
        return this.instance;
    }

    public void setInstance(InstanceData instance) {
        this.instance = instance;
    }

    public ServiceData getService() {
        return this.service;
    }

    public void setService(ServiceData service) {
        this.service = service;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public ConfigurationData getEffectiveConfiguration() {
        return this.effectiveConfiguration;
    }

    public void setEffectiveConfiguration(ConfigurationData effectiveConfiguration) {
        this.effectiveConfiguration = effectiveConfiguration;
    }

}

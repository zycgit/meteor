/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin;
import java.util.Set;
import java.util.TreeSet;

public final class MetServiceMember {
    private MetServiceEndpoint[] endpoints = new MetServiceEndpoint[0];
    private MetInstanceInfo      instance  = null;
    private MetServiceInfo       service   = null;
    private boolean              enabled   = true;

    /** 消费者当前服务地址本中的协议，去重排序；不表示历史调用统计。 */
    private String[] usedProtocols = new String[0];

    public String[] getUsedProtocols() {
        return this.usedProtocols;
    }

    public void setUsedProtocols(String[] usedProtocols) {
        this.usedProtocols = usedProtocols;
    }

    /** 获取当前服务使用的发布或订阅端点，包含尚未发布的端点。 */
    public MetServiceEndpoint[] getEndpoints() {
        return this.endpoints;
    }

    public void setEndpoints(MetServiceEndpoint[] endpoints) {
        this.endpoints = endpoints;
    }

    /** 从端点汇总当前发布地址，去重并按地址排序。 */
    public String[] getAddresses() {
        Set<String> addresses = new TreeSet<>();
        for (MetServiceEndpoint endpoint : this.endpoints) {
            String address = endpoint.getPublishedAddress();
            if (address != null) {
                addresses.add(address);
            }
        }
        return addresses.toArray(String[]::new);
    }

    public MetInstanceInfo getInstance() {
        return this.instance;
    }

    public void setInstance(MetInstanceInfo instance) {
        this.instance = instance;
    }

    public MetServiceInfo getService() {
        return this.service;
    }

    public void setService(MetServiceInfo service) {
        this.service = service;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}

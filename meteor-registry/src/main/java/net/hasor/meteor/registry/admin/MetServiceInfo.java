/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin;
import net.hasor.meteor.domain.MetServiceType;

/** 服务基本声明及当前范围内的成员数量、角色和协议支持。 */
public class MetServiceInfo {
    private String         serviceId     = null;
    private String         group         = null;
    private String         name          = null;
    private String         version       = null;
    private String         type          = null;
    private MetServiceType role          = null;
    private String[]       protocols     = new String[0];
    private int            providerCount = 0;
    private int            consumerCount = 0;

    public String getServiceId() {
        return this.serviceId;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

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

    public MetServiceType getRole() {
        return this.role;
    }

    public void setRole(MetServiceType role) {
        this.role = role;
    }

    public String[] getProtocols() {
        return this.protocols;
    }

    public void setProtocols(String[] protocols) {
        this.protocols = protocols;
    }

    public int getProviderCount() {
        return this.providerCount;
    }

    public void setProviderCount(int providerCount) {
        this.providerCount = providerCount;
    }

    public int getConsumerCount() {
        return this.consumerCount;
    }

    public void setConsumerCount(int consumerCount) {
        this.consumerCount = consumerCount;
    }
}

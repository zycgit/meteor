/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.shared.storage.domain;
import net.hasor.meteor.domain.MetServiceType;

/** 实例下独立登记的服务正文；包含声明、身份与实际挂载，随会话退出。 */
public class EntServiceRuntimeData extends EntServiceCatalogData {
    private MetServiceType         role;
    private boolean                enabled       = true;
    private RefProtocolMountData[] endpoints     = new RefProtocolMountData[0];
    private String[]               addresses     = new String[0];
    private String[]               usedProtocols = new String[0];

    public MetServiceType getRole() {
        return this.role;
    }

    public void setRole(MetServiceType role) {
        this.role = role;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public RefProtocolMountData[] getEndpoints() {
        return this.endpoints;
    }

    public void setEndpoints(RefProtocolMountData[] endpoints) {
        this.endpoints = endpoints;
    }

    public String[] getAddresses() {
        return this.addresses;
    }

    public void setAddresses(String[] addresses) {
        this.addresses = addresses;
    }

    public String[] getUsedProtocols() {
        return this.usedProtocols;
    }

    public void setUsedProtocols(String[] usedProtocols) {
        this.usedProtocols = usedProtocols;
    }
}

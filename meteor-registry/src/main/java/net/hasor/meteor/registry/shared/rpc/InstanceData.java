/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared.rpc;
import java.util.LinkedHashMap;
import java.util.Map;

public final class InstanceData {
    private String              instanceId        = null;
    private String              applicationName   = null;
    private boolean             block             = false;
    private String              unitName          = null;
    private Map<String, String> listenAddresses   = new LinkedHashMap<>();
    private Map<String, String> managementAddress = new LinkedHashMap<>(); // 协议名称 -> 管理 RPC 地址

    public String getInstanceId() {
        return this.instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public String getApplicationName() {
        return this.applicationName;
    }

    public void setApplicationName(String applicationName) {
        this.applicationName = applicationName;
    }

    public boolean isBlock() {
        return this.block;
    }

    public void setBlock(boolean block) {
        this.block = block;
    }

    public String getUnitName() {
        return this.unitName;
    }

    public void setUnitName(String unitName) {
        this.unitName = unitName;
    }

    public Map<String, String> getListenAddresses() {
        return this.listenAddresses;
    }

    public void setListenAddresses(Map<String, String> listenAddresses) {
        this.listenAddresses = listenAddresses;
    }

    public Map<String, String> getManagementAddress() {
        return this.managementAddress;
    }

    public void setManagementAddress(Map<String, String> managementAddress) {
        this.managementAddress = managementAddress;
    }
}

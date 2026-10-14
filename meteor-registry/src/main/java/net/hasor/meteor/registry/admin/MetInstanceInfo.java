/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin;
import java.util.LinkedHashMap;
import java.util.Map;
import net.hasor.meteor.domain.MetServiceType;

public final class MetInstanceInfo {
    private String              ipAddress         = null; // 应用使用的本地 IP
    private Long                processId         = null; // 应用进程 ID
    private String              instanceId        = null;
    private String              applicationName   = null;
    private boolean             online            = false; // 注册会话是否仍然有效
    private boolean             block             = false;
    private String              unitName          = null;
    private Map<String, String> listenAddresses   = new LinkedHashMap<>();
    private Map<String, String> managementAddress = new LinkedHashMap<>(); // 协议名称 -> 管理 RPC 地址
    private MetServiceType[]    roles             = new MetServiceType[0];

    public MetServiceType[] getRoles() {
        return this.roles;
    }

    public void setRoles(MetServiceType[] roles) {
        this.roles = roles;
    }

    public String getInstanceId() {
        return this.instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public String getApplicationName() {
        return this.applicationName;
    }

    public String getIpAddress() {
        return this.ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public Long getProcessId() {
        return this.processId;
    }

    public void setProcessId(Long processId) {
        this.processId = processId;
    }

    public void setApplicationName(String applicationName) {
        this.applicationName = applicationName;
    }

    public boolean isOnline() {
        return this.online;
    }

    public void setOnline(boolean online) {
        this.online = online;
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

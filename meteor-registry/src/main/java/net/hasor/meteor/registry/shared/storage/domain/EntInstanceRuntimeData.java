/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared.storage.domain;
import java.util.LinkedHashMap;
import java.util.Map;

/** INSTANCE/RUNTIME 存储正文；仅保存已上报数据，实例 ID 由 EntityRef 提供。 */
public final class EntInstanceRuntimeData extends EntData {
    private String              applicationName     = null; // 应用名称
    private boolean             block               = false; // 已上报的业务阻断状态
    private String              unitName            = null; // 所属单元
    private Map<String, String> listenAddresses     = new LinkedHashMap<>(); // 协议名称 -> 监听地址
    private Map<String, String> managementAddresses = new LinkedHashMap<>(); // 协议名称 -> 管理 RPC 地址
    private Map<String, Object> settings            = new LinkedHashMap<>(); // 已上报的应用配置

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

    public Map<String, String> getManagementAddresses() {
        return this.managementAddresses;
    }

    public void setManagementAddresses(Map<String, String> managementAddresses) {
        this.managementAddresses = managementAddresses;
    }

    public Map<String, Object> getSettings() {
        return this.settings;
    }

    public void setSettings(Map<String, Object> settings) {
        this.settings = settings;
    }
}

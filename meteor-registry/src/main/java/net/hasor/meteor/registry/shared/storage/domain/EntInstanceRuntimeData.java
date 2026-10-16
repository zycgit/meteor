/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.shared.storage.domain;
import java.util.LinkedHashMap;
import java.util.Map;
import net.hasor.meteor.domain.MetServiceType;

/** 当前登记会话中的实例运行数据；不能作为持久在线标记。 */
public class EntInstanceRuntimeData extends EntInstanceCatalogData {
    private MetServiceType[]    roles               = new MetServiceType[0];
    private boolean             block               = false;
    private Map<String, String> listenAddresses     = new LinkedHashMap<>(); // 连接器名称#地址 scheme -> 实际监听地址
    private Map<String, String> managementAddresses = new LinkedHashMap<>(); // 连接器名称#地址 scheme -> 管理 RPC 地址

    public MetServiceType[] getRoles() {
        return this.roles;
    }

    public void setRoles(MetServiceType[] roles) {
        this.roles = roles;
    }

    public boolean isBlock() {
        return this.block;
    }

    public void setBlock(boolean block) {
        this.block = block;
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
}

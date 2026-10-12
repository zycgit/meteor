/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin.support;
import net.hasor.meteor.registry.admin.MetInstanceAdmin;
import net.hasor.meteor.registry.admin.MetInstanceInfo;
import net.hasor.meteor.registry.admin.MetRuleType;
import net.hasor.meteor.registry.admin.MetServiceInfo;
import net.hasor.meteor.registry.shared.rpc.ManagementRPC;
import net.hasor.meteor.registry.shared.rpc.RuleTypeData;
import net.hasor.meteor.registry.shared.utils.RegistryJson;

/** 通过管理 RPC 操作目标实例，不访问注册中心存储。 */
final class TargetInstanceAdmin implements MetInstanceAdmin {
    private final ManagementRPC rpc;

    public TargetInstanceAdmin(ManagementRPC rpc) {
        this.rpc = rpc;
    }

    // 实例和本地服务

    @Override
    public MetInstanceInfo instance() {
        return RegistryJson.copy(this.rpc.instance(), MetInstanceInfo.class);
    }

    @Override
    public MetServiceInfo[] getServiceList() {
        return RegistryJson.copy(this.rpc.services(), MetServiceInfo[].class);
    }

    @Override
    public MetServiceInfo getServiceById(String serviceId) {
        return RegistryJson.copy(this.rpc.service(serviceId).getService(), MetServiceInfo.class);
    }

    // 网络业务调用阻断

    @Override
    public boolean isBlock() {
        return this.rpc.isBlock();
    }

    @Override
    public void block() {
        this.rpc.setBlock(true);
    }

    @Override
    public void unblock() {
        this.rpc.setBlock(false);
    }

    // 实例规则

    @Override
    public String fetchRule(MetRuleType type) {
        return this.rpc.fetchInstanceRule(ruleType(type));
    }

    @Override
    public void updateRule(MetRuleType type, String rule) {
        this.rpc.updateInstanceRule(ruleType(type), rule);
    }

    @Override
    public void resetRule(MetRuleType type) {
        this.updateRule(type, null);
    }

    @Override
    public boolean isRuleEnabled(MetRuleType type) {
        throw new UnsupportedOperationException("Rule enable/disable requires registry storage");
    }

    @Override
    public void enableRule(MetRuleType type) {
        throw new UnsupportedOperationException("Rule enable/disable requires registry storage");
    }

    @Override
    public void disableRule(MetRuleType type) {
        throw new UnsupportedOperationException("Rule enable/disable requires registry storage");
    }

    private static RuleTypeData ruleType(MetRuleType type) {
        return type == null ? null : RuleTypeData.valueOf(type.name());
    }
}

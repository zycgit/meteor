/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin.support;
import java.util.Arrays;
import java.util.function.Supplier;
import net.hasor.meteor.registry.RegistryTokenProvider;
import net.hasor.meteor.registry.admin.*;
import net.hasor.meteor.registry.shared.rpc.ManagementRPC;
import net.hasor.meteor.registry.shared.rpc.RuleTypeData;
import net.hasor.meteor.registry.shared.utils.RegistryJson;

/** 通过管理 RPC 操作目标实例，不访问注册中心存储。 */
final class TargetInstanceAdmin implements MetInstanceAdmin {
    private final Supplier<ManagementRPC> rpc;
    private final RegistryTokenProvider   tokens;

    public TargetInstanceAdmin(Supplier<ManagementRPC> rpc, RegistryTokenProvider tokens) {
        this.rpc = rpc;
        this.tokens = tokens;
    }

    // 实例和本地服务

    @Override
    public MetInstanceInfo instance() {
        var snapshot = this.rpc.get().instance(this.tokens.getToken());
        MetInstanceInfo instance = RegistryJson.copy(snapshot, MetInstanceInfo.class);
        instance.setOnline(true);
        return instance;
    }

    @Override
    public MetPage<MetServiceInfo> getServiceList(MetDirectoryQuery query) {
        var snapshot = this.rpc.get().services(this.tokens.getToken());
        MetServiceInfo[] services = RegistryJson.copy(snapshot, MetServiceInfo[].class);
        return DirectoryMapping.local(Arrays.asList(services), query, MetServiceInfo::getServiceId);
    }

    @Override
    public MetServiceInfo getServiceById(String serviceId) {
        var snapshot = this.rpc.get().service(this.tokens.getToken(), serviceId);
        return RegistryJson.copy(snapshot.getService(), MetServiceInfo.class);
    }

    // 网络业务调用阻断

    @Override
    public boolean isBlock() {
        return this.rpc.get().isBlock(this.tokens.getToken());
    }

    @Override
    public void block() {
        this.rpc.get().setBlock(this.tokens.getToken(), true);
    }

    @Override
    public void unblock() {
        this.rpc.get().setBlock(this.tokens.getToken(), false);
    }

    // 实例规则

    @Override
    public String fetchRule(MetRuleType type) {
        return this.rpc.get().fetchInstanceRule(this.tokens.getToken(), ruleType(type));
    }

    @Override
    public void updateRule(MetRuleType type, String rule) {
        this.rpc.get().updateInstanceRule(this.tokens.getToken(), ruleType(type), rule);
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

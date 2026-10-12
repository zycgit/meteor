/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin.support;
import java.util.Collections;
import java.util.Map;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.admin.*;
import net.hasor.meteor.registry.shared.rpc.ManagementRPC;
import net.hasor.meteor.registry.shared.rpc.RuleTypeData;
import net.hasor.meteor.registry.shared.utils.RegistryJson;

/** 通过管理 RPC 操作目标实例中的一个服务，不查询注册中心成员。 */
final class TargetServiceAdmin implements MetServiceAdmin {
    private final ManagementRPC rpc;
    private final String        serviceId;

    public TargetServiceAdmin(ManagementRPC rpc, String serviceId) {
        this.rpc = rpc;
        this.serviceId = serviceId;
    }

    // 目标实例的服务声明和成员

    @Override
    public MetServiceInfo info() {
        return RegistryJson.copy(this.rpc.service(this.serviceId).getService(), MetServiceInfo.class);
    }

    @Override
    public MetServiceMember[] providers(MetEndpointFilter filter) {
        return this.members(MetServiceType.Provider, filter);
    }

    @Override
    public MetServiceMember[] consumers(MetEndpointFilter filter) {
        return this.members(MetServiceType.Consumer, filter);
    }

    private MetServiceMember[] members(MetServiceType role, MetEndpointFilter filter) {
        MetServiceMember member = RegistryJson.copy(this.rpc.service(this.serviceId), MetServiceMember.class);
        boolean matchedRole = role == member.getService().getRole();
        if (!matchedRole || !MemberMatcherUtils.matches(member, filter)) {
            return new MetServiceMember[0];
        }
        return new MetServiceMember[] { member };
    }

    // 规则

    @Override
    public String fetchRule(MetRuleType type) {
        return this.rpc.fetchServiceRule(this.serviceId, ruleType(type));
    }

    @Override
    public void resetRule(MetRuleType type) {
        this.updateRule(type, null);
    }

    @Override
    public MetValidationResult validateRule(MetRuleType type, String rule) {
        var validation = this.rpc.validateServiceRule(this.serviceId, ruleType(type), rule);
        return RegistryJson.copy(validation, MetValidationResult.class);
    }

    @Override
    public void updateRule(MetRuleType type, String rule) {
        this.rpc.updateServiceRule(this.serviceId, ruleType(type), rule);
    }

    @Override
    public boolean isRule(MetRuleType type) {
        return StringUtils.isNotBlank(this.fetchRule(type));
    }

    @Override
    public void enableRule(MetRuleType type) {
        throw new UnsupportedOperationException("Rule enable/disable requires registry storage");
    }

    @Override
    public void disableRule(MetRuleType type) {
        throw new UnsupportedOperationException("Rule enable/disable requires registry storage");
    }

    // 网络业务调用与元数据

    @Override
    public boolean isEnable() {
        return this.rpc.isServiceEnabled(this.serviceId);
    }

    @Override
    public void enable() {
        this.rpc.setServiceEnabled(this.serviceId, true);
    }

    @Override
    public void disable() {
        this.rpc.setServiceEnabled(this.serviceId, false);
    }

    @Override
    public Map<String, Object> fetchMeta() {
        return this.rpc.fetchServiceMeta(this.serviceId);
    }

    @Override
    public void putMeta(String key, Object value) {
        this.putMeta(Collections.singletonMap(key, value));
    }

    @Override
    public void putMeta(Map<String, Object> values) {
        this.rpc.putServiceMeta(this.serviceId, values);
    }

    @Override
    public void removeMeta(String... keys) {
        this.rpc.removeServiceMeta(this.serviceId, keys);
    }

    private static RuleTypeData ruleType(MetRuleType type) {
        return type == null ? null : RuleTypeData.valueOf(type.name());
    }
}

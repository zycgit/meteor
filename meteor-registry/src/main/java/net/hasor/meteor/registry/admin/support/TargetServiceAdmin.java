/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin.support;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.RegistryTokenProvider;
import net.hasor.meteor.registry.admin.*;
import net.hasor.meteor.registry.admin.description.MetServiceDescription;
import net.hasor.meteor.registry.shared.rpc.ManagementRPC;
import net.hasor.meteor.registry.shared.rpc.RuleTypeData;
import net.hasor.meteor.registry.shared.rpc.ServiceMemberData;
import net.hasor.meteor.registry.shared.utils.RegistryJson;

/** 通过管理 RPC 操作目标实例中的一个服务，不查询注册中心成员。 */
final class TargetServiceAdmin implements MetServiceAdmin {
    private final Supplier<ManagementRPC> rpc;
    private final RegistryTokenProvider   tokens;
    private final String                  serviceId;

    public TargetServiceAdmin(Supplier<ManagementRPC> rpc, RegistryTokenProvider tokens, String serviceId) {
        this.rpc = rpc;
        this.tokens = tokens;
        this.serviceId = serviceId;
    }

    // 目标实例的服务声明和成员

    @Override
    public String[] units() {
        String unit = this.rpc.get().service(this.tokens.getToken(), this.serviceId).getInstance().getUnitName();
        if (unit == null) {
            return new String[0];
        } else {
            return new String[] { unit };
        }
    }

    @Override
    public MetPage<MetServiceMember> providers(MetEndpointFilter filter, MetDirectoryQuery query) {
        List<MetServiceMember> members = this.members(MetServiceType.Provider, filter);
        return DirectoryMapping.local(members, query, DirectoryMapping::memberIdentity);
    }

    @Override
    public MetPage<MetServiceMember> consumers(MetEndpointFilter filter, MetDirectoryQuery query) {
        List<MetServiceMember> members = this.members(MetServiceType.Consumer, filter);
        return DirectoryMapping.local(members, query, DirectoryMapping::memberIdentity);
    }

    @Override
    public MetServiceInfo info() {
        ServiceMemberData service = this.rpc.get().service(this.tokens.getToken(), this.serviceId);
        return RegistryJson.copy(service.getService(), MetServiceInfo.class);
    }

    @Override
    public Map<String, MetServiceDescription> descriptions() {
        ServiceMemberData service = this.rpc.get().service(this.tokens.getToken(), this.serviceId);
        ObjectNode description = service.getDescription();
        if (description == null) {
            return Collections.emptyMap();
        }
        return Collections.singletonMap(service.getInstance().getInstanceId(), DescriptionMapping.service(description));
    }

    private List<MetServiceMember> members(MetServiceType role, MetEndpointFilter filter) {
        ServiceMemberData service = this.rpc.get().service(this.tokens.getToken(), this.serviceId);
        MetServiceMember member = RegistryJson.copy(service, MetServiceMember.class);
        member.getInstance().setOnline(true);
        boolean matchedRole = role == member.getService().getRole();
        if (!matchedRole || !MemberMatcherUtils.matches(member, filter)) {
            return Collections.emptyList();
        } else {
            return Collections.singletonList(member);
        }
    }

    // 规则

    @Override
    public String fetchRule(MetRuleType type) {
        return this.rpc.get().fetchServiceRule(this.tokens.getToken(), this.serviceId, ruleType(type));
    }

    @Override
    public void resetRule(MetRuleType type) {
        this.updateRule(type, null);
    }

    @Override
    public MetValidationResult validateRule(MetRuleType type, String rule) {
        var validation = this.rpc.get().validateServiceRule(this.tokens.getToken(), this.serviceId, ruleType(type), rule);
        return RegistryJson.copy(validation, MetValidationResult.class);
    }

    @Override
    public void updateRule(MetRuleType type, String rule) {
        this.rpc.get().updateServiceRule(this.tokens.getToken(), this.serviceId, ruleType(type), rule);
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
        return this.rpc.get().isServiceEnabled(this.tokens.getToken(), this.serviceId);
    }

    @Override
    public void enable() {
        this.rpc.get().setServiceEnabled(this.tokens.getToken(), this.serviceId, true);
    }

    @Override
    public void disable() {
        this.rpc.get().setServiceEnabled(this.tokens.getToken(), this.serviceId, false);
    }

    @Override
    public Map<String, Object> fetchMeta() {
        return this.rpc.get().fetchServiceMeta(this.tokens.getToken(), this.serviceId);
    }

    @Override
    public void putMeta(String key, Object value) {
        this.putMeta(Collections.singletonMap(key, value));
    }

    @Override
    public void putMeta(Map<String, Object> values) {
        this.rpc.get().putServiceMeta(this.tokens.getToken(), this.serviceId, values);
    }

    @Override
    public void removeMeta(String... keys) {
        this.rpc.get().removeServiceMeta(this.tokens.getToken(), this.serviceId, keys);
    }

    private static RuleTypeData ruleType(MetRuleType type) {
        return type == null ? null : RuleTypeData.valueOf(type.name());
    }
}

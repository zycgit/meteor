/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin.support;
import java.util.*;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.registry.admin.*;
import net.hasor.meteor.registry.shared.rpc.*;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import net.hasor.meteor.registry.shared.utils.RegistryGovernanceUtils;
import net.hasor.meteor.registry.shared.utils.RegistryJson;
import static net.hasor.meteor.registry.admin.support.GovernanceStore.call;

/** 查询当前服务的声明和成员，管理服务级或指定实例上的服务治理配置。 */
final class DefaultServiceAdmin implements MetServiceAdmin {
    private final GovernanceStore governance;
    private final String          serviceId;
    private final String          instanceId;

    public DefaultServiceAdmin(GovernanceStore governance, String serviceId, String instanceId) {
        RegistryGovernanceUtils.service(serviceId);
        this.governance = governance;
        this.serviceId = serviceId;
        this.instanceId = instanceId;
    }

    // 服务信息与发现关系

    @Override
    public MetServiceInfo info() {
        if (this.instanceId != null) {
            return RegistryJson.copy(this.detailData(), MetServiceInfo.class);
        }

        return call(() -> {
            EntityRef ref = RegistryGovernanceUtils.service(this.serviceId);
            EntityRecord record = this.governance.storage().getEntity(ref);
            ServiceMemberData[] members = this.members(null, null);
            if (record == null && members.length == 0) {
                throw new IllegalArgumentException("Unknown service: " + this.serviceId);
            }

            // 目录提供持久身份，数量来自当前服务的发布和订阅关系。
            MetServiceInfo result = new MetServiceInfo();
            if (record != null && record.getData() != null) {
                result = RegistryJson.copy(record.getData(), MetServiceInfo.class);
            }
            result.setServiceId(this.serviceId);
            int providers = 0;
            for (ServiceMemberData member : members) {
                ServiceData service = member.getService();
                result.setGroup(service.getGroup());
                result.setName(service.getName());
                result.setVersion(service.getVersion());
                if (StringUtils.equals("Provider", service.getRole())) {
                    providers++;
                }
            }

            result.setProviderCount(providers);
            result.setConsumerCount(members.length - providers);
            return result;
        });
    }

    @Override
    public MetServiceDetail detail() {
        return RegistryJson.copy(this.detailData(), MetServiceDetail.class);
    }

    private ServiceData detailData() {
        ServiceMemberData[] members = this.members(null, null);
        Comparator<ServiceMemberData> order = Comparator.comparing(member -> {
            return !StringUtils.equals("Provider", member.getService().getRole());
        });
        order = order.thenComparing(member -> member.getInstance().getInstanceId());

        // 优先选择提供者声明；同角色按实例 ID 确定顺序。
        ServiceMemberData selected = null;
        int providers = 0;
        for (ServiceMemberData member : members) {
            if (selected == null || order.compare(member, selected) < 0) {
                selected = member;
            }
            if (StringUtils.equals("Provider", member.getService().getRole())) {
                providers++;
            }
        }

        if (selected == null) {
            throw new IllegalArgumentException("Unknown live service: " + this.serviceId);
        }
        ServiceData detail = selected.getService();
        detail.setProviderCount(providers);
        detail.setConsumerCount(members.length - providers);
        return detail;
    }

    @Override
    public MetServiceMember[] providers(MetEndpointFilter filter) {
        return RegistryJson.copy(this.members(RelationType.PROVIDES, filter), MetServiceMember[].class);
    }

    @Override
    public MetServiceMember[] consumers(MetEndpointFilter filter) {
        return RegistryJson.copy(this.members(RelationType.CONSUMES, filter), MetServiceMember[].class);
    }

    private ServiceMemberData[] members(RelationType role, MetEndpointFilter filter) {
        return call(() -> {
            RelationQuery query = new RelationQuery();
            query.setSource(RegistryGovernanceUtils.service(this.serviceId));
            query.setType(role);
            if (this.instanceId != null) {
                query.setTarget(RegistryGovernanceUtils.instance(this.instanceId));
            }

            StorageAccess storage = this.governance.storage();
            List<ServiceMemberData> result = new ArrayList<>();
            for (RelationRecord relation : RegistryGovernanceUtils.relations(storage, query)) {
                EntityRef instanceRef = RegistryGovernanceUtils.runtime(relation.getTarget());
                EntityRecord instance = storage.getEntity(instanceRef);
                if (instance == null) {
                    continue;
                }
                EntityRef local = RegistryGovernanceUtils.localService(instance.getId(), this.serviceId);
                EntityRecord child = storage.getEntity(RegistryGovernanceUtils.runtime(local));
                if (child == null) {
                    continue;
                }

                EntInstanceServiceRuntimeData runtime = (EntInstanceServiceRuntimeData) child.getData();
                EntInstanceRuntimeData snapshot = (EntInstanceRuntimeData) instance.getData();
                ServiceMemberData member = new ServiceMemberData();
                ServiceData declaration = RegistryJson.copy(runtime.getService(), ServiceData.class);
                declaration.setServiceId(child.getId());
                boolean provider = relation.getType() == RelationType.PROVIDES;
                declaration.setProviderCount(provider ? 1 : 0);
                declaration.setConsumerCount(provider ? 0 : 1);
                member.setService(declaration);
                member.setEnabled(runtime.isEnabled());
                InstanceData instanceData = RegistryJson.copy(snapshot, InstanceData.class);
                instanceData.setInstanceId(instance.getId());
                instanceData.setManagementAddress(snapshot.getManagementAddresses());
                member.setInstance(instanceData);
                RefServiceRelationData discovery = (RefServiceRelationData) relation.getData();
                member.setEndpoints(RegistryJson.copy(discovery.getEndpoints(), ServiceEndpointData[].class));
                member.setAddresses(discovery.getAddresses());
                if (MemberMatcherUtils.matches(member, filter)) {
                    result.add(member);
                }
            }
            return result.toArray(ServiceMemberData[]::new);
        });
    }

    // 当前范围的规则、服务开关和元信息

    private EntityRef configurationOwner() {
        if (this.instanceId == null) {
            return RegistryGovernanceUtils.service(this.serviceId);
        }

        return call(() -> {
            EntityRef local = RegistryGovernanceUtils.localService(this.instanceId, this.serviceId);
            StorageAccess storage = this.governance.storage();
            EntityRecord configured = storage.getEntity(local);
            if (configured == null && storage.getEntity(RegistryGovernanceUtils.runtime(local)) == null) {
                throw new IllegalArgumentException("Service is not declared by instance: " + this.instanceId);
            }
            return local;
        });
    }

    @Override
    public String fetchRule(MetRuleType type) {
        return this.governance.fetchRule(this.configurationOwner(), type);
    }

    @Override
    public void resetRule(MetRuleType type) {
        this.updateRule(type, null);
    }

    @Override
    public MetValidationResult validateRule(MetRuleType type, String rule) {
        this.configurationOwner();
        ValidationData validation = this.governance.validateRule(type, rule);
        return RegistryJson.copy(validation, MetValidationResult.class);
    }

    @Override
    public void updateRule(MetRuleType type, String rule) {
        this.governance.updateRule(this.configurationOwner(), type, rule);
    }

    @Override
    public boolean isRule(MetRuleType type) {
        return this.governance.isRuleEnabled(this.configurationOwner(), type);
    }

    @Override
    public void enableRule(MetRuleType type) {
        this.governance.setRuleEnabled(this.configurationOwner(), type, true);
    }

    @Override
    public void disableRule(MetRuleType type) {
        this.governance.setRuleEnabled(this.configurationOwner(), type, false);
    }

    @Override
    public boolean isEnable() {
        EntityRef owner = this.configurationOwner();
        EntServiceConfigurationData service = this.state(RegistryGovernanceUtils.service(this.serviceId));
        boolean enabled = !Boolean.FALSE.equals(service.getEnabled());
        if (this.instanceId != null) {
            enabled &= !Boolean.FALSE.equals(this.state(owner).getEnabled());
        }
        return enabled;
    }

    private EntServiceConfigurationData state(EntityRef owner) {
        return call(() -> {
            EntityRecord record = this.governance.storage().getEntity(owner);
            if (record == null || record.getData() == null) {
                return new EntServiceConfigurationData();
            }
            return (EntServiceConfigurationData) record.getData();
        });
    }

    @Override
    public void enable() {
        this.setEnabled(true);
    }

    @Override
    public void disable() {
        this.setEnabled(false);
    }

    private void setEnabled(boolean enabled) {
        EntityRef owner = this.configurationOwner();
        if (this.instanceId == null) {
            this.governance.update(owner, EntServiceCatalogData.class, data -> data.setEnabled(enabled));
        } else {
            this.governance.update(owner, EntServiceConfigurationData.class, data -> data.setEnabled(enabled));
        }
    }

    @Override
    public void putMeta(String key, Object value) {
        this.putMeta(Collections.singletonMap(key, value));
    }

    @Override
    public void putMeta(Map<String, Object> values) {
        this.governance.putMeta(this.configurationOwner(), values);
    }

    @Override
    public void removeMeta(String... keys) {
        this.governance.removeMeta(this.configurationOwner(), keys);
    }
}

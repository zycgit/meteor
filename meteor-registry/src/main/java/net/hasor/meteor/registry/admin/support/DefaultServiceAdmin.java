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
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.admin.*;
import net.hasor.meteor.registry.shared.rpc.ValidationData;
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
        return call(() -> {
            EntityRecord catalog = this.governance.storage().getEntity(RegistryGovernanceUtils.service(this.serviceId));
            if (catalog == null) {
                throw new IllegalArgumentException("Unknown service: " + this.serviceId);
            }

            MetServiceMember[] members = this.members(null, null);
            if (this.instanceId != null && members.length == 0) {
                throw new IllegalArgumentException("Service is not active on instance: " + this.serviceId);
            }

            MetServiceInfo info = new MetServiceInfo();
            if (catalog.getData() != null) {
                info = RegistryJson.copy(catalog.getData(), MetServiceInfo.class);
            }
            info.setServiceId(this.serviceId);

            int providers = 0;
            Set<String> protocols = new TreeSet<>();
            for (MetServiceMember member : members) {
                providers += member.getService().getProviderCount();
                for (MetServiceEndpoint endpoint : member.getEndpoints()) {
                    protocols.add(endpoint.getProtocolName());
                }
            }

            info.setProviderCount(providers);
            info.setConsumerCount(members.length - providers);
            info.setProtocols(protocols.toArray(String[]::new));
            if (providers > 0) {
                info.setRole(MetServiceType.Provider);
            } else if (members.length > 0) {
                info.setRole(MetServiceType.Consumer);
            }

            return info;
        });
    }

    @Override
    public MetServiceMember[] providers(MetEndpointFilter filter) {
        return this.members(RelationType.PROVIDES, filter);
    }

    @Override
    public MetServiceMember[] consumers(MetEndpointFilter filter) {
        return this.members(RelationType.CONSUMES, filter);
    }

    private MetServiceMember[] members(RelationType role, MetEndpointFilter filter) {
        return call(() -> {
            RelationQuery query = new RelationQuery();
            query.setSource(RegistryGovernanceUtils.service(this.serviceId));
            query.setType(role);
            if (this.instanceId != null) {
                query.setTarget(RegistryGovernanceUtils.instance(this.instanceId));
            } else if (filter != null && StringUtils.isNotBlank(filter.getInstanceId())) {
                query.setTarget(RegistryGovernanceUtils.instance(filter.getInstanceId()));
            }

            StorageAccess storage = this.governance.storage();
            EntityRecord catalog = storage.getEntity(query.getSource());
            List<MetServiceMember> result = new ArrayList<>();
            for (RelationRecord relation : RegistryGovernanceUtils.relations(storage, query)) {
                EntityRecord instance = storage.getEntity(RegistryGovernanceUtils.runtime(relation.getTarget()));
                if (instance == null) {
                    continue;
                }

                // 关系自身已包含端点和启用状态，不再依赖另一条实例服务记录。
                RefServiceRelationData registration = (RefServiceRelationData) relation.getData();
                EntInstanceRuntimeData snapshot = (EntInstanceRuntimeData) instance.getData();
                MetServiceMember member = new MetServiceMember();
                MetInstanceInfo instanceInfo = RegistryJson.copy(snapshot, MetInstanceInfo.class);
                instanceInfo.setInstanceId(instance.getId());
                instanceInfo.setManagementAddress(snapshot.getManagementAddresses());
                member.setInstance(instanceInfo);
                member.setEnabled(registration.isEnabled());
                member.setEndpoints(RegistryJson.copy(registration.getEndpoints(), MetServiceEndpoint[].class));

                MetServiceInfo declaration = new MetServiceInfo();
                if (catalog != null && catalog.getData() != null) {
                    declaration = RegistryJson.copy(catalog.getData(), MetServiceInfo.class);
                }
                declaration.setServiceId(this.serviceId);
                boolean provider = relation.getType() == RelationType.PROVIDES;
                declaration.setRole(provider ? MetServiceType.Provider : MetServiceType.Consumer);
                declaration.setProviderCount(provider ? 1 : 0);
                declaration.setConsumerCount(provider ? 0 : 1);
                Set<String> protocols = new TreeSet<>();
                for (MetServiceEndpoint endpoint : member.getEndpoints()) {
                    protocols.add(endpoint.getProtocolName());
                }
                declaration.setProtocols(protocols.toArray(String[]::new));
                member.setService(declaration);
                if (MemberMatcherUtils.matches(member, filter)) {
                    result.add(member);
                }
            }
            return result.toArray(MetServiceMember[]::new);
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
            if (configured == null) {
                RelationQuery query = new RelationQuery();
                query.setSource(RegistryGovernanceUtils.service(this.serviceId));
                query.setTarget(RegistryGovernanceUtils.instance(this.instanceId));
                query.setLimit(1);
                if (storage.findRelations(query).records().isEmpty()) {
                    throw new IllegalArgumentException("Service is not declared by instance: " + this.instanceId);
                }
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
    public Map<String, Object> fetchMeta() {
        return this.governance.fetchMeta(this.configurationOwner());
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

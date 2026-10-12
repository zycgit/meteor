/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin.support;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.admin.MetInstanceAdmin;
import net.hasor.meteor.registry.admin.MetInstanceInfo;
import net.hasor.meteor.registry.admin.MetRuleType;
import net.hasor.meteor.registry.admin.MetServiceInfo;
import net.hasor.meteor.registry.shared.storage.domain.*;
import net.hasor.meteor.registry.shared.utils.RegistryGovernanceUtils;
import net.hasor.meteor.registry.shared.utils.RegistryJson;
import static net.hasor.meteor.registry.admin.support.GovernanceStore.call;

/** 读取实例及其当前服务，保存实例治理配置，不直接操作应用进程。 */
final class DefaultInstanceAdmin implements MetInstanceAdmin {
    private final GovernanceStore governance;
    private final EntityRef       instance;

    public DefaultInstanceAdmin(GovernanceStore governance, String instanceId) {
        this.governance = governance;
        this.instance = RegistryGovernanceUtils.instance(instanceId);
        // 获取实例管理接口时，确认目标实例当前存在。
        this.snapshot();
    }

    // 实例登记与阻断状态

    private EntInstanceRuntimeData snapshot() {
        return call(() -> {
            EntityRef ref = RegistryGovernanceUtils.runtime(this.instance);
            EntityRecord record = this.governance.storage().getEntity(ref);
            if (record == null) {
                throw new IllegalArgumentException("Unknown instance: " + this.instance.id());
            }
            return (EntInstanceRuntimeData) record.getData();
        });
    }

    @Override
    public MetInstanceInfo instance() {
        EntInstanceRuntimeData snapshot = this.snapshot();
        MetInstanceInfo result = RegistryJson.copy(snapshot, MetInstanceInfo.class);
        result.setInstanceId(this.instance.id());
        result.setManagementAddress(snapshot.getManagementAddresses());
        return result;
    }

    @Override
    public boolean isBlock() {
        EntInstanceRuntimeData reported = this.snapshot();
        return call(() -> {
            EntityRecord record = this.governance.storage().getEntity(this.instance);
            if (record != null && record.getData() != null) {
                EntInstanceConfigurationData desired = (EntInstanceConfigurationData) record.getData();
                if (desired.getBlock() != null) {
                    return desired.getBlock();
                }
            }
            return reported.isBlock();
        });
    }

    @Override
    public void block() {
        this.governance.update(this.instance, EntInstanceConfigurationData.class, data -> data.setBlock(true));
    }

    @Override
    public void unblock() {
        this.governance.update(this.instance, EntInstanceConfigurationData.class, data -> data.setBlock(false));
    }

    // 从发布、订阅关系读取当前实例的服务，不再另存服务运行快照。

    @Override
    public MetServiceInfo[] getServiceList() {
        return this.services(null);
    }

    @Override
    public MetServiceInfo getServiceById(String serviceId) {
        RegistryGovernanceUtils.service(serviceId);
        MetServiceInfo[] services = this.services(serviceId);
        if (services.length == 0) {
            throw new IllegalArgumentException("Service is not active on instance: " + serviceId);
        }
        return services[0];
    }

    private MetServiceInfo[] services(String serviceId) {
        this.snapshot();
        return call(() -> {
            RelationQuery query = new RelationQuery();
            query.setTarget(this.instance);
            if (serviceId != null) {
                query.setSource(RegistryGovernanceUtils.service(serviceId));
            }

            List<MetServiceInfo> result = new ArrayList<>();
            for (RelationRecord relation : RegistryGovernanceUtils.relations(this.governance.storage(), query)) {
                EntityRecord catalog = this.governance.storage().getEntity(relation.getSource());
                MetServiceInfo info = new MetServiceInfo();
                if (catalog != null && catalog.getData() != null) {
                    info = RegistryJson.copy(catalog.getData(), MetServiceInfo.class);
                }

                info.setServiceId(relation.getSource().id());
                boolean provider = relation.getType() == RelationType.PROVIDES;
                info.setRole(provider ? MetServiceType.Provider : MetServiceType.Consumer);
                info.setProviderCount(provider ? 1 : 0);
                info.setConsumerCount(provider ? 0 : 1);
                RefServiceRelationData registration = (RefServiceRelationData) relation.getData();
                Set<String> protocols = new TreeSet<>();
                for (RefProtocolMountData endpoint : registration.getEndpoints()) {
                    protocols.add(endpoint.getProtocolName());
                }
                info.setProtocols(protocols.toArray(String[]::new));
                result.add(info);
            }
            return result.toArray(MetServiceInfo[]::new);
        });
    }

    // 实例规则

    @Override
    public String fetchRule(MetRuleType type) {
        return this.governance.fetchRule(this.instance, type);
    }

    @Override
    public void updateRule(MetRuleType type, String rule) {
        this.governance.updateRule(this.instance, type, rule);
    }

    @Override
    public void resetRule(MetRuleType type) {
        this.updateRule(type, null);
    }

    @Override
    public boolean isRuleEnabled(MetRuleType type) {
        return this.governance.isRuleEnabled(this.instance, type);
    }

    @Override
    public void enableRule(MetRuleType type) {
        this.governance.setRuleEnabled(this.instance, type, true);
    }

    @Override
    public void disableRule(MetRuleType type) {
        this.governance.setRuleEnabled(this.instance, type, false);
    }
}

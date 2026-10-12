/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin.support;
import java.util.*;
import net.hasor.cobble.ExceptionUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.admin.*;
import net.hasor.meteor.registry.shared.rpc.ValidationData;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import net.hasor.meteor.registry.shared.utils.RegistryGovernanceUtils;
import net.hasor.meteor.registry.shared.utils.RegistryJson;
import static net.hasor.meteor.registry.admin.support.GovernanceStore.call;

/** 查询集群服务与实例目录，管理全局治理配置和注册中心信息。 */
final class DefaultClusterAdmin implements MetClusterAdmin {
    private final GovernanceStore     governance;
    private final String              provider;
    private final Map<String, String> scope;

    public DefaultClusterAdmin(GovernanceStore governance, String provider, Map<String, String> scope) {
        this.governance = governance;
        this.provider = provider;
        this.scope = scope;
    }

    // 注册中心信息与连通状态

    @Override
    public RegistryInfo info() {
        this.governance.storage();
        RegistryInfo result = new RegistryInfo();
        result.setProvider(this.provider);
        result.setScope(new LinkedHashMap<>(this.scope));
        return result;
    }

    @Override
    public RegistryStatus status() {
        RegistryStatus result = new RegistryStatus();
        result.setCheckedAt(System.currentTimeMillis());
        try {
            EntityQuery query = new EntityQuery();
            query.setType(EntityType.INSTANCE);
            query.setLimit(1);
            this.governance.storage().findEntities(query);
            result.setReachable(true);
        } catch (Exception failure) {
            result.setError(ExceptionUtils.getStackTrace(failure));
        }
        return result;
    }

    // 持久服务目录和当前实例目录

    @Override
    public MetServiceInfo[] services(String filter) {
        return call(() -> {
            StorageAccess storage = this.governance.storage();
            Map<String, MetServiceInfo> services = new TreeMap<>();
            EntityQuery query = new EntityQuery();
            query.setType(EntityType.SERVICE);

            // 先读取持久目录，全部实例离线后服务仍然可见。
            for (EntityRecord record : RegistryGovernanceUtils.entities(storage, query)) {
                if (StringUtils.isBlank(filter) || record.getId().contains(filter)) {
                    MetServiceInfo info = new MetServiceInfo();
                    if (record.getData() != null) {
                        info = RegistryJson.copy(record.getData(), MetServiceInfo.class);
                    }
                    info.setServiceId(record.getId());
                    services.put(record.getId(), info);
                }
            }

            // 读取在线实例，按两类关系统计，不扫描实例下的服务记录。
            EntityQuery instances = new EntityQuery();
            instances.setType(EntityType.INSTANCE);
            instances.setSection(EntitySection.RUNTIME);
            Set<String> online = new HashSet<>();
            for (EntityRecord instance : RegistryGovernanceUtils.entities(storage, instances)) {
                online.add(instance.getId());
            }

            Map<String, Set<String>> protocols = new HashMap<>();
            for (RelationType role : RelationType.values()) {
                RelationQuery relations = new RelationQuery();
                relations.setType(role);
                for (RelationRecord relation : RegistryGovernanceUtils.relations(storage, relations)) {
                    if (!online.contains(relation.getTarget().id())) {
                        continue;
                    }
                    MetServiceInfo info = services.get(relation.getSource().id());
                    if (info == null) {
                        continue;
                    }

                    if (role == RelationType.PROVIDES) {
                        info.setProviderCount(info.getProviderCount() + 1);
                        info.setRole(MetServiceType.Provider);
                    } else {
                        info.setConsumerCount(info.getConsumerCount() + 1);
                        if (info.getRole() == null) {
                            info.setRole(MetServiceType.Consumer);
                        }
                    }

                    RefServiceRelationData registration = (RefServiceRelationData) relation.getData();
                    Set<String> supported = protocols.computeIfAbsent(info.getServiceId(), id -> new TreeSet<>());
                    for (RefProtocolMountData endpoint : registration.getEndpoints()) {
                        supported.add(endpoint.getProtocolName());
                    }
                }
            }

            for (Map.Entry<String, Set<String>> entry : protocols.entrySet()) {
                services.get(entry.getKey()).setProtocols(entry.getValue().toArray(String[]::new));
            }
            return services.values().toArray(MetServiceInfo[]::new);
        });
    }

    @Override
    public MetInstanceInfo[] instances(String applicationName) {
        return call(() -> {
            EntityQuery query = new EntityQuery();
            query.setType(EntityType.INSTANCE);
            query.setSection(EntitySection.RUNTIME);
            List<MetInstanceInfo> result = new ArrayList<>();
            boolean allApplications = StringUtils.isBlank(applicationName);
            for (EntityRecord record : RegistryGovernanceUtils.entities(this.governance.storage(), query)) {
                EntInstanceRuntimeData instance = (EntInstanceRuntimeData) record.getData();
                if (allApplications || StringUtils.equals(applicationName, instance.getApplicationName())) {
                    MetInstanceInfo info = RegistryJson.copy(instance, MetInstanceInfo.class);
                    info.setInstanceId(record.getId());
                    info.setManagementAddress(instance.getManagementAddresses());
                    result.add(info);
                }
            }

            result.sort(Comparator.comparing(MetInstanceInfo::getInstanceId));
            return result.toArray(MetInstanceInfo[]::new);
        });
    }

    // 全局规则和元信息

    @Override
    public String fetchRule(MetRuleType type) {
        return this.governance.fetchRule(RegistryGovernanceUtils.GLOBAL, type);
    }

    @Override
    public void resetRule(MetRuleType type) {
        this.updateRule(type, null);
    }

    @Override
    public MetValidationResult validateRule(MetRuleType type, String rule) {
        ValidationData validation = this.governance.validateRule(type, rule);
        return RegistryJson.copy(validation, MetValidationResult.class);
    }

    @Override
    public void updateRule(MetRuleType type, String rule) {
        this.governance.updateRule(RegistryGovernanceUtils.GLOBAL, type, rule);
    }

    @Override
    public boolean isRuleEnabled(MetRuleType type) {
        return this.governance.isRuleEnabled(RegistryGovernanceUtils.GLOBAL, type);
    }

    @Override
    public void enableRule(MetRuleType type) {
        this.governance.setRuleEnabled(RegistryGovernanceUtils.GLOBAL, type, true);
    }

    @Override
    public void disableRule(MetRuleType type) {
        this.governance.setRuleEnabled(RegistryGovernanceUtils.GLOBAL, type, false);
    }

    @Override
    public Map<String, Object> fetchMeta() {
        return this.governance.fetchMeta(RegistryGovernanceUtils.GLOBAL);
    }

    @Override
    public void putMeta(String key, Object value) {
        this.putMeta(Collections.singletonMap(key, value));
    }

    @Override
    public void putMeta(Map<String, Object> values) {
        this.governance.putMeta(RegistryGovernanceUtils.GLOBAL, values);
    }

    @Override
    public void removeMeta(String... keys) {
        this.governance.removeMeta(RegistryGovernanceUtils.GLOBAL, keys);
    }
}

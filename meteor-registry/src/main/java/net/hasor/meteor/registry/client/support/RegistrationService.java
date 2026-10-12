/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.client.support;
import java.util.*;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.shared.rpc.ConfigurationData;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import net.hasor.meteor.registry.shared.utils.RegistryGovernanceUtils;
import net.hasor.meteor.registry.shared.utils.RegistryJson;

/** 通过存储登记本机运行信息、读取治理配置和发现地址、订阅变更；不访问 Meteor 实例。 */
final class RegistrationService {
    private final StorageAccess storage;

    public RegistrationService(StorageAccess storage) {
        this.storage = storage;
    }

    ////////////////////////////////////////////////////////////////////////////////////////
    // 登记实例、服务及发布订阅关系
    private void saveInstance(RegistrationSnapshot record) throws Exception {
        EntityRef instance = RegistryGovernanceUtils.instance(record.getInstanceId());
        this.ensureOwner(instance);
        this.saveRuntime(RegistryGovernanceUtils.runtime(instance), record.getInstance());
    }

    private void ensureOwner(EntityRef ref) throws Exception {
        if (this.storage.getEntity(ref) != null) {
            return;
        }

        try {
            this.storage.saveEntity(new EntityRecord(ref));
        } catch (ConcurrentModificationException conflict) {
            if (this.storage.getEntity(ref) == null) {
                throw conflict;
            }
        }
    }

    private void saveRuntime(EntityRef ref, EntData body) throws Exception {
        EntityRecord next = new EntityRecord(ref);
        next.setData(body);
        EntityRecord previous = this.storage.getEntity(ref);
        if (previous != null && RegistryJson.sameData(next.getData(), previous.getData())) {
            return;
        }

        this.storage.saveEntity(next);
    }

    /** 登记信息不能覆盖管理配置；目录不会因实例退出而删除。 */
    private void ensureService(ServiceRegistration registration) throws Exception {
        EntityRef ref = RegistryGovernanceUtils.service(registration.getServiceId());
        EntServiceCatalogData service = registration.getService();
        for (int attempt = 0; attempt < 8; attempt++) {
            EntityRecord previous = this.storage.getEntity(ref);
            EntServiceCatalogData data;
            if (previous == null || previous.getData() == null) {
                data = new EntServiceCatalogData();
            } else {
                data = RegistryJson.copy(previous.getData(), EntServiceCatalogData.class);
            }
            data.setGroup(service.getGroup());
            data.setName(service.getName());
            data.setVersion(service.getVersion());
            data.setType(service.getType());

            EntityRecord next = new EntityRecord(ref);
            next.setData(data);
            if (previous != null && RegistryJson.sameData(next.getData(), previous.getData())) {
                return;
            }

            next.setRevision(previous == null ? "" : previous.getRevision());
            try {
                this.storage.saveEntity(next);
                return;
            } catch (ConcurrentModificationException conflict) {
                if (attempt == 7) {
                    throw conflict;
                }
            }
        }
    }

    /** 登记服务目录和关系；指定服务时保留实例登记及其他服务。 */
    public void publish(RegistrationSnapshot record, String serviceId) throws Exception {
        EntityRef instance = RegistryGovernanceUtils.instance(record.getInstanceId());
        Set<String> current = new HashSet<>();
        for (ServiceRegistration member : record.getServices()) {
            String id = member.getServiceId();
            this.ensureService(member);
            current.add(id);

            EntityRef service = RegistryGovernanceUtils.service(id);
            boolean provider = member.getRole() == MetServiceType.Provider;
            RelationType role = provider ? RelationType.PROVIDES : RelationType.CONSUMES;
            RelationRecord relation = new RelationRecord(role, service, instance);
            relation.setLifecycle(RecordLifecycle.EPHEMERAL);
            relation.setData(member.getRelation());
            RelationRecord previous = this.storage.getRelation(role, service, instance);

            if (previous == null || !RegistryJson.sameData(previous.getData(), relation.getData())) {
                this.storage.saveRelation(relation);
            }

            RelationType other = role == RelationType.PROVIDES ? RelationType.CONSUMES : RelationType.PROVIDES;
            RelationRecord obsolete = this.storage.getRelation(other, service, instance);
            if (obsolete != null) {
                this.storage.deleteRelation(obsolete);
            }
        }

        // 实例服务列表直接来自关系；移除服务仅删除其关系，保留服务目录和治理。
        RelationQuery query = new RelationQuery();
        query.setTarget(instance);
        if (serviceId != null) {
            query.setSource(RegistryGovernanceUtils.service(serviceId));
        }
        for (RelationRecord previous : RegistryGovernanceUtils.relations(this.storage, query)) {
            if (!current.contains(previous.getSource().id())) {
                this.storage.deleteRelation(previous);
            }
        }

        if (serviceId == null) {
            this.saveInstance(record);
        }
    }

    ////////////////////////////////////////////////////////////////////////////////////////
    // 读取治理配置与服务发现结果，存储记录转换统一在此完成。
    public ConfigurationData defaults() throws Exception {
        return RegistryGovernanceUtils.read(this.storage, RegistryGovernanceUtils.GLOBAL);
    }

    public ConfigurationData serviceConfiguration(String id) throws Exception {
        return RegistryGovernanceUtils.read(this.storage, RegistryGovernanceUtils.service(id));
    }

    public ConfigurationData instanceRules(String id) throws Exception {
        return RegistryGovernanceUtils.read(this.storage, RegistryGovernanceUtils.instance(id));
    }

    public ConfigurationData serviceOverride(String serviceId, String instanceId) throws Exception {
        return RegistryGovernanceUtils.read(this.storage, RegistryGovernanceUtils.localService(instanceId, serviceId));
    }

    public EntInstanceConfigurationData instanceConfiguration(String id) throws Exception {
        EntityRecord record = this.storage.getEntity(RegistryGovernanceUtils.instance(id));
        if (record == null || record.getData() == null) {
            return new EntInstanceConfigurationData();
        } else {
            return (EntInstanceConfigurationData) record.getData();
        }
    }

    public Set<String> providers(String serviceId) throws Exception {
        RelationQuery query = new RelationQuery();
        query.setType(RelationType.PROVIDES);
        query.setSource(RegistryGovernanceUtils.service(serviceId));
        Set<String> result = new TreeSet<>();
        for (RelationRecord relation : RegistryGovernanceUtils.relations(this.storage, query)) {
            if (this.storage.getEntity(RegistryGovernanceUtils.runtime(relation.getTarget())) == null) {
                continue;
            }

            RefServiceRelationData binding = (RefServiceRelationData) relation.getData();
            Collections.addAll(result, binding.getAddresses());
        }
        return result;
    }

    ////////////////////////////////////////////////////////////////////////////////////////
    // 订阅配置及提供者变更
    public List<Cancellable> watchInstance(String instanceId, Runnable changed) {
        List<Cancellable> result = new ArrayList<>();
        try {
            this.watchConfiguration(RegistryGovernanceUtils.GLOBAL, changed, result);
            this.watchConfiguration(RegistryGovernanceUtils.instance(instanceId), changed, result);
            return result;
        } catch (RuntimeException failure) {
            result.forEach(Cancellable::cancel);
            throw failure;
        }
    }

    public List<Cancellable> watchService(String instanceId, String serviceId, Runnable changed) {
        List<Cancellable> result = new ArrayList<>();
        try {
            this.watchConfiguration(RegistryGovernanceUtils.service(serviceId), changed, result);
            this.watchConfiguration(RegistryGovernanceUtils.localService(instanceId, serviceId), changed, result);
            RelationQuery providers = new RelationQuery();
            providers.setType(RelationType.PROVIDES);
            providers.setSource(RegistryGovernanceUtils.service(serviceId));
            result.add(this.storage.watchRelations(providers, changed));
            return result;
        } catch (RuntimeException failure) {
            result.forEach(Cancellable::cancel);
            throw failure;
        }
    }

    private void watchConfiguration(EntityRef ref, Runnable changed, List<Cancellable> watches) {
        EntityQuery query = new EntityQuery();
        query.setType(ref.type());
        query.setIds(Set.of(ref.id()));
        query.setParent(ref.parent());
        watches.add(this.storage.watchEntities(query, changed));

        EntityQuery rules = new EntityQuery();
        rules.setType(EntityType.RULE);
        rules.setParent(ref);
        watches.add(this.storage.watchEntities(rules, changed));
    }
}

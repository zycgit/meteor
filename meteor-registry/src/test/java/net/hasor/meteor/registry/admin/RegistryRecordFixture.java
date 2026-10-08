/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin;
import java.util.LinkedHashMap;
import java.util.Map;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.registry.shared.rpc.InstanceData;
import net.hasor.meteor.registry.shared.rpc.ServiceMemberData;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import net.hasor.meteor.registry.shared.utils.RegistryGovernanceUtils;
import net.hasor.meteor.registry.shared.utils.RegistryJson;

/** 管理查询测试的存储夹具；按实际正文分别写入实例、实例服务和发现关系。 */
final class RegistryRecordFixture {
    private final String instanceId;
    private final EntInstanceRuntimeData instance;
    private final Map<String, EntInstanceServiceRuntimeData> services = new LinkedHashMap<>();
    private final Map<String, RefServiceRelationData> relations = new LinkedHashMap<>();

    public RegistryRecordFixture(InstanceData instance, ServiceMemberData... members) {
        this.instanceId = instance.getInstanceId();
        this.instance = RegistryJson.copy(instance, EntInstanceRuntimeData.class);
        this.instance.setManagementAddresses(instance.getManagementAddress());
        for (ServiceMemberData member : members) {
            String id = member.getService().getServiceId();
            EntInstanceServiceRuntimeData service = new EntInstanceServiceRuntimeData();
            service.setService(RegistryJson.copy(member.getService(), EntServiceDeclarationData.class));
            service.setEnabled(member.isEnabled());
            this.services.put(id, service);
            RefServiceRelationData relation = new RefServiceRelationData();
            relation.setEndpoints(RegistryJson.copy(member.getEndpoints(), RefProtocolMountData[].class));
            relation.setAddresses(member.getAddresses());
            this.relations.put(id, relation);
        }
    }

    public EntInstanceRuntimeData getInstance() {
        return this.instance;
    }

    public EntInstanceServiceRuntimeData getService(String serviceId) {
        return this.services.get(serviceId);
    }

    public void save(StorageAccess storage) throws Exception {
        // 替换当前实例的成员关系，允许同一夹具切换发布或订阅角色。
        EntityRef owner = RegistryGovernanceUtils.instance(this.instanceId);
        RelationQuery query = new RelationQuery();
        query.setTarget(owner);
        for (RelationRecord relation : RegistryGovernanceUtils.relations(storage, query)) {
            storage.deleteRelation(relation);
        }

        // 为每个服务分别写入运行记录与发现关系。
        for (Map.Entry<String, EntInstanceServiceRuntimeData> entry : this.services.entrySet()) {
            String id = entry.getKey();
            EntInstanceServiceRuntimeData data = entry.getValue();
            EntityRef local = RegistryGovernanceUtils.localService(this.instanceId, id);
            EntityRecord child = new EntityRecord(RegistryGovernanceUtils.runtime(local));
            child.setData(data);
            storage.saveEntity(child);

            boolean provider = StringUtils.equals("Provider", data.getService().getRole());
            RelationType role = provider ? RelationType.PROVIDES : RelationType.CONSUMES;
            RelationRecord relation = new RelationRecord(role, RegistryGovernanceUtils.service(id), owner);
            relation.setLifecycle(RecordLifecycle.EPHEMERAL);
            relation.setData(this.relations.get(id));
            storage.saveRelation(relation);
        }

        // 最后保存实例运行快照。
        EntityRecord value = new EntityRecord(RegistryGovernanceUtils.runtime(owner));
        value.setData(this.instance);
        storage.saveEntity(value);
    }
}

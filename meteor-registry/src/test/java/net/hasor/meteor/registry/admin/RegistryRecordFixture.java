/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.shared.rpc.InstanceData;
import net.hasor.meteor.registry.shared.rpc.ServiceMemberData;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import net.hasor.meteor.registry.shared.utils.RegistryGovernanceUtils;
import net.hasor.meteor.registry.shared.utils.RegistryJson;

/** 查询测试的登记夹具；服务目录共享，实例与服务之间只保存一条关系。 */
final class RegistryRecordFixture {
    private final InstanceData        instance;
    private final ServiceMemberData[] members;

    public RegistryRecordFixture(InstanceData instance, ServiceMemberData... members) {
        this.instance = instance;
        this.members = members;
    }

    public void save(StorageAccess storage) throws Exception {
        // 替换当前实例的成员关系，允许夹具切换发布或订阅角色。
        EntityRef owner = RegistryGovernanceUtils.instance(this.instance.getInstanceId());
        RelationQuery query = new RelationQuery();
        query.setTarget(owner);
        for (RelationRecord relation : RegistryGovernanceUtils.relations(storage, query)) {
            storage.deleteRelation(relation);
        }

        for (ServiceMemberData member : this.members) {
            EntityRef service = RegistryGovernanceUtils.service(member.getService().getServiceId());
            if (storage.getEntity(service) == null) {
                EntityRecord catalog = new EntityRecord(service);
                catalog.setData(RegistryJson.copy(member.getService(), EntServiceCatalogData.class));
                storage.saveEntity(catalog);
            }
            boolean provider = member.getService().getRole() == MetServiceType.Provider;
            RelationType role = provider ? RelationType.PROVIDES : RelationType.CONSUMES;
            RefServiceRelationData data = new RefServiceRelationData();
            data.setEnabled(member.isEnabled());
            data.setEndpoints(RegistryJson.copy(member.getEndpoints(), RefProtocolMountData[].class));
            data.setAddresses(member.getAddresses());
            RelationRecord relation = new RelationRecord(role, service, owner);
            relation.setLifecycle(RecordLifecycle.EPHEMERAL);
            relation.setData(data);
            storage.saveRelation(relation);
        }

        // 实例登记不携带服务清单、运行配置和地址本。
        EntInstanceRuntimeData instance = RegistryJson.copy(this.instance, EntInstanceRuntimeData.class);
        instance.setManagementAddresses(this.instance.getManagementAddress());
        EntityRecord value = new EntityRecord(RegistryGovernanceUtils.runtime(owner));
        value.setData(instance);
        storage.saveEntity(value);
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin;
import net.hasor.meteor.registry.shared.rpc.InstanceData;
import net.hasor.meteor.registry.shared.rpc.ServiceMemberData;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import net.hasor.meteor.registry.shared.utils.RegistryJson;

/** 查询测试的登记夹具；通过业务存储登记完整身份及当前服务。 */
final class RegistryRecordFixture {
    private final InstanceData        instance;
    private final ServiceMemberData[] members;

    public RegistryRecordFixture(InstanceData instance, ServiceMemberData... members) {
        this.instance = instance;
        this.members = members;
    }

    public void save(StorageAccess storage) throws Exception {
        String instanceId = this.instance.getInstanceId();
        EntInstanceRuntimeData runtime = RegistryJson.copy(this.instance, EntInstanceRuntimeData.class);
        runtime.setManagementAddresses(this.instance.getManagementAddress());
        storage.registerInstance(runtime);

        // 夹具只替换本实例的当前清单，目录与治理仍由正式存储维护。
        DirectoryQuery query = new DirectoryQuery();
        query.setLimit(1000);
        do {
            RecordPage<EntServiceRuntimeData> page = storage.instanceServices(instanceId, query);
            for (EntServiceRuntimeData service : page.getItems()) {
                storage.unregisterService(instanceId, service.getServiceId());
            }
            query.setCursor(page.getNextCursor());
        } while (query.getCursor() != null);
        for (ServiceMemberData member : this.members) {
            EntServiceRuntimeData service = RegistryJson.copy(member.getService(), EntServiceRuntimeData.class);
            service.setEnabled(member.isEnabled());
            service.setEndpoints(RegistryJson.copy(member.getEndpoints(), RefProtocolMountData[].class));
            service.setAddresses(member.getAddresses());
            service.setUsedProtocols(member.getUsedProtocols());
            storage.registerService(instanceId, service);
        }
    }
}

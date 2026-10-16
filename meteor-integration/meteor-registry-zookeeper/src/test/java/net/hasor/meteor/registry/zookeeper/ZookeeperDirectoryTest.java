/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.zookeeper;
import java.util.List;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import org.apache.curator.test.TestingServer;
import org.junit.Test;
import static net.hasor.meteor.registry.zookeeper.ZookeeperRecords.instance;
import static net.hasor.meteor.registry.zookeeper.ZookeeperRecords.service;
import static org.junit.Assert.*;

/** 应用、实例、服务成员目录的筛选范围与分页。 */
public class ZookeeperDirectoryTest {
    @Test
    public void instancePagesFilterUnitBeforeLimitingAndStayInsideApplication() throws Exception {
        // 相同单元分布在两个应用中，目标应用还包含不匹配的实例。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess storage = configuration.openStorage()) {
                storage.registerInstance(instance("a", "orders", "west"));
                storage.registerInstance(instance("b", "orders", "east"));
                storage.registerInstance(instance("c", "orders", "east"));
                storage.registerInstance(instance("d", "other", "east"));

                // 每页限制为一条，过滤应在限制之前完成，游标不能跨应用。
                DirectoryQuery query = new DirectoryQuery();
                query.setUnitName("east");
                query.setLimit(1);
                RecordPage<InstanceStatus> first = storage.instances("orders", query);
                assertEquals(1, first.getItems().size());
                assertEquals("b", first.getItems().get(0).getInstance().getInstanceId());
                assertNotNull(first.getNextCursor());

                query.setCursor(first.getNextCursor());
                RecordPage<InstanceStatus> second = storage.instances("orders", query);
                assertEquals(1, second.getItems().size());
                assertEquals("c", second.getItems().get(0).getInstance().getInstanceId());
                assertNull(second.getNextCursor());
            }
        }
    }

    @Test
    public void applicationDirectoryContainsEachApplicationOnce() throws Exception {
        // 同一应用的多个实例不能在应用目录中形成重复条目。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess storage = configuration.openStorage()) {
                storage.registerInstance(instance("a", "orders", "west"));
                storage.registerInstance(instance("b", "orders", "east"));
                storage.registerInstance(instance("c", "other", "east"));

                int applications = storage.applications(new DirectoryQuery()).getItems().size();
                assertEquals(2, applications);
                assertNotNull(storage.getApplication("orders"));
                assertNotNull(storage.getApplication("other"));
            }
        }
    }

    @Test
    public void consumerFiltersUseSelectedAddressProtocolInsteadOfConfiguredMount() throws Exception {
        // 消费者配置 rsf 接入点，但地址本实际选择 http。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess storage = configuration.openStorage()) {
                storage.registerInstance(instance("one", "orders", "east"));
                EntServiceRuntimeData provider = service("A", MetServiceType.Provider);
                provider.setEndpoints(new RefProtocolMountData[] { rsfEndpoint() });
                storage.registerService("one", provider);
                EntServiceRuntimeData consumer = service("B", MetServiceType.Consumer);
                consumer.setUsedProtocols(new String[] { "http" });
                consumer.setEndpoints(new RefProtocolMountData[] { rsfEndpoint() });
                storage.registerService("one", consumer);

                // 先过滤掉 A 再截取一条；两个查询方向都不能把 rsf 接入配置当成调用协议。
                DirectoryQuery query = new DirectoryQuery();
                query.setProtocol("http");
                query.setLimit(1);
                RecordPage<EntServiceRuntimeData> services = storage.instanceServices("one", query);
                assertEquals("B", services.getItems().get(0).getServiceId());
                RecordPage<RefServiceRelationData> consumers = storage.members("B", MetServiceType.Consumer, query);
                assertEquals(1, consumers.getItems().size());

                query.setProtocol("rsf");
                assertTrue(storage.members("B", MetServiceType.Consumer, query).getItems().isEmpty());
            }
        }
    }

    @Test
    public void providerFiltersApplyUnitAndApplicationBeforePagination() throws Exception {
        // 同一服务的两个提供者处于不同单元。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess storage = configuration.openStorage()) {
                storage.registerInstance(instance("one", "orders", "east"));
                storage.registerInstance(instance("two", "orders", "west"));
                EntServiceRuntimeData provider = service("A", MetServiceType.Provider);
                provider.setEndpoints(new RefProtocolMountData[] { rsfEndpoint() });
                storage.registerService("one", provider);
                storage.registerService("two", provider);

                // 多条件先筛选再分页，应用文本大小写不影响匹配。
                DirectoryQuery query = new DirectoryQuery();
                query.setProtocol("rsf");
                query.setUnitName("east");
                query.setField("application");
                query.setSearch("ORDERS");
                query.setLimit(1);
                RecordPage<RefServiceRelationData> selected = storage.members("A", MetServiceType.Provider, query);
                assertEquals(1, selected.getItems().size());
                assertEquals("one", selected.getItems().get(0).getInstance().getInstanceId());
                assertNull(selected.getNextCursor());
            }
        }
    }

    @Test
    public void applicationUnitsAreDistinctAndSortedWithinApplication() throws Exception {
        // 重复单元和其他应用的单元都不能污染当前应用的候选项。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess storage = configuration.openStorage()) {
                storage.registerInstance(instance("one", "orders", "east"));
                storage.registerInstance(instance("two", "orders", "west"));
                storage.registerInstance(instance("three", "orders", "east"));
                storage.registerInstance(instance("other", "inventory", "north"));

                List<String> units = List.of(storage.applicationUnits("orders"));
                assertEquals(List.of("east", "west"), units);
            }
        }
    }

    private static RefProtocolMountData rsfEndpoint() {
        RefProtocolMountData endpoint = new RefProtocolMountData();
        endpoint.setProtocol("rsf");
        endpoint.setProtocolName("rsf");
        endpoint.setPublishedAddress("rsf://127.0.0.1:8000");
        return endpoint;
    }

}

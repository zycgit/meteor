/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.zookeeper.unit;
import java.util.List;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import net.hasor.meteor.registry.zookeeper.ZookeeperRegistryConfiguration;
import org.apache.curator.test.TestingServer;
import org.junit.Test;
import static net.hasor.meteor.registry.zookeeper.unit.ZookeeperRecords.instance;
import static net.hasor.meteor.registry.zookeeper.unit.ZookeeperRecords.service;
import static org.junit.Assert.*;

/** 登记身份、会话所有权、临时成员与持久目录的边界。 */
public class ZookeeperRegistrationTest {
    @Test
    public void persistentDirectoriesSurviveSessionClose() throws Exception {
        // 同一注册中心分别打开登记会话和查询会话。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess reader = configuration.openStorage()) {
                try (StorageAccess writer = configuration.openStorage()) {
                    writer.registerInstance(instance("one", "orders", "east"));
                    EntServiceRuntimeData service = service("A", MetServiceType.Provider, "rsf://127.0.0.1:8000");
                    writer.registerService("one", service);

                    // 先确认两个方向都有完整声明，避免把未登记误判为关闭后消失。
                    assertEquals("A", reader.getInstanceService("one", "A").getName());
                    RefServiceRelationData member = reader.getMember("A", MetServiceType.Provider, "one");
                    assertEquals("orders", member.getInstance().getApplicationName());
                    assertEquals(Long.valueOf(123), member.getInstance().getProcessId());
                    assertEquals("A", member.getService().getName());
                }

                // 会话关闭后仅删除临时登记，应用、实例和服务档案仍可点查。
                assertNotNull(reader.getApplication("orders"));
                assertNotNull(reader.getInstance("one").getInstance());
                assertNull(reader.getInstance("one").getRuntime());
                assertNotNull(reader.getService("A"));
                assertNull(reader.getInstanceService("one", "A"));
                assertNull(reader.getMember("A", MetServiceType.Provider, "one"));
                int archivedInstances = reader.instances("orders", new DirectoryQuery()).getItems().size();
                assertEquals(1, archivedInstances);
            }
        }
    }

    @Test
    public void rollingUpgradeOnlyDiscoversInstancesActuallyProvidingTheService() throws Exception {
        // 模拟滚动升级：旧实例只提供 A，新实例同时提供 A 和 B。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess old = configuration.openStorage(); //
                 StorageAccess next = configuration.openStorage(); //
                 StorageAccess reader = configuration.openStorage()) {
                old.registerInstance(instance("old", "orders", "east"));
                EntServiceRuntimeData oldService = service("A", MetServiceType.Provider, "rsf://127.0.0.1:8000");
                old.registerService("old", oldService);
                next.registerInstance(instance("new", "orders", "east"));
                EntServiceRuntimeData shared = service("A", MetServiceType.Provider, "rsf://127.0.0.1:9000");
                EntServiceRuntimeData added = service("B", MetServiceType.Provider, "rsf://127.0.0.1:9000");
                next.registerService("new", shared);
                next.registerService("new", added);

                // 按 B 发现时只有新实例，不能仅按应用地址把旧实例也算进来。
                DirectoryQuery query = new DirectoryQuery();
                RecordPage<RefServiceRelationData> providers = reader.members("B", MetServiceType.Provider, query);
                assertEquals(1, providers.getItems().size());
                assertEquals("new", providers.getItems().get(0).getInstance().getInstanceId());
                assertNull(reader.getInstanceService("old", "B"));
                int sharedProviders = reader.members("A", MetServiceType.Provider, query).getItems().size();
                assertEquals(2, sharedProviders);
            }
        }
    }

    @Test
    public void replacedSessionCannotRegisterInstanceAgain() throws Exception {
        // 新会话接管相同实例 ID，旧会话不能再次抢回登记所有权。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess old = configuration.openStorage(); //
                 StorageAccess next = configuration.openStorage()) {
                old.registerInstance(instance("one", "orders", "east"));
                next.registerInstance(instance("one", "orders", "east"));

                assertThrows(IllegalStateException.class, () -> {
                    old.registerInstance(instance("one", "orders", "east"));
                });
                assertNotNull(next.getInstance("one").getRuntime());
            }
        }
    }

    @Test
    public void replacedSessionCannotWithdrawNewService() throws Exception {
        // 新会话接管实例后发布自己的服务。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess old = configuration.openStorage(); //
                 StorageAccess next = configuration.openStorage()) {
                old.registerInstance(instance("one", "orders", "east"));
                next.registerInstance(instance("one", "orders", "east"));
                next.registerService("one", service("B", MetServiceType.Provider));

                // 旧会话撤销同一个实例的服务时，必须暴露所有权冲突。
                assertThrows(IllegalStateException.class, () -> old.unregisterService("one", "B"));
                assertNotNull(next.getMember("B", MetServiceType.Provider, "one"));
            }
        }
    }

    @Test
    public void replacedSessionCloseKeepsOnlyNewRegistrations() throws Exception {
        // 同一个实例先后由两个会话登记，分别提供不同服务。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess old = configuration.openStorage(); //
                 StorageAccess next = configuration.openStorage()) {
                old.registerInstance(instance("one", "orders", "east"));
                old.registerService("one", service("A", MetServiceType.Provider));
                next.registerInstance(instance("one", "orders", "east"));
                next.registerService("one", service("B", MetServiceType.Provider));

                // 关闭旧会话不能删除接管者的临时节点，也不能保留旧服务。
                old.close();
                assertNotNull(next.getInstance("one").getRuntime());
                assertNotNull(next.getMember("B", MetServiceType.Provider, "one"));
                assertNull(next.getMember("A", MetServiceType.Provider, "one"));
            }
        }
    }

    @Test
    public void roleChangeReplacesBothDirectionsWithoutChangingOtherServices() throws Exception {
        // 同一实例最初发布两个服务。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess storage = configuration.openStorage()) {
                storage.registerInstance(instance("one", "orders", "east"));
                storage.registerService("one", service("A", MetServiceType.Provider));
                storage.registerService("one", service("B", MetServiceType.Provider));

                // 仅把 A 改为消费身份，实例清单和服务成员必须同步更新。
                storage.registerService("one", service("A", MetServiceType.Consumer));
                assertNull(storage.getMember("A", MetServiceType.Provider, "one"));
                assertNotNull(storage.getMember("A", MetServiceType.Consumer, "one"));
                assertEquals(MetServiceType.Consumer, storage.getInstanceService("one", "A").getRole());
                assertNotNull(storage.getMember("B", MetServiceType.Provider, "one"));
            }
        }
    }

    @Test
    public void withdrawalRemovesOnlySelectedRuntimeService() throws Exception {
        // 实例同时消费 A 并发布 B，两个服务都有持久档案。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess storage = configuration.openStorage()) {
                storage.registerInstance(instance("one", "orders", "east"));
                storage.registerService("one", service("A", MetServiceType.Consumer));
                storage.registerService("one", service("B", MetServiceType.Provider));

                // 撤销 A 仅删除该实例的登记，不删除目录或无关服务。
                storage.unregisterService("one", "A");
                assertNull(storage.getInstanceService("one", "A"));
                assertNull(storage.getMember("A", MetServiceType.Consumer, "one"));
                assertNotNull(storage.getService("A"));
                assertNotNull(storage.getMember("B", MetServiceType.Provider, "one"));
                int remaining = storage.instanceServices("one", new DirectoryQuery()).getItems().size();
                assertEquals(1, remaining);
            }
        }
    }

    @Test
    public void memberQueryReturnsDetachedIdentityAndDeclaration() throws Exception {
        // 登记可修改的实例身份与服务地址正文。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess storage = configuration.openStorage()) {
                storage.registerInstance(instance("one", "orders", "east"));
                EntServiceRuntimeData service = service("A", MetServiceType.Provider, "rsf://127.0.0.1:8000");
                storage.registerService("one", service);

                // 修改返回值不能悄悄回写存储中的任一正文。
                RefServiceRelationData read = storage.getMember("A", MetServiceType.Provider, "one");
                read.getInstance().setIpAddress("changed");
                read.getService().setAddresses(new String[0]);
                RefServiceRelationData unchanged = storage.getMember("A", MetServiceType.Provider, "one");
                assertEquals("127.0.0.1", unchanged.getInstance().getIpAddress());
                assertEquals(1, unchanged.getService().getAddresses().length);
            }
        }
    }

    @Test
    public void serviceSummaryReflectsProviderWithdrawal() throws Exception {
        // 查询同一服务的登记前后数量，确认统计依据实时成员而非持久目录。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess storage = configuration.openStorage()) {
                storage.registerInstance(instance("one", "orders", "east"));
                storage.registerService("one", service("A", MetServiceType.Provider));
                ServiceSummary registered = storage.serviceSummaries(List.of("A")).get("A");
                assertEquals(1, registered.getProviderCount());

                storage.unregisterService("one", "A");
                ServiceSummary withdrawn = storage.serviceSummaries(List.of("A")).get("A");
                assertEquals(0, withdrawn.getProviderCount());
            }
        }
    }

}

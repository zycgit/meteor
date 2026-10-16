/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.nacos;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** 新业务存储契约；只通过正式配置打开会话。 */
public class NacosStorageTest {
    @Test
    public void bothDirectoriesExposeTheCompleteRegistration() throws Exception {
        // 创建独立读写会话，登记一个带身份和地址的提供者。
        try (MockNacosSdk sdk = new MockNacosSdk()) {
            NacosRegistryConfiguration configuration = sdk.configuration();
            try (StorageAccess writer = configuration.openStorage(); //
                 StorageAccess reader = configuration.openStorage()) {
                writer.registerInstance(instance("one", "orders", "east"));
                writer.registerService("one", service("A", MetServiceType.Provider, "rsf://127.0.0.1:8000"));

                // 从实例与服务两个目录点查，同一登记的正文和身份均完整可用。
                EntServiceRuntimeData declaration = reader.getInstanceService("one", "A");
                assertEquals("A", declaration.getName());
                RefServiceRelationData member = reader.getMember("A", MetServiceType.Provider, "one");
                assertEquals("orders", member.getInstance().getApplicationName());
                assertEquals(Long.valueOf(123), member.getInstance().getProcessId());
                assertEquals("A", member.getService().getName());
            }
        }
    }

    @Test
    public void directDirectoriesSurviveSessionClose() throws Exception {
        // 写会话退出前已登记应用、实例、服务以及临时发布关系。
        try (MockNacosSdk sdk = new MockNacosSdk()) {
            NacosRegistryConfiguration configuration = sdk.configuration();
            try (StorageAccess reader = configuration.openStorage()) {
                try (StorageAccess writer = configuration.openStorage()) {
                    writer.registerInstance(instance("one", "orders", "east"));
                    writer.registerService("one", service("A", MetServiceType.Provider, "rsf://127.0.0.1:8000"));
                }

                // 关闭撤回全部运行登记，应用和服务定义仍然存在。
                InstanceStatus offline = reader.getInstance("one");
                assertNotNull(reader.getApplication("orders"));
                assertNull(offline);
                assertNotNull(reader.getService("A"));
                assertNull(reader.getInstanceService("one", "A"));
                assertNull(reader.getMember("A", MetServiceType.Provider, "one"));
                RecordPage<InstanceStatus> instances = reader.instances("orders", new DirectoryQuery());
                assertEquals(0, instances.getItems().size());
            }
        }
    }

    @Test
    public void rollingUpgradeOnlyDiscoversInstancesActuallyProvidingTheService() throws Exception {
        // 旧实例只发布 A，新实例同时发布 A 和 B。
        try (MockNacosSdk sdk = new MockNacosSdk()) {
            NacosRegistryConfiguration configuration = sdk.configuration();
            try (StorageAccess old = configuration.openStorage(); //
                 StorageAccess next = configuration.openStorage(); //
                 StorageAccess reader = configuration.openStorage()) {
                old.registerInstance(instance("old", "orders", "east"));
                old.registerService("old", service("A", MetServiceType.Provider, "rsf://127.0.0.1:8000"));
                next.registerInstance(instance("new", "orders", "east"));
                next.registerService("new", service("A", MetServiceType.Provider, "rsf://127.0.0.1:9000"));
                next.registerService("new", service("B", MetServiceType.Provider, "rsf://127.0.0.1:9000"));

                // 能连接旧实例不等于旧实例提供 B；直接查 B 的成员只能得到新实例。
                DirectoryQuery query = new DirectoryQuery();
                RecordPage<RefServiceRelationData> providers = reader.members("B", MetServiceType.Provider, query);
                assertEquals(1, providers.getItems().size());
                RefServiceRelationData provider = providers.getItems().get(0);
                assertEquals("new", provider.getInstance().getInstanceId());
                assertNull(reader.getInstanceService("old", "B"));
                RecordPage<RefServiceRelationData> common = reader.members("A", MetServiceType.Provider, query);
                assertEquals(2, common.getItems().size());
            }
        }
    }

    @Test
    public void anotherClientCannotClaimAnActiveInstanceId() throws Exception {
        // 一个实例 ID 只标识一个运行进程，不做新旧会话接管。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess owner = sdk.configuration().openStorage(); //
             StorageAccess other = sdk.configuration().openStorage()) {
            owner.registerInstance(instance("one", "orders", "east"));

            // 其他客户端不能使用同一个活动实例 ID 登记。
            EntInstanceRuntimeData duplicate = instance("one", "orders", "east");
            assertThrows(IllegalStateException.class, () -> other.registerInstance(duplicate));
        }
    }

    @Test
    public void anotherClientCannotWithdrawMembership() throws Exception {
        // 服务登记属于应用持有的 SDK 连接。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess owner = sdk.configuration().openStorage(); //
             StorageAccess other = sdk.configuration().openStorage()) {
            owner.registerInstance(instance("one", "orders", "east"));
            owner.registerService("one", service("A", MetServiceType.Provider));

            // 管理端不能冒充发布者撤回登记。
            assertThrows(IllegalStateException.class, () -> other.unregisterService("one", "A"));
            assertNotNull(owner.getMember("A", MetServiceType.Provider, "one"));
        }
    }

    @Test
    public void closingOneInstancePreservesAnotherOnTheSameIp() throws Exception {
        // 同一 IP 下两个进程各自持有临时登记。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess first = sdk.configuration().openStorage(); //
             StorageAccess second = sdk.configuration().openStorage()) {
            first.registerInstance(instance("one", "orders", "east"));
            second.registerInstance(instance("two", "orders", "east"));
            first.registerService("one", service("A", MetServiceType.Provider));
            second.registerService("two", service("A", MetServiceType.Provider));

            // 退出其中一个进程不影响另一个进程。
            first.close();
            assertNull(second.getInstance("one"));
            assertNotNull(second.getMember("A", MetServiceType.Provider, "two"));
        }
    }

    @Test
    public void roleChangesReplaceBothDirectionsWithoutChangingOtherServices() throws Exception {
        // 准备模拟 SDK，通过正式配置打开实际存储实现。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.registerInstance(instance("one", "orders", "east"));
            storage.registerService("one", service("A", MetServiceType.Provider, "rsf://127.0.0.1:8000"));
            storage.registerService("one", service("B", MetServiceType.Provider, "rsf://127.0.0.1:8000"));

            // 将 A 改为消费者；两个目录一并更新，B 仍然是提供者。
            storage.registerService("one", service("A", MetServiceType.Consumer));
            assertNull(storage.getMember("A", MetServiceType.Provider, "one"));
            assertNotNull(storage.getMember("A", MetServiceType.Consumer, "one"));
            assertEquals(MetServiceType.Consumer, storage.getInstanceService("one", "A").getRole());
            assertNotNull(storage.getMember("B", MetServiceType.Provider, "one"));
        }
    }

    @Test
    public void withdrawingServiceKeepsItsCatalogAndUnrelatedMembership() throws Exception {
        // 准备模拟 SDK，通过正式配置打开实际存储实现。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.registerInstance(instance("one", "orders", "east"));
            storage.registerService("one", service("A", MetServiceType.Provider, "rsf://127.0.0.1:8000"));
            storage.registerService("one", service("B", MetServiceType.Provider, "rsf://127.0.0.1:8000"));

            // 仅撤回 A 的临时登记，持久服务目录和 B 的登记不受影响。
            storage.unregisterService("one", "A");
            assertNull(storage.getInstanceService("one", "A"));
            assertNull(storage.getMember("A", MetServiceType.Provider, "one"));
            assertNotNull(storage.getService("A"));
            assertNotNull(storage.getMember("B", MetServiceType.Provider, "one"));
            RecordPage<EntServiceRuntimeData> remaining = storage.instanceServices("one", new DirectoryQuery());
            assertEquals(1, remaining.getItems().size());
        }
    }

    @Test
    public void applicationPagesFilterBeforeLimitingAndDoNotMixDirectories() throws Exception {
        // 准备模拟 SDK，通过正式配置打开实际存储实现。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.registerInstance(instance("a", "orders", "west"));
            storage.registerInstance(instance("b", "orders", "east"));
            storage.registerInstance(instance("c", "orders", "east"));
            storage.registerInstance(instance("d", "other", "east"));

            // 在 orders 应用内按单元筛选后分页，不混入其他应用或单元。
            DirectoryQuery query = new DirectoryQuery();
            query.setUnitName("east");
            query.setLimit(1);
            RecordPage<InstanceStatus> first = storage.instances("orders", query);
            query.setCursor(first.getNextCursor());
            RecordPage<InstanceStatus> second = storage.instances("orders", query);
            String firstId = first.getItems().get(0).getInstance().getInstanceId();
            String secondId = second.getItems().get(0).getInstance().getInstanceId();
            assertEquals(Set.of("b", "c"), Set.of(firstId, secondId));
            assertNull(second.getNextCursor());
            RecordPage<EntApplicationCatalogData> applications = storage.applications(new DirectoryQuery());
            assertEquals(2, applications.getItems().size());
        }
    }

    @Test
    public void metadataCreationRejectsReusingTheEmptyRevision() throws Exception {
        // 准备模拟 SDK，通过正式配置打开实际存储实现。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            EntityRecord<EntMetadataData> initial = new EntityRecord<>();
            initial.setData(new EntMetadataData());
            initial.getData().getMetadata().putAll(Map.of("a", 1, "b", 2));
            storage.saveMetadata(null, "A", initial);

            // 同一个创建版本不能覆盖已经存在的整批元信息。
            assertThrows(ConcurrentModificationException.class, () -> storage.saveMetadata(null, "A", initial));
            Map<String, Object> saved = storage.getMetadata(null, "A").getData().getMetadata();
            assertEquals(Map.of("a", 1, "b", 2), saved);
        }
    }

    @Test
    public void savingRuleLeavesMetadataRevisionAndScopeUnchanged() throws Exception {
        // 准备模拟 SDK，通过正式配置打开实际存储实现。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            EntityRecord<EntMetadataData> initial = new EntityRecord<>();
            initial.setData(new EntMetadataData());
            initial.getData().getMetadata().putAll(Map.of("a", 1, "b", 2));
            storage.saveMetadata(null, "A", initial);

            // 保存独立规则正文，不改写服务元信息，也不产生实例覆盖。
            String revision = storage.getMetadata(null, "A").getRevision();
            EntityRecord<EntRuleConfigurationData> rule = new EntityRecord<>();
            rule.setData(new EntRuleConfigurationData());
            rule.getData().setRule("<route/>");
            storage.saveRule(null, "A", RuleKind.SERVICE_ROUTE, rule);
            assertEquals(revision, storage.getMetadata(null, "A").getRevision());
            assertNull(storage.getMetadata("one", "A"));
        }
    }

    @Test
    public void metadataQueriesReturnDetachedData() throws Exception {
        // 准备模拟 SDK，通过正式配置打开实际存储实现。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            EntityRecord<EntMetadataData> initial = new EntityRecord<>();
            initial.setData(new EntMetadataData());
            initial.getData().getMetadata().putAll(Map.of("a", 1, "b", 2));
            storage.saveMetadata(null, "A", initial);

            // 修改读取结果不应污染存储中的整批元信息。
            EntityRecord<EntMetadataData> read = storage.getMetadata(null, "A");
            read.getData().getMetadata().clear();
            Map<String, Object> saved = storage.getMetadata(null, "A").getData().getMetadata();
            assertEquals(Map.of("a", 1, "b", 2), saved);
        }
    }

    @Test
    public void providerSubscriptionDoesNotRepeatForIdenticalRegistration() throws Exception {
        // 注册提供者并等待首次订阅快照。
        try (MockNacosSdk sdk = new MockNacosSdk()) {
            NacosRegistryConfiguration configuration = sdk.configuration();
            try (StorageAccess writer = configuration.openStorage(); //
                 StorageAccess reader = configuration.openStorage()) {
                writer.registerInstance(instance("one", "orders", "east"));
                EntServiceRuntimeData service = service("A", MetServiceType.Provider, "rsf://127.0.0.1:8000");
                writer.registerService("one", service);
                AtomicInteger notices = new AtomicInteger();
                CountDownLatch first = new CountDownLatch(1);
                Cancellable subscription = reader.watchProviders("A", () -> {
                    notices.incrementAndGet();
                    first.countDown();
                });
                assertTrue(first.await(3, TimeUnit.SECONDS));

                // 重复提交相同实例和服务正文，不应制造额外通知。
                writer.registerInstance(instance("one", "orders", "east"));
                writer.registerService("one", service);

                // 等待多个检查周期，确认相同正文不会产生自身刷新循环。
                Thread.sleep(350);
                assertEquals(1, notices.get());
                assertTrue(subscription.cancel());
                assertFalse(subscription.cancel());
            }
        }
    }

    @Test
    public void memberQueriesReturnDetachedData() throws Exception {
        // 准备模拟 SDK，通过正式配置打开实际存储实现。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.registerInstance(instance("one", "orders", "east"));
            storage.registerService("one", service("A", MetServiceType.Provider, "rsf://127.0.0.1:8000"));

            // 改动返回的身份和服务地址，随后重读应保持原始登记。
            RefServiceRelationData read = storage.getMember("A", MetServiceType.Provider, "one");
            read.getInstance().setIpAddress("changed");
            read.getService().setAddresses(new String[0]);
            RefServiceRelationData unchanged = storage.getMember("A", MetServiceType.Provider, "one");
            assertEquals("127.0.0.1", unchanged.getInstance().getIpAddress());
            assertEquals(1, unchanged.getService().getAddresses().length);
        }
    }

    @Test
    public void serviceSummaryTracksProviderWithdrawal() throws Exception {
        // 准备模拟 SDK，通过正式配置打开实际存储实现。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.registerInstance(instance("one", "orders", "east"));
            storage.registerService("one", service("A", MetServiceType.Provider, "rsf://127.0.0.1:8000"));

            // 提供者撤回后，服务摘要立即反映成员数量变化。
            ServiceSummary before = storage.serviceSummaries(List.of("A")).get("A");
            assertEquals(1, before.getProviderCount());
            storage.unregisterService("one", "A");
            ServiceSummary after = storage.serviceSummaries(List.of("A")).get("A");
            assertEquals(0, after.getProviderCount());
        }
    }

    @Test
    public void consumerProtocolFilterUsesTheSelectedProtocol() throws Exception {
        // 准备模拟 SDK，通过正式配置打开实际存储实现。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.registerInstance(instance("one", "orders", "east"));
            EntServiceRuntimeData consumer = service("B", MetServiceType.Consumer);
            consumer.setUsedProtocols(new String[] { "http" });
            RefProtocolMountData endpoint = new RefProtocolMountData();
            endpoint.setProtocol("rsf");
            endpoint.setProtocolName("rsf");
            endpoint.setPublishedAddress("rsf://127.0.0.1:8000");
            consumer.setEndpoints(new RefProtocolMountData[] { endpoint });
            storage.registerService("one", consumer);

            // 两个目录都按实际使用的 http 协议返回消费者。
            DirectoryQuery query = new DirectoryQuery();
            query.setProtocol("http");
            query.setLimit(1);
            RecordPage<EntServiceRuntimeData> services = storage.instanceServices("one", query);
            assertEquals("B", services.getItems().get(0).getServiceId());
            RecordPage<RefServiceRelationData> members = storage.members("B", MetServiceType.Consumer, query);
            assertEquals(1, members.getItems().size());
        }
    }

    @Test
    public void consumerProtocolFilterIgnoresUnusedEndpointProtocol() throws Exception {
        // 准备模拟 SDK，通过正式配置打开实际存储实现。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.registerInstance(instance("one", "orders", "east"));
            EntServiceRuntimeData consumer = service("B", MetServiceType.Consumer);
            consumer.setUsedProtocols(new String[] { "http" });
            RefProtocolMountData endpoint = new RefProtocolMountData();
            endpoint.setProtocol("rsf");
            endpoint.setProtocolName("rsf");
            endpoint.setPublishedAddress("rsf://127.0.0.1:8000");
            consumer.setEndpoints(new RefProtocolMountData[] { endpoint });
            storage.registerService("one", consumer);

            // 已配置的 rsf 接入点不是消费者实际选用的协议。
            DirectoryQuery query = new DirectoryQuery();
            query.setProtocol("rsf");
            RecordPage<RefServiceRelationData> members = storage.members("B", MetServiceType.Consumer, query);
            assertTrue(members.getItems().isEmpty());
        }
    }

    @Test
    public void memberUnitAndTextFiltersApplyBeforePagination() throws Exception {
        // 准备模拟 SDK，通过正式配置打开实际存储实现。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.registerInstance(instance("one", "orders", "east"));
            storage.registerInstance(instance("two", "orders", "west"));
            EntServiceRuntimeData provider = service("A", MetServiceType.Provider);
            RefProtocolMountData endpoint = new RefProtocolMountData();
            endpoint.setProtocol("rsf");
            endpoint.setProtocolName("rsf");
            endpoint.setPublishedAddress("rsf://127.0.0.1:8000");
            provider.setEndpoints(new RefProtocolMountData[] { endpoint });
            storage.registerService("one", provider);
            storage.registerService("two", provider);

            // 协议、单元和应用名称共同筛选后才截取结果页。
            DirectoryQuery query = new DirectoryQuery();
            query.setProtocol("rsf");
            query.setUnitName("east");
            query.setField("application");
            query.setSearch("ORDERS");
            query.setLimit(1);
            RecordPage<RefServiceRelationData> selected = storage.members("A", MetServiceType.Provider, query);
            assertEquals(1, selected.getItems().size());
            RefServiceRelationData selectedMember = selected.getItems().get(0);
            assertEquals("one", selectedMember.getInstance().getInstanceId());
            assertNull(selected.getNextCursor());
        }
    }

    @Test
    public void applicationUnitsContainItsRegisteredUnits() throws Exception {
        // 准备模拟 SDK，通过正式配置打开实际存储实现。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.registerInstance(instance("one", "orders", "east"));
            storage.registerInstance(instance("two", "orders", "west"));
            storage.registerInstance(instance("three", "other", "north"));

            // 单元目录仅来自指定应用，不混入其他应用的单元。
            String[] units = storage.applicationUnits("orders");
            assertArrayEquals(new String[] { "east", "west" }, units);
        }
    }

    private static EntInstanceRuntimeData instance(String id, String application, String unit) {
        EntInstanceRuntimeData instance = new EntInstanceRuntimeData();
        instance.setInstanceId(id);
        instance.setApplicationName(application);
        instance.setUnitName(unit);
        instance.setIpAddress("127.0.0.1");
        instance.setProcessId(123L);
        return instance;
    }

    private static EntServiceRuntimeData service(String id, MetServiceType role, String... addresses) {
        EntServiceRuntimeData service = new EntServiceRuntimeData();
        service.setServiceId(id);
        service.setName(id);
        service.setRole(role);
        service.setAddresses(addresses);
        return service;
    }
}

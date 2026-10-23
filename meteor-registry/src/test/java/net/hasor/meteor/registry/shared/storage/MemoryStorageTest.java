/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.shared.storage;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import net.hasor.meteor.registry.shared.storage.domain.*;
import net.hasor.meteor.registry.shared.utils.RegistryJson;
import org.junit.Test;
import static org.junit.Assert.*;

/** 新业务存储契约；只通过正式配置打开会话。 */
public class MemoryStorageTest {
    @Test
    public void directDirectoriesSurviveSessionClose() throws Exception {
        MemoryRegistryConfiguration configuration = new MemoryRegistryConfiguration();
        try (StorageAccess reader = configuration.openStorage()) {
            try (StorageAccess writer = configuration.openStorage()) {
                writer.registerInstance(instance("one", "orders", "east"));
                writer.registerService("one", service("A", MetServiceType.Provider, "rsf://127.0.0.1:8000"));

                // 两个方向均可点查完整声明与实例身份。
                assertEquals("A", reader.getInstanceService("one", "A").getName());
                RefServiceRelationData member = reader.getMember("A", MetServiceType.Provider, "one");
                assertEquals("orders", member.getInstance().getApplicationName());
                assertEquals(Long.valueOf(123), member.getInstance().getProcessId());
                assertEquals("A", member.getService().getName());
            }

            // 临时登记消失，三个持久目录仍可点查。
            assertNotNull(reader.getApplication("orders"));
            assertNotNull(reader.getInstance("one").getInstance());
            assertNull(reader.getInstance("one").getRuntime());
            assertNotNull(reader.getService("A"));
            assertNull(reader.getInstanceService("one", "A"));
            assertNull(reader.getMember("A", MetServiceType.Provider, "one"));
            assertEquals(1, reader.instances("orders", new DirectoryQuery()).getItems().size());
        }
    }

    @Test
    public void serviceCatalogUpdatesPreserveUnspecifiedIdentityFields() throws Exception {
        // 目录允许管理端只更新部分身份字段。
        MemoryRegistryConfiguration configuration = new MemoryRegistryConfiguration();
        try (StorageAccess storage = configuration.openStorage()) {
            EntServiceCatalogData original = new EntServiceCatalogData();
            original.setServiceId("orders");
            original.setGroup("orders-group");
            original.setType("example.OrderService");
            original.setVersion("release-1.0");
            storage.saveService(original);

            // 后续只更新名称，已有身份与字符串版本保持原义。
            EntServiceCatalogData partial = new EntServiceCatalogData();
            partial.setServiceId("orders");
            partial.setName("OrderService");
            storage.saveService(partial);
            EntServiceCatalogData stored = storage.getService("orders");
            assertEquals("orders-group", stored.getGroup());
            assertEquals("example.OrderService", stored.getType());
            assertEquals("release-1.0", stored.getVersion());
            assertEquals("OrderService", stored.getName());
        }
    }

    @Test
    public void differentInstancesKeepTheirOwnContractIds() throws Exception {
        // 同一字符串服务 ID 可以由不同接口契约版本提供，各实例保留自己的声明。
        MemoryRegistryConfiguration configuration = new MemoryRegistryConfiguration();
        try (StorageAccess first = configuration.openStorage(); //
             StorageAccess second = configuration.openStorage()) {
            first.registerInstance(instance("first", "orders", "east"));
            second.registerInstance(instance("second", "orders", "east"));
            EntServiceRuntimeData original = service("orders", MetServiceType.Provider);
            original.setDescription(RegistryJson.copy(Map.of("contractServiceId", 0xffff_ffffL), ObjectNode.class));
            first.registerService("first", original);
            EntServiceRuntimeData unspecified = service("orders", MetServiceType.Provider);
            ObjectNode description = RegistryJson.copy(Map.of(), ObjectNode.class);
            description.putNull("contractServiceId");
            unspecified.setDescription(description);
            second.registerService("second", unspecified);

            // 编号只归属实例的完整描述，不从共享目录或其他实例回填。
            EntServiceRuntimeData firstService = first.getInstanceService("first", "orders");
            EntServiceRuntimeData secondService = second.getInstanceService("second", "orders");
            assertEquals(0xffff_ffffL, firstService.getDescription().path("contractServiceId").asLong());
            assertTrue(secondService.getDescription().path("contractServiceId").isNull());
            RefServiceRelationData member = first.getMember("orders", MetServiceType.Provider, "second");
            assertTrue(member.getService().getDescription().path("contractServiceId").isNull());
            ObjectNode catalog = RegistryJson.copy(first.getService("orders"), ObjectNode.class);
            assertFalse(catalog.has("contractServiceId"));
            ObjectNode runtime = RegistryJson.copy(firstService, ObjectNode.class);
            assertFalse(runtime.has("contractServiceId"));
            assertEquals(0xffff_ffffL, runtime.path("description").path("contractServiceId").asLong());
        }
    }

    @Test
    public void rollingUpgradeOnlyDiscoversInstancesActuallyProvidingTheService() throws Exception {
        MemoryRegistryConfiguration configuration = new MemoryRegistryConfiguration();
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
            assertEquals("new", providers.getItems().get(0).getInstance().getInstanceId());
            assertNull(reader.getInstanceService("old", "B"));
            assertEquals(2, reader.members("A", MetServiceType.Provider, new DirectoryQuery()).getItems().size());
        }
    }

    @Test
    public void replacedSessionCannotWriteOrRemoveNewRegistration() throws Exception {
        MemoryRegistryConfiguration configuration = new MemoryRegistryConfiguration();
        try (StorageAccess old = configuration.openStorage(); //
             StorageAccess next = configuration.openStorage()) {
            old.registerInstance(instance("one", "orders", "east"));
            old.registerService("one", service("A", MetServiceType.Provider, "rsf://127.0.0.1:8000"));
            next.registerInstance(instance("one", "orders", "east"));
            next.registerService("one", service("B", MetServiceType.Provider, "rsf://127.0.0.1:9000"));

            assertThrows(IllegalStateException.class, () -> old.registerInstance(instance("one", "orders", "east")));
            assertThrows(IllegalStateException.class, () -> old.unregisterService("one", "B"));
            old.close();
            assertNotNull(next.getInstance("one").getRuntime());
            assertNotNull(next.getMember("B", MetServiceType.Provider, "one"));
            assertNull(next.getMember("A", MetServiceType.Provider, "one"));
        }
    }

    @Test
    public void roleChangesReplaceBothDirectionsWithoutChangingOtherServices() throws Exception {
        MemoryRegistryConfiguration configuration = new MemoryRegistryConfiguration();
        try (StorageAccess storage = configuration.openStorage()) {
            storage.registerInstance(instance("one", "orders", "east"));
            storage.registerService("one", service("A", MetServiceType.Provider, "rsf://127.0.0.1:8000"));
            storage.registerService("one", service("B", MetServiceType.Provider, "rsf://127.0.0.1:8000"));
            storage.registerService("one", service("A", MetServiceType.Consumer));

            assertNull(storage.getMember("A", MetServiceType.Provider, "one"));
            assertNotNull(storage.getMember("A", MetServiceType.Consumer, "one"));
            assertEquals(MetServiceType.Consumer, storage.getInstanceService("one", "A").getRole());
            assertNotNull(storage.getMember("B", MetServiceType.Provider, "one"));
            storage.unregisterService("one", "A");
            assertNull(storage.getInstanceService("one", "A"));
            assertNotNull(storage.getService("A"));
            assertEquals(1, storage.instanceServices("one", new DirectoryQuery()).getItems().size());
        }
    }

    @Test
    public void applicationPagesFilterBeforeLimitingAndDoNotMixDirectories() throws Exception {
        MemoryRegistryConfiguration configuration = new MemoryRegistryConfiguration();
        try (StorageAccess storage = configuration.openStorage()) {
            storage.registerInstance(instance("a", "orders", "west"));
            storage.registerInstance(instance("b", "orders", "east"));
            storage.registerInstance(instance("c", "orders", "east"));
            storage.registerInstance(instance("d", "other", "east"));
            DirectoryQuery query = new DirectoryQuery();
            query.setUnitName("east");
            query.setLimit(1);
            RecordPage<InstanceStatus> first = storage.instances("orders", query);
            assertEquals("b", first.getItems().get(0).getInstance().getInstanceId());
            query.setCursor(first.getNextCursor());
            RecordPage<InstanceStatus> second = storage.instances("orders", query);
            assertEquals("c", second.getItems().get(0).getInstance().getInstanceId());
            assertNull(second.getNextCursor());
            assertEquals(2, storage.applications(new DirectoryQuery()).getItems().size());
        }
    }

    @Test
    public void metadataCasIsAtomicAndIndependentOfRuleAndDeclaration() throws Exception {
        MemoryRegistryConfiguration configuration = new MemoryRegistryConfiguration();
        try (StorageAccess storage = configuration.openStorage()) {
            EntityRecord<EntMetadataData> first = new EntityRecord<>();
            first.setData(new EntMetadataData());
            first.getData().getMetadata().putAll(Map.of("a", 1, "b", 2));
            storage.saveMetadata(null, "A", first);
            assertThrows(ConcurrentModificationException.class, () -> storage.saveMetadata(null, "A", first));

            EntityRecord<EntMetadataData> previous = storage.getMetadata(null, "A");
            EntityRecord<EntRuleConfigurationData> rule = new EntityRecord<>();
            rule.setData(new EntRuleConfigurationData());
            rule.getData().setRule("<route/>");
            storage.saveRule(null, "A", RuleKind.SERVICE_ROUTE, rule);
            assertEquals(previous.getRevision(), storage.getMetadata(null, "A").getRevision());
            previous.getData().getMetadata().clear();
            assertEquals(2, storage.getMetadata(null, "A").getData().getMetadata().size());
            assertNull(storage.getMetadata("one", "A"));
        }
    }

    @Test
    public void providerSubscriptionDoesNotRepeatForIdenticalRegistration() throws Exception {
        MemoryRegistryConfiguration configuration = new MemoryRegistryConfiguration();
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
            writer.registerInstance(instance("one", "orders", "east"));
            writer.registerService("one", service);

            // 等待多个检查周期，确认相同正文不会产生自身刷新循环。
            Thread.sleep(350);
            assertEquals(1, notices.get());
            assertTrue(subscription.cancel());
            assertFalse(subscription.cancel());
        }
    }

    @Test
    public void queriesReturnDetachedDataAndSummaryTracksWithdrawal() throws Exception {
        MemoryRegistryConfiguration configuration = new MemoryRegistryConfiguration();
        try (StorageAccess storage = configuration.openStorage()) {
            storage.registerInstance(instance("one", "orders", "east"));
            storage.registerService("one", service("A", MetServiceType.Provider, "rsf://127.0.0.1:8000"));
            RefServiceRelationData read = storage.getMember("A", MetServiceType.Provider, "one");
            read.getInstance().setIpAddress("changed");
            read.getService().setAddresses(new String[0]);
            RefServiceRelationData unchanged = storage.getMember("A", MetServiceType.Provider, "one");
            assertEquals("127.0.0.1", unchanged.getInstance().getIpAddress());
            assertEquals(1, unchanged.getService().getAddresses().length);
            assertEquals(1, storage.serviceSummaries(List.of("A")).get("A").getProviderCount());
            storage.unregisterService("one", "A");
            assertEquals(0, storage.serviceSummaries(List.of("A")).get("A").getProviderCount());
        }
    }

    @Test
    public void directoryFiltersApplyBeforePaginationAndUseConsumerSelection() throws Exception {
        MemoryRegistryConfiguration configuration = new MemoryRegistryConfiguration();
        try (StorageAccess storage = configuration.openStorage()) {
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
            EntServiceRuntimeData consumer = service("B", MetServiceType.Consumer);
            consumer.setUsedProtocols(new String[] { "http" });
            consumer.setEndpoints(new RefProtocolMountData[] { endpoint });
            storage.registerService("one", consumer);

            // 协议筛选以消费者实际选择为准，不能误用它配置的 rsf 接入点。
            DirectoryQuery query = new DirectoryQuery();
            query.setProtocol("http");
            query.setLimit(1);
            assertEquals("B", storage.instanceServices("one", query).getItems().get(0).getServiceId());
            assertEquals(1, storage.members("B", MetServiceType.Consumer, query).getItems().size());
            query.setProtocol("rsf");
            assertTrue(storage.members("B", MetServiceType.Consumer, query).getItems().isEmpty());

            // 单元和文本筛选在截取页之前完成。
            query.setUnitName("east");
            query.setField("application");
            query.setSearch("ORDERS");
            RecordPage<RefServiceRelationData> selected = storage.members("A", MetServiceType.Provider, query);
            assertEquals(1, selected.getItems().size());
            assertEquals("one", selected.getItems().get(0).getInstance().getInstanceId());
            assertNull(selected.getNextCursor());
            assertEquals(List.of("east", "west"), List.of(storage.applicationUnits("orders")));
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

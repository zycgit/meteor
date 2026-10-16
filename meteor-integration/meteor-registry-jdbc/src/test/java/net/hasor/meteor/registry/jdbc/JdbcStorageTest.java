/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.jdbc;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import org.junit.Test;
import static net.hasor.example.registry.jdbc.JdbcTestSupport.configuration;
import static net.hasor.example.registry.jdbc.JdbcTestSupport.createDatabase;
import static net.hasor.meteor.registry.jdbc.JdbcStorageData.*;
import static org.junit.Assert.*;

/** 使用 H2 验证正式存储契约，不替代应用接入与 RPC 场景。 */
public class JdbcStorageTest {
    @Test
    public void directLookupsReturnServiceDeclarationAndInstanceIdentity() throws Exception {
        // 准备应用实例及其实际发布的服务。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess writer = configuration.openStorage(); //
             StorageAccess reader = configuration.openStorage()) {
            writer.registerInstance(instance("one", "orders", "east"));
            writer.registerService("one", service("A", MetServiceType.Provider, "rsf://127.0.0.1:8000"));

            // 两个查询方向都返回完整正文，不需要额外关联实例与服务。
            EntServiceRuntimeData registered = reader.getInstanceService("one", "A");
            RefServiceRelationData member = reader.getMember("A", MetServiceType.Provider, "one");
            assertEquals("A", registered.getName());
            assertEquals("orders", member.getInstance().getApplicationName());
            assertEquals(Long.valueOf(123), member.getInstance().getProcessId());
            assertEquals("A", member.getService().getName());
        }
    }

    @Test
    public void sessionCloseRemovesRuntimeAndKeepsPersistentDirectories() throws Exception {
        // 读写会话分开，关闭发布者后仍能从另一个会话查询档案。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess reader = configuration.openStorage()) {
            try (StorageAccess writer = configuration.openStorage()) {
                writer.registerInstance(instance("one", "orders", "east"));
                writer.registerService("one", service("A", MetServiceType.Provider, "rsf://127.0.0.1:8000"));
            }

            // 临时登记消失，应用、实例、服务的持久目录继续存在。
            InstanceStatus offline = reader.getInstance("one");
            RecordPage<InstanceStatus> instances = reader.instances("orders", new DirectoryQuery());
            assertNotNull(reader.getApplication("orders"));
            assertNotNull(offline.getInstance());
            assertNull(offline.getRuntime());
            assertNotNull(reader.getService("A"));
            assertNull(reader.getInstanceService("one", "A"));
            assertNull(reader.getMember("A", MetServiceType.Provider, "one"));
            assertEquals(1, instances.getItems().size());
        }
    }

    @Test
    public void rollingUpgradeOnlyDiscoversInstancesActuallyProvidingTheService() throws Exception {
        // 旧实例只有 A，新实例同时提供 A、B。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess old = configuration.openStorage(); //
             StorageAccess next = configuration.openStorage(); //
             StorageAccess reader = configuration.openStorage()) {
            old.registerInstance(instance("old", "orders", "east"));
            old.registerService("old", service("A", MetServiceType.Provider, "rsf://127.0.0.1:8000"));
            next.registerInstance(instance("new", "orders", "east"));
            next.registerService("new", service("A", MetServiceType.Provider, "rsf://127.0.0.1:9000"));
            next.registerService("new", service("B", MetServiceType.Provider, "rsf://127.0.0.1:9000"));

            // B 只发现新实例，不能把同应用的旧地址误认为 B 的提供者。
            DirectoryQuery query = new DirectoryQuery();
            RecordPage<RefServiceRelationData> providers = reader.members("B", MetServiceType.Provider, query);
            assertEquals(1, providers.getItems().size());
            String providerId = providers.getItems().get(0).getInstance().getInstanceId();
            assertEquals("new", providerId);
            assertNull(reader.getInstanceService("old", "B"));
            RecordPage<RefServiceRelationData> commonProviders = reader.members("A", MetServiceType.Provider, query);
            assertEquals(2, commonProviders.getItems().size());
        }
    }

    @Test
    public void replacedSessionCannotRegisterItsOldInstance() throws Exception {
        // 新会话接管同一实例，并用服务 B 替换旧会话的服务 A。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess old = configuration.openStorage(); //
             StorageAccess next = configuration.openStorage()) {
            old.registerInstance(instance("one", "orders", "east"));
            old.registerService("one", service("A", MetServiceType.Provider, "rsf://127.0.0.1:8000"));
            next.registerInstance(instance("one", "orders", "east"));
            next.registerService("one", service("B", MetServiceType.Provider, "rsf://127.0.0.1:9000"));

            // 旧会话的迟到动作不能破坏接管后的登记。
            assertThrows(IllegalStateException.class, () -> old.registerInstance(instance("one", "orders", "east")));
            assertNotNull(next.getInstance("one").getRuntime());
            assertNotNull(next.getMember("B", MetServiceType.Provider, "one"));
            assertNull(next.getMember("A", MetServiceType.Provider, "one"));
        }
    }

    @Test
    public void replacedSessionCannotWithdrawNewService() throws Exception {
        // 新会话接管同一实例，并用服务 B 替换旧会话的服务 A。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess old = configuration.openStorage(); //
             StorageAccess next = configuration.openStorage()) {
            old.registerInstance(instance("one", "orders", "east"));
            old.registerService("one", service("A", MetServiceType.Provider, "rsf://127.0.0.1:8000"));
            next.registerInstance(instance("one", "orders", "east"));
            next.registerService("one", service("B", MetServiceType.Provider, "rsf://127.0.0.1:9000"));

            // 旧会话的迟到动作不能破坏接管后的登记。
            assertThrows(IllegalStateException.class, () -> old.unregisterService("one", "B"));
            assertNotNull(next.getInstance("one").getRuntime());
            assertNotNull(next.getMember("B", MetServiceType.Provider, "one"));
            assertNull(next.getMember("A", MetServiceType.Provider, "one"));
        }
    }

    @Test
    public void replacedSessionCloseKeepsNewRegistration() throws Exception {
        // 新会话接管同一实例，并用服务 B 替换旧会话的服务 A。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess old = configuration.openStorage(); //
             StorageAccess next = configuration.openStorage()) {
            old.registerInstance(instance("one", "orders", "east"));
            old.registerService("one", service("A", MetServiceType.Provider, "rsf://127.0.0.1:8000"));
            next.registerInstance(instance("one", "orders", "east"));
            next.registerService("one", service("B", MetServiceType.Provider, "rsf://127.0.0.1:9000"));

            // 旧会话的迟到动作不能破坏接管后的登记。
            old.close();
            assertNotNull(next.getInstance("one").getRuntime());
            assertNotNull(next.getMember("B", MetServiceType.Provider, "one"));
            assertNull(next.getMember("A", MetServiceType.Provider, "one"));
        }
    }

    @Test
    public void roleChangesReplaceBothDirectionsWithoutChangingOtherServices() throws Exception {
        // 同一实例最初提供 A、B，只改变 A 的发布身份。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess storage = configuration.openStorage()) {
            storage.registerInstance(instance("one", "orders", "east"));
            EntServiceRuntimeData provider = service("A", MetServiceType.Provider, "rsf://127.0.0.1:8000");
            provider.setEndpoints(new RefProtocolMountData[] { rsfEndpoint() });
            storage.registerService("one", provider);
            storage.registerService("one", service("B", MetServiceType.Provider, "rsf://127.0.0.1:8000"));

            // A 改为消费者，两侧视图同步替换，B 的发布关系不变。
            EntServiceRuntimeData consumer = service("A", MetServiceType.Consumer);
            consumer.setUsedProtocols(new String[] { "http" });
            storage.registerService("one", consumer);
            EntServiceRuntimeData updated = storage.getInstanceService("one", "A");
            assertNull(storage.getMember("A", MetServiceType.Provider, "one"));
            assertNotNull(storage.getMember("A", MetServiceType.Consumer, "one"));
            assertEquals(MetServiceType.Consumer, updated.getRole());
            assertNotNull(storage.getMember("B", MetServiceType.Provider, "one"));
            ServiceSummary summary = storage.serviceSummaries(List.of("A")).get("A");
            assertEquals(0, summary.getProviderCount());
            assertEquals(1, summary.getConsumerCount());
            assertArrayEquals(new String[] { "http" }, summary.getProtocols());
        }
    }

    @Test
    public void serviceWithdrawalKeepsCatalogAndOtherRegistrations() throws Exception {
        // 准备一个消费者和一个无关提供者。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess storage = configuration.openStorage()) {
            storage.registerInstance(instance("one", "orders", "east"));
            storage.registerService("one", service("A", MetServiceType.Consumer));
            storage.registerService("one", service("B", MetServiceType.Provider, "rsf://127.0.0.1:8000"));

            // 撤销 A 只清理它的两侧登记，持久服务目录及 B 均保留。
            storage.unregisterService("one", "A");
            RecordPage<EntServiceRuntimeData> remaining = storage.instanceServices("one", new DirectoryQuery());
            assertNull(storage.getInstanceService("one", "A"));
            assertNull(storage.getMember("A", MetServiceType.Consumer, "one"));
            assertNotNull(storage.getService("A"));
            assertNotNull(storage.getMember("B", MetServiceType.Provider, "one"));
            assertEquals(1, remaining.getItems().size());
        }
    }

    @Test
    public void applicationPagesFilterBeforeLimitingAndDoNotMixDirectories() throws Exception {
        // 多应用、多单元共存，目标应用有两个 east 实例。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess storage = configuration.openStorage()) {
            storage.registerInstance(instance("a", "orders", "west"));
            storage.registerInstance(instance("b", "orders", "east"));
            storage.registerInstance(instance("c", "orders", "east"));
            storage.registerInstance(instance("d", "other", "east"));

            // 每页只取一个目标实例，筛选在分页之前执行。
            DirectoryQuery query = new DirectoryQuery();
            query.setUnitName("east");
            query.setLimit(1);
            RecordPage<InstanceStatus> first = storage.instances("orders", query);
            String firstId = first.getItems().get(0).getInstance().getInstanceId();
            query.setCursor(first.getNextCursor());
            RecordPage<InstanceStatus> second = storage.instances("orders", query);
            String secondId = second.getItems().get(0).getInstance().getInstanceId();
            assertEquals(Set.of("b", "c"), Set.of(firstId, secondId));
            assertNull(second.getNextCursor());
            RecordPage<EntApplicationCatalogData> applications = storage.applications(new DirectoryQuery());
            assertEquals(2, applications.getItems().size());
        }
    }

    @Test
    public void staleMetadataRevisionCannotOverwriteSavedKeys() throws Exception {
        // 首次保存两个元信息键，保留尚未取得版本的原始对象。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess storage = configuration.openStorage()) {
            EntityRecord<EntMetadataData> first = metadata();
            first.getData().getMetadata().putAll(Map.of("a", 1, "b", 2));
            storage.saveMetadata(null, "A", first);

            // 过期版本必须报冲突，整批键值保持原样。
            assertThrows(ConcurrentModificationException.class, () -> storage.saveMetadata(null, "A", first));
            EntityRecord<EntMetadataData> saved = storage.getMetadata(null, "A");
            assertEquals(Map.of("a", 1, "b", 2), saved.getData().getMetadata());
        }
    }

    @Test
    public void ruleWriteDoesNotChangeMetadataRevision() throws Exception {
        // 元信息和路由规则属于独立存储正文。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess storage = configuration.openStorage()) {
            storage.saveMetadata(null, "A", metadata());
            EntityRecord<EntMetadataData> previous = storage.getMetadata(null, "A");

            // 写入规则不能让元信息产生无关版本变化。
            EntityRecord<EntRuleConfigurationData> rule = new EntityRecord<>();
            rule.setData(new EntRuleConfigurationData());
            rule.getData().setRule("<route/>");
            storage.saveRule(null, "A", RuleKind.SERVICE_ROUTE, rule);
            EntityRecord<EntMetadataData> unchanged = storage.getMetadata(null, "A");
            assertEquals(previous.getRevision(), unchanged.getRevision());
        }
    }

    @Test
    public void metadataQueryReturnsDetachedData() throws Exception {
        // 写入完整元信息，再取得一份供调用方读取的正文。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess storage = configuration.openStorage()) {
            EntityRecord<EntMetadataData> value = metadata();
            value.getData().getMetadata().putAll(Map.of("a", 1, "b", 2));
            storage.saveMetadata(null, "A", value);
            EntityRecord<EntMetadataData> read = storage.getMetadata(null, "A");

            // 改动读取结果不等于写回存储。
            read.getData().getMetadata().clear();
            EntityRecord<EntMetadataData> unchanged = storage.getMetadata(null, "A");
            assertEquals(2, unchanged.getData().getMetadata().size());
        }
    }

    @Test
    public void serviceMetadataIsSeparateFromInstanceServiceMetadata() throws Exception {
        // 服务级元信息不冒充实例加服务范围的独立记录。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess storage = configuration.openStorage()) {
            storage.saveMetadata(null, "A", metadata());
            assertNull(storage.getMetadata("one", "A"));
        }
    }

    @Test
    public void memberQueryReturnsDetachedInstanceAndServiceData() throws Exception {
        // 从服务目录读取完整提供者正文。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess storage = configuration.openStorage()) {
            storage.registerInstance(instance("one", "orders", "east"));
            storage.registerService("one", service("A", MetServiceType.Provider, "rsf://127.0.0.1:8000"));
            RefServiceRelationData read = storage.getMember("A", MetServiceType.Provider, "one");

            // 修改实例和服务两部分都不能污染后续查询。
            read.getInstance().setIpAddress("changed");
            read.getService().setAddresses(new String[0]);
            RefServiceRelationData unchanged = storage.getMember("A", MetServiceType.Provider, "one");
            assertEquals("127.0.0.1", unchanged.getInstance().getIpAddress());
            assertEquals(1, unchanged.getService().getAddresses().length);
        }
    }

    @Test
    public void summaryTracksProviderWithdrawal() throws Exception {
        // 登记一个提供者，汇总应与当前登记一致。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess storage = configuration.openStorage()) {
            storage.registerInstance(instance("one", "orders", "east"));
            storage.registerService("one", service("A", MetServiceType.Provider, "rsf://127.0.0.1:8000"));
            ServiceSummary before = storage.serviceSummaries(List.of("A")).get("A");
            assertEquals(1, before.getProviderCount());

            // 撤销后服务档案仍在，但当前提供者数归零。
            storage.unregisterService("one", "A");
            ServiceSummary after = storage.serviceSummaries(List.of("A")).get("A");
            assertEquals(0, after.getProviderCount());
        }
    }

    @Test
    public void summaryCountsEachMemberOnceAcrossMultipleProtocols() throws Exception {
        // 每个成员都挂载两个协议，提供者和消费者仍各只有一个实例。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess storage = configuration.openStorage()) {
            storage.registerInstance(instance("provider", "orders", "east"));
            storage.registerInstance(instance("consumer", "orders", "west"));
            EntServiceRuntimeData provider = service("A", MetServiceType.Provider);
            RefProtocolMountData http = new RefProtocolMountData();
            http.setProtocol("http");
            http.setProtocolName("http");
            http.setPublishedAddress("http://127.0.0.1:8001");
            provider.setEndpoints(new RefProtocolMountData[] { rsfEndpoint(), http });
            storage.registerService("provider", provider);
            EntServiceRuntimeData consumer = service("A", MetServiceType.Consumer);
            consumer.setUsedProtocols(new String[] { "rsf", "http" });
            storage.registerService("consumer", consumer);

            // 协议索引的行数不影响成员汇总，协议集合也不重复。
            ServiceSummary summary = storage.serviceSummaries(List.of("A")).get("A");
            assertEquals(1, summary.getProviderCount());
            assertEquals(1, summary.getConsumerCount());
            assertArrayEquals(new String[] { "http", "rsf" }, summary.getProtocols());
        }
    }

    @Test
    public void consumerFiltersUseSelectedAddressProtocol() throws Exception {
        // 消费者配置 rsf 挂载，但地址本实际选择 http。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess storage = configuration.openStorage()) {
            storage.registerInstance(instance("one", "orders", "east"));
            EntServiceRuntimeData provider = service("A", MetServiceType.Provider);
            provider.setEndpoints(new RefProtocolMountData[] { rsfEndpoint() });
            storage.registerService("one", provider);
            EntServiceRuntimeData consumer = service("B", MetServiceType.Consumer);
            consumer.setUsedProtocols(new String[] { "http" });
            consumer.setEndpoints(new RefProtocolMountData[] { rsfEndpoint() });
            storage.registerService("one", consumer);

            // 实例服务清单和消费者列表均按实际协议筛选。
            DirectoryQuery query = new DirectoryQuery();
            query.setProtocol("http");
            query.setLimit(1);
            RecordPage<EntServiceRuntimeData> services = storage.instanceServices("one", query);
            assertEquals("B", services.getItems().get(0).getServiceId());
            RecordPage<RefServiceRelationData> selected = storage.members("B", MetServiceType.Consumer, query);
            assertEquals(1, selected.getItems().size());
            query.setProtocol("rsf");
            RecordPage<RefServiceRelationData> notSelected = storage.members("B", MetServiceType.Consumer, query);
            assertTrue(notSelected.getItems().isEmpty());
        }
    }

    @Test
    public void providerFiltersApplyBeforePagination() throws Exception {
        // 同一个服务由两个单元提供，每个实例都有两个 rsf 协议挂载。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess storage = configuration.openStorage()) {
            storage.registerInstance(instance("one", "orders", "east"));
            storage.registerInstance(instance("two", "orders", "west"));
            EntServiceRuntimeData provider = service("A", MetServiceType.Provider);
            RefProtocolMountData secondary = rsfEndpoint();
            secondary.setProtocolName("rsf-backup");
            secondary.setPublishedAddress("rsf://127.0.0.1:9000");
            provider.setEndpoints(new RefProtocolMountData[] { rsfEndpoint(), secondary });
            storage.registerService("one", provider);
            storage.registerService("two", provider);

            // 协议、单元及不区分大小写的应用条件都在取页前应用。
            DirectoryQuery query = new DirectoryQuery();
            query.setProtocol("rsf");
            query.setLimit(1);
            query.setUnitName("east");
            query.setField("application");
            query.setSearch("ORDERS");
            RecordPage<RefServiceRelationData> selected = storage.members("A", MetServiceType.Provider, query);
            assertEquals(1, selected.getItems().size());
            String instanceId = selected.getItems().get(0).getInstance().getInstanceId();
            assertEquals("one", instanceId);
            assertNull(selected.getNextCursor());
            RecordPage<EntServiceRuntimeData> declarations = storage.instanceServices("one", new DirectoryQuery());
            assertEquals(1, declarations.getItems().size());
        }
    }

    @Test
    public void applicationUnitsIncludeEveryRegisteredUnit() throws Exception {
        // 应用内两个单元都应出现在单元目录中。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess storage = configuration.openStorage()) {
            storage.registerInstance(instance("one", "orders", "east"));
            storage.registerInstance(instance("two", "orders", "west"));
            assertEquals(List.of("east", "west"), List.of(storage.applicationUnits("orders")));
        }
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.nacos;
import java.util.ConcurrentModificationException;
import java.util.Map;
import java.util.Set;
import com.alibaba.nacos.api.naming.pojo.Instance;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.DirectoryQuery;
import net.hasor.meteor.registry.shared.storage.domain.EntServiceCatalogData;
import net.hasor.meteor.registry.shared.utils.RegistryJson;
import org.junit.Test;
import static net.hasor.meteor.registry.nacos.NacosTestData.instance;
import static net.hasor.meteor.registry.nacos.NacosTestData.service;
import static org.junit.Assert.*;

public class NacosLayoutTest {
    @Test
    public void instanceIdentityUsesAValidNacosClusterName() throws Exception {
        // 业务实例 ID 包含 Nacos 3.1.2 集群名不接受的分隔字符。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            String instanceId = "node_with.dot";
            storage.registerInstance(instance(instanceId));

            // 集群名只含字母、数字和连字符，原始 ID 仍由元信息完整保存和查询。
            Instance stored = sdk.nodes.get("instances.node_5fwith_2edot").values().iterator().next();
            assertTrue(stored.getClusterName().matches("[A-Za-z0-9-]{1,64}"));
            assertEquals(instanceId, stored.getMetadata().get("instanceId"));
            assertNotNull(storage.getInstance(instanceId));
        }
    }

    @Test
    public void applicationAndServiceBodiesHaveDirectKeys() throws Exception {
        // 通过公开存储入口完成一次应用与服务登记。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.registerInstance(instance("node"));
            storage.registerService("node", service("echo"));

            // 主记录独立存放，实例信息不写入 Config。
            Map<?, ?> application = RegistryJson.read(sdk.documents.get("applications.orders"), Map.class);
            Map<?, ?> service = RegistryJson.read(sdk.documents.get("services.echo"), Map.class);
            assertEquals("orders", application.get("applicationName"));
            assertEquals("echo", service.get("serviceId"));
            String applicationIndex = "index.applications." + Math.floorMod("orders".hashCode(), 16);
            String serviceIndex = "index.services." + Math.floorMod("echo".hashCode(), 16);
            Set<String> configKeys = Set.of("applications.orders", "services.echo", applicationIndex, serviceIndex);
            assertEquals(configKeys, sdk.documents.keySet());

            // 应用索引保留业务 ID，并指向完整名称的主记录。
            Map<?, ?> applicationReference = RegistryJson.read(sdk.documents.get(applicationIndex), Map.class);
            assertEquals(Map.of("id", "orders", "key", "applications.orders"), applicationReference);
        }
    }

    @Test
    public void indexesOnlyContainJsonlReferences() throws Exception {
        // Aa 与 BB 的 Java hashCode 相同，落在同一辅助索引中。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.saveService(service("Aa"));
            storage.saveService(service("BB"));

            // 每行仅指向一个独立主记录，不把正文包在分页结构中。
            String key = "index.services." + Math.floorMod("Aa".hashCode(), 16);
            String[] lines = sdk.documents.get(key).split("\n");
            assertEquals(2, lines.length);
            for (String line : lines) {
                Map<?, ?> reference = RegistryJson.read(line, Map.class);
                assertEquals(Set.of("id", "key"), reference.keySet());
                assertEquals("services." + reference.get("id"), reference.get("key"));
                assertNotNull(sdk.documents.get(reference.get("key")));
            }
        }
    }

    @Test
    public void directReadDoesNotDependOnIndexAvailability() throws Exception {
        // 模拟辅助索引尚未补齐，主记录已经写入成功。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.saveService(service("echo"));
            sdk.documents.keySet().removeIf(key -> key.startsWith("index."));

            // 已知 ID 的读取直接访问主记录，不依赖索引或 UI。
            assertEquals("echo", storage.getService("echo").getServiceId());
        }
    }

    @Test
    public void retryRepairsAnIndexAfterPrimaryWriteSucceeds() throws Exception {
        // 主记录写入成功后，索引 CAS 连续失败。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            EntServiceCatalogData service = service("echo");
            sdk.conflictPrefix = "index.services.";
            sdk.conflicts = 8;
            assertThrows(ConcurrentModificationException.class, () -> storage.saveService(service));
            assertNotNull(storage.getService("echo"));

            // 重试同一登记补齐索引，不产生第二份服务正文。
            storage.saveService(service);
            assertEquals(1, storage.services(new DirectoryQuery()).getItems().size());
        }
    }

    @Test
    public void identifierEscapingDoesNotCollideWithLiteralEscapeText() throws Exception {
        // 点号与下划线必须区分，主 Key 不使用不可读的哈希摘要。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.saveService(service("a.b"));
            storage.saveService(service("a_2eb"));

            // 两个原始 ID 对应不同的可逆编码，分别读取原始值。
            assertTrue(sdk.documents.containsKey("services.a_2eb"));
            assertTrue(sdk.documents.containsKey("services.a_5f2eb"));
            assertEquals("a.b", storage.getService("a.b").getServiceId());
            assertEquals("a_2eb", storage.getService("a_2eb").getServiceId());
        }
    }

    @Test
    public void runtimeNodesStoreReferencesInsteadOfRepeatedEntityBodies() throws Exception {
        // 一次运行具有实例、应用成员、服务成员以及两种实例治理节点。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.registerInstance(instance("node"));
            storage.registerService("node", service("echo"));

            // 关系只保存自身属性，主实体正文仅出现在对应主节点。
            Set<String> keys = Set.of("instances.node", "applications.orders.instances", "services.echo.providers", //
                    "governance.instances.node", "governance.services.echo.instances.node");
            assertEquals(keys, sdk.nodes.keySet());
            Instance membership = sdk.nodes.get("services.echo.providers").get("node");
            Map<?, ?> body = RegistryJson.read(membership.getMetadata().get("body"), Map.class);
            assertEquals(Set.of("enabled", "endpoints", "addresses", "usedProtocols"), body.keySet());
            Instance applicationMember = sdk.nodes.get("applications.orders.instances").get("node");
            assertEquals(Map.of("instanceId", "node"), applicationMember.getMetadata());
        }
    }

    @Test
    public void repeatedRestartsDoNotAccumulatePersistentInstanceRecords() throws Exception {
        // 每次启动有新的实例 ID，但应用与服务身份保持不变。
        try (MockNacosSdk sdk = new MockNacosSdk()) {
            for (int run = 0; run < 100; run++) {
                try (StorageAccess storage = sdk.configuration().openStorage()) {
                    String id = "node-" + run;
                    storage.registerInstance(instance(id));
                    storage.registerService(id, service("echo"));
                }
            }

            // Config 只留下两个定义和两个辅助索引，所有临时成员均已退出。
            assertEquals(4, sdk.documents.size());
            for (Map<String, Instance> nodes : sdk.nodes.values()) {
                assertTrue(nodes.isEmpty());
            }
        }
    }

    @Test
    public void sdkDisconnectRemovesRuntimeWithoutExplicitWithdrawal() throws Exception {
        // 模拟应用连接断开，未执行 StorageAccess.close 的主动撤回。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess writer = sdk.configuration().openStorage(); //
             StorageAccess reader = sdk.configuration().openStorage()) {
            writer.registerInstance(instance("node"));
            writer.registerService("node", service("echo"));
            sdk.namingClients.get(0).shutDown();

            // 运行数据随连接清除，持久定义不受影响。
            assertNull(reader.getInstance("node"));
            assertTrue(reader.instances("orders", new DirectoryQuery()).getItems().isEmpty());
            assertNotNull(reader.getService("echo"));
        }
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.nacos;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class NacosStorageWatchTest {
    @Test
    public void nativeConfigNotificationsDiscoverNewShardsFilterQueriesAndCancel() throws Exception {
        // 订阅持久服务记录，新增分片也必须通过原生通知发现。
        try (NacosTestServer server = new NacosTestServer()) {
            NacosRegistryConfiguration configuration = server.configuration();
            try (StorageAccess writer = configuration.openStorage(); //
                 StorageAccess reader = configuration.openStorage()) {
                EntityQuery query = new EntityQuery();
                query.setType(EntityType.SERVICE);
                query.setIds(Set.of("echo"));
                query.setLimit(1);
                query.setCursor("ignored");
                AtomicInteger notifications = new AtomicInteger();
                Cancellable watch = reader.watchEntities(query, notifications::incrementAndGet);
                query.setType(EntityType.INSTANCE);
                await(() -> notifications.get() == 1);

                // 其他服务变化不会交付本订阅；目标服务的新分片必须自动发现。
                writer.saveEntity(new EntityRecord(EntityType.SERVICE, "unrelated"));
                EntityRecord echo = writer.saveEntity(new EntityRecord(EntityType.SERVICE, "echo"));
                await(() -> notifications.get() == 2);

                // 留出通知收尾时间，再观察健康订阅不会退化为内容轮询。
                Thread.sleep(100);
                int reads = server.reads.get();
                Thread.sleep(1200);
                assertEquals("Healthy subscriptions must not poll record content", reads, server.reads.get());

                // 丢失通知时保持旧视图，补发通知后再应用新版本。
                server.notify = false;
                EntServiceCatalogData configurationData = new EntServiceCatalogData();
                configurationData.setName("changed");
                echo.setData(configurationData);
                EntityRecord updated = writer.saveEntity(echo);
                Thread.sleep(1200);
                assertEquals(2, notifications.get());
                server.notify = true;
                for (String name : server.configListeners.keySet()) {
                    server.configChanged(name);
                }
                await(() -> notifications.get() == 3);
                writer.deleteEntity(updated);
                await(() -> notifications.get() == 4);

                // 取消订阅释放已成功注册的 SDK 监听器。
                assertTrue(watch.cancel());
                assertFalse(watch.cancel());
                assertEquals(0, server.listenerCount());
                writer.saveEntity(new EntityRecord(EntityType.SERVICE, "echo"));
                Thread.sleep(200);
                assertEquals(4, notifications.get());
            }
        }
    }

    @Test
    public void nativeNamingNotificationsCoverNewNamesUpdatesExpiryAndRecovery() throws Exception {
        try (NacosTestServer server = new NacosTestServer()) {
            NacosRegistryConfiguration configuration = server.configuration();
            try (StorageAccess writer = configuration.openStorage(); //
                 StorageAccess reader = configuration.openStorage()) {
                EntityRef source = new EntityRef(EntityType.INSTANCE, "node");
                EntityRef target = new EntityRef(EntityType.SERVICE, "echo");
                RelationQuery query = new RelationQuery();
                query.setSource(source);
                query.setTarget(target);
                AtomicInteger notifications = new AtomicInteger();
                reader.watchRelations(query, notifications::incrementAndGet);
                await(() -> notifications.get() == 1);
                RelationRecord relation = new RelationRecord(RelationType.PROVIDES, source, target);
                relation.setLifecycle(RecordLifecycle.EPHEMERAL);
                writer.saveRelation(relation);
                await(() -> notifications.get() == 2);
                RefServiceRelationData relationData = new RefServiceRelationData();
                relationData.setAddresses(new String[] { "rsf://localhost:2181/changed" });
                relation.setData(relationData);
                writer.saveRelation(relation);
                await(() -> notifications.get() == 3);

                // 健康状态变化通过原生 Naming 通知传递，不通过主动拉取补齐。
                for (var nodes : server.nodes.values()) {
                    for (var node : nodes.values()) {
                        node.setHealthy(false);
                    }
                }
                for (String name : server.namingListeners.keySet()) {
                    server.namingChanged(name, false);
                }
                await(() -> notifications.get() == 4);

                // 断开期间保留原有视图，恢复后补发通知。
                server.available = false;
                Thread.sleep(1300);
                assertEquals(4, notifications.get());
                server.available = true;
                await(() -> notifications.get() == 5);
                writer.saveRelation(relation);
                await(() -> notifications.get() == 6);
                writer.close();
                await(() -> notifications.get() == 7);
                reader.close();
                assertEquals(0, server.listenerCount());
                assertThrows(IllegalStateException.class, () -> reader.watchRelations(query, () -> {
                }));
            }
        }
    }

    @Test
    public void partialRegistrationAndFailedCallbacksRetryAndReleaseNativeListeners() throws Exception {
        // 订阅安装中途失败后可恢复，取消时释放已经安装的原生监听器。
        try (NacosTestServer server = new NacosTestServer()) {
            NacosRegistryConfiguration configuration = server.configuration();
            server.rejectConfigListener = true;
            try (StorageAccess reader = configuration.openStorage()) {
                EntityQuery query = new EntityQuery();
                query.setType(EntityType.INSTANCE);
                AtomicInteger attempts = new AtomicInteger();
                Cancellable watch = reader.watchEntities(query, () -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("callback failed");
                    }
                });
                await(() -> server.listenerCount() > 0);
                assertEquals(0, attempts.get());
                server.rejectConfigListener = false;
                await(() -> attempts.get() == 2);

                // 取消订阅释放已成功注册的 SDK 监听器。
                assertTrue(watch.cancel());
                assertEquals(0, server.listenerCount());
            }
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue("Nacos watch did not converge", condition.getAsBoolean());
    }
}

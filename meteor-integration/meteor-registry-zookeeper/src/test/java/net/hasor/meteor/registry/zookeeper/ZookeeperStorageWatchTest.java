/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.zookeeper;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.StorageKeyUtils;
import net.hasor.meteor.registry.shared.storage.domain.*;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingServer;
import org.junit.Test;
import static org.junit.Assert.*;

public class ZookeeperStorageWatchTest {
    private ZookeeperRegistryConfiguration configuration(String address) {
        ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
        configuration.setAddress(address);
        return configuration;
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue("ZooKeeper watch did not converge", condition.getAsBoolean());
    }

    @Test
    public void missingDirectoryCreationUpdatesDeletionFilteringAndCancellation() throws Exception {
        try (TestingServer server = new TestingServer(); //
             StorageAccess writer = this.configuration(server.getConnectString()).openStorage(); //
             StorageAccess reader = this.configuration(server.getConnectString()).openStorage()) {
            EntityQuery query = new EntityQuery();
            query.setType(EntityType.SERVICE);
            query.setIds(Set.of("echo"));
            query.setCursor("ignored");
            query.setLimit(1);
            AtomicInteger events = new AtomicInteger();
            Cancellable watch = reader.watchEntities(query, events::incrementAndGet);
            query.setType(EntityType.INSTANCE);
            await(() -> events.get() == 1);

            // 无关服务不通知，目标服务的创建、修改和删除按顺序交付。
            writer.saveEntity(new EntityRecord(EntityType.SERVICE, "other"));
            EntityRecord record = writer.saveEntity(new EntityRecord(EntityType.SERVICE, "echo"));
            await(() -> events.get() == 2);
            EntServiceCatalogData serviceData = new EntServiceCatalogData();
            serviceData.setName("new");
            record.setData(serviceData);
            EntityRecord updated = writer.saveEntity(record);
            await(() -> events.get() == 3);
            writer.deleteEntity(updated);
            await(() -> events.get() == 4);
            writer.saveEntity(new EntityRecord(EntityType.SERVICE, "echo"));
            await(() -> events.get() == 5);

            // 取消监听后保留观察窗口，确认后续变更不会继续通知。
            assertTrue(watch.cancel());
            assertFalse(watch.cancel());
            writer.deleteEntity(writer.getEntity(new EntityRef(EntityType.SERVICE, "echo")));
            Thread.sleep(200);
            assertEquals(5, events.get());
            reader.close();
            assertThrows(IllegalStateException.class, () -> reader.watchEntities(query, () -> {
            }));
        }
    }

    @Test
    public void relationIndexRepairAndSessionCloseNotify() throws Exception {
        // 原生订阅同时观察关系正文与索引，修复索引后恢复查询结果。
        ExponentialBackoffRetry retry = new ExponentialBackoffRetry(50, 3);
        try (TestingServer server = new TestingServer(); //
             CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), retry)) {
            client.start();
            assertTrue("ZooKeeper session did not connect", client.blockUntilConnected(5, TimeUnit.SECONDS));
            ZookeeperRegistryConfiguration configuration = this.configuration(server.getConnectString());
            configuration.setRoot("/watch");
            // 通过独立 SDK 会话删除索引，存储会话仍由正式配置创建。
            try (StorageAccess writer = configuration.openStorage(); //
                 StorageAccess reader = configuration.openStorage()) {
                EntityRef source = new EntityRef(EntityType.INSTANCE, "node");
                EntityRef target = new EntityRef(EntityType.SERVICE, "echo");
                RelationQuery query = new RelationQuery();
                query.setSource(source);
                query.setTarget(target);
                AtomicInteger events = new AtomicInteger();
                reader.watchRelations(query, events::incrementAndGet);
                await(() -> events.get() == 1);
                RelationRecord input = new RelationRecord(RelationType.PROVIDES, source, target);
                input.setLifecycle(RecordLifecycle.EPHEMERAL);
                writer.saveRelation(input);
                await(() -> events.get() == 2);
                String sourceKey = StorageKeyUtils.entity(source);
                String relationKey = StorageKeyUtils.relation(RelationType.PROVIDES, source, target);
                String index = "/watch/records-v2/index/source/" + sourceKey + "/PROVIDES/" + relationKey;

                // 独立 SDK 删除索引，订阅应感知查询结果变化。
                client.delete().forPath(index);
                await(() -> events.get() == 3);
                writer.saveRelation(input);
                await(() -> events.get() == 4);
                writer.close();
                await(() -> events.get() == 5);
            }
        }
    }

    @Test
    public void reconnectWithUnchangedDataAndCallbackFailureRetry() throws Exception {
        // 故障注入需要精确控制 SDK 会话，重连后即使数据未变也要重新通知。
        ExponentialBackoffRetry retry = new ExponentialBackoffRetry(50, 3);
        try (TestingServer server = new TestingServer(); //
             CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), 10000, 2000, retry)) {
            client.start();
            assertTrue("ZooKeeper session did not connect", client.blockUntilConnected(5, TimeUnit.SECONDS));
            try (StorageAccess reader = new ZookeeperRegistry(client, "/reconnect")) {
                EntityQuery query = new EntityQuery();
                query.setType(EntityType.SERVICE);
                AtomicInteger attempts = new AtomicInteger();
                Cancellable failed = reader.watchEntities(query, () -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("callback failed");
                    }
                });
                await(() -> attempts.get() == 2);
                assertTrue(failed.cancel());

                // 换成正常订阅，单独验证断线重连时数据未变也会补发通知。
                AtomicInteger events = new AtomicInteger();
                reader.watchEntities(query, events::incrementAndGet);
                await(() -> events.get() == 1);
                CountDownLatch disconnected = new CountDownLatch(1);
                client.getConnectionStateListenable().addListener((c, state) -> {
                    if (state == ConnectionState.SUSPENDED || state == ConnectionState.LOST) {
                        disconnected.countDown();
                    }
                });

                // 停止服务端并等待连接状态变化，再验证重连补偿。
                server.stop();
                assertTrue(disconnected.await(10, TimeUnit.SECONDS));
                assertEquals(1, events.get());
                server.restart();
                await(() -> events.get() >= 2);
            }
        }
    }
}

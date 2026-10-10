/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.jdbc;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.dbvisitor.jdbc.core.JdbcTemplate;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import org.junit.Before;
import org.junit.Test;
import static net.hasor.meteor.registry.jdbc.JdbcTestSupport.configuration;
import static net.hasor.meteor.registry.jdbc.JdbcTestSupport.createDatabase;
import static org.junit.Assert.*;

public class JdbcStorageWatchTest {
    private String                    url;
    private JdbcRegistryConfiguration configuration;

    @Before
    public void initialize() throws Exception {
        this.url = createDatabase();
        this.configuration = configuration(this.url);
    }

    private void renameRecords(String from, String to) throws Exception {
        try (Connection connection = DriverManager.getConnection(this.url)) {
            new JdbcTemplate(connection).executeUpdate("ALTER TABLE " + from + " RENAME TO " + to);
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue("JDBC watch did not converge", condition.getAsBoolean());
    }

    @Test
    public void initialSnapshotAllPagesQueryIsolationUpdatesAndDeletion() throws Exception {
        // 准备跨页记录，订阅读取完整结果并固定订阅时的查询范围。
        try (StorageAccess publisher = this.configuration.openStorage(); //
             StorageAccess observer = this.configuration.openStorage()) {
            for (int i = 0; i < 1002; i++) {
                publisher.saveEntity(new EntityRecord(EntityType.SERVICE, "service-" + i));
            }
            EntityQuery query = new EntityQuery();
            query.setType(EntityType.SERVICE);
            query.setLimit(1);
            query.setCursor("ignored-for-subscription");
            AtomicInteger notifications = new AtomicInteger();
            Cancellable subscription = observer.watchEntities(query, notifications::incrementAndGet);
            query.setType(EntityType.INSTANCE);
            await(() -> notifications.get() == 1);

            // 首轮包含所有分页；随后修改和删除目标记录应分别产生通知。
            EntityRecord record = publisher.getEntity(new EntityRef(EntityType.SERVICE, "service-999"));
            EntServiceCatalogData serviceData = new EntServiceCatalogData();
            serviceData.setName("changed");
            record.setData(serviceData);
            publisher.saveEntity(record);
            await(() -> notifications.get() == 2);
            publisher.deleteEntity(publisher.getEntity(new EntityRef(EntityType.SERVICE, "service-999")));
            await(() -> notifications.get() == 3);

            // 取消后即使继续写入，也不会再收到通知。
            assertTrue(subscription.cancel());
            assertFalse(subscription.cancel());
            publisher.saveEntity(new EntityRecord(EntityType.SERVICE, "after-cancel"));
            Thread.sleep(1200);
            assertEquals(3, notifications.get());
        }
    }

    @Test
    public void relationRemovalSessionLossAndTransientFailuresNotify() throws Exception {
        // 订阅临时发布关系，验证数据库故障恢复与发布者退出。
        try (StorageAccess publisher = this.configuration.openStorage(); //
             StorageAccess observer = this.configuration.openStorage()) {
            EntityRef source = new EntityRef(EntityType.INSTANCE, "node");
            EntityRef target = new EntityRef(EntityType.SERVICE, "echo");
            RelationQuery query = new RelationQuery();
            query.setType(RelationType.PROVIDES);
            query.setTarget(target);
            AtomicInteger notifications = new AtomicInteger();
            observer.watchRelations(query, notifications::incrementAndGet);
            await(() -> notifications.get() == 1);
            RelationRecord record = new RelationRecord(RelationType.PROVIDES, source, target);
            record.setLifecycle(RecordLifecycle.EPHEMERAL);
            publisher.saveRelation(record);
            await(() -> notifications.get() == 2);

            // 暂时让底层读表失败，不应被误判为记录删除。
            this.renameRecords("meteor_records", "offline_records");
            try {
                Thread.sleep(1300);
                assertEquals(2, notifications.get());
            } finally {
                this.renameRecords("offline_records", "meteor_records");
            }
            await(() -> notifications.get() == 3);

            // 发布会话关闭后临时关系消失，观察会话仍继续工作。
            publisher.close();
            await(() -> notifications.get() == 4);
            observer.close();
            assertThrows(IllegalStateException.class, () -> observer.watchRelations(query, () -> {
            }));
        }
    }

    @Test
    public void listenerFailureRetriesWithoutKillingOtherSubscriptions() throws Exception {
        // 一个订阅回调失败不会阻断其他订阅，失败回调可在下一轮恢复。
        try (StorageAccess observer = this.configuration.openStorage()) {
            EntityQuery query = new EntityQuery();
            query.setType(EntityType.INSTANCE);
            AtomicInteger attempts = new AtomicInteger();
            AtomicInteger other = new AtomicInteger();
            observer.watchEntities(query, () -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("callback failure");
                }
            });
            observer.watchEntities(query, other::incrementAndGet);
            await(() -> attempts.get() == 2 && other.get() == 1);
        }
    }
}

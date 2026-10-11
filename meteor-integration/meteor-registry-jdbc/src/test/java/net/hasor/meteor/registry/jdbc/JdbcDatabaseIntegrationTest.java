/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.jdbc;
import java.sql.Connection;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import net.hasor.dbvisitor.jdbc.core.JdbcTemplate;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Test;
import static net.hasor.meteor.registry.jdbc.JdbcTestSupport.initialize;
import static org.junit.Assert.*;

/** 使用 H2 验证建表、租约和多会话存储契约，不依赖外部数据库。 */
public class JdbcDatabaseIntegrationTest {
    @Test
    public void schemaLeaseRecordsAndConcurrentWrites() throws Exception {
        // 应用创建 H2 数据源并执行正式建表脚本，保留连接以维持内存库生命周期。
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID());
        JdbcRegistryConfiguration configuration = new JdbcRegistryConfiguration();
        configuration.setDataSource(dataSource);
        configuration.setGroup(UUID.randomUUID().toString());

        try (Connection connection = dataSource.getConnection()) {
            initialize(connection);
            JdbcTemplate jdbc = new JdbcTemplate(connection);
            this.exercise(configuration, jdbc);
        }
    }

    private void exercise(JdbcRegistryConfiguration configuration, JdbcTemplate jdbc) throws Exception {
        // 租约是应用时钟计算的毫秒数，不依赖数据库时间函数。
        String id = "服务/".repeat(2000);
        String body = "{\"text\":\"" + "正文数据".repeat(20000) + "\"}";
        EntityRef service = new EntityRef(EntityType.SERVICE, id);
        EntityRef instance = new EntityRef(EntityType.INSTANCE, "实例");
        long before = System.currentTimeMillis();
        try (StorageAccess first = configuration.openStorage(); //
             StorageAccess second = configuration.openStorage()) {
            List<Long> leases = jdbc.queryForList("SELECT expires_at FROM meteor_sessions", (rs, row) -> rs.getLong(1));
            long after = System.currentTimeMillis();
            assertEquals(2, leases.size());
            long earliestExpiry = before + 15000;
            long latestExpiry = after + 15000;
            for (long expiresAt : leases) {
                assertTrue(expiresAt >= earliestExpiry);
                assertTrue(expiresAt <= latestExpiry);
            }

            // 长 Unicode 正文和标识可持久化，旧版本不能更新或删除。
            EntityRecord serviceRecord = new EntityRecord(service.type(), id);
            EntServiceCatalogData serviceData = new EntServiceCatalogData();
            serviceData.setName(body);
            serviceRecord.setData(serviceData);
            EntityRecord original = first.saveEntity(serviceRecord);
            EntityRecord stale = second.getEntity(service);
            EntServiceCatalogData storedData = (EntServiceCatalogData) stale.getData();
            assertEquals(body, storedData.getName());
            original.setData(null);
            first.saveEntity(original);
            assertThrows(ConcurrentModificationException.class, () -> second.saveEntity(stale));
            assertThrows(ConcurrentModificationException.class, () -> second.deleteEntity(stale));

            // 临时关系支持分页，其他会话不能删除当前实例的记录。
            EntityRecord instanceRecord = new EntityRecord(instance.type(), instance.id());
            instanceRecord.setLifecycle(RecordLifecycle.EPHEMERAL);
            first.saveEntity(instanceRecord);
            RelationRecord provider = new RelationRecord(RelationType.PROVIDES, instance, service);
            provider.setLifecycle(RecordLifecycle.EPHEMERAL);
            first.saveRelation(provider);
            RelationRecord consumer = new RelationRecord(RelationType.CONSUMES, instance, service);
            consumer.setLifecycle(RecordLifecycle.EPHEMERAL);
            first.saveRelation(consumer);
            RelationQuery firstPageQuery = new RelationQuery();
            firstPageQuery.setSource(instance);
            firstPageQuery.setTarget(service);
            firstPageQuery.setLimit(1);
            RecordPage<RelationRecord> page = second.findRelations(firstPageQuery);
            assertNotNull(page.nextCursor());
            RelationQuery nextPageQuery = new RelationQuery();
            nextPageQuery.setSource(instance);
            nextPageQuery.setTarget(service);
            nextPageQuery.setLimit(1);
            nextPageQuery.setCursor(page.nextCursor());
            assertEquals(1, second.findRelations(nextPageQuery).records().size());
            EntityRecord ownedInstance = first.getEntity(instance);
            assertThrows(IllegalStateException.class, () -> second.deleteEntity(ownedInstance));

            // 租约过期后可由新会话认领，原会话退出不能误删新归属。
            jdbc.executeUpdate("UPDATE meteor_sessions SET expires_at=0");
            assertNull(second.getEntity(instance));
            EntityRecord reclaimedInstance = new EntityRecord(instance.type(), instance.id());
            reclaimedInstance.setLifecycle(RecordLifecycle.EPHEMERAL);
            second.saveEntity(reclaimedInstance);
            assertNotNull(second.getEntity(instance));
            this.concurrentCreate(first, second);
            first.close();
            assertNotNull(second.getEntity(instance));
            assertNull(second.getEntity(service).getData());
        }

        // 最后一个会话退出后，仅持久服务记录保留。
        try (StorageAccess reopened = configuration.openStorage()) {
            assertNull(reopened.getEntity(instance));
            assertNotNull(reopened.getEntity(service));
        }
    }

    private void concurrentCreate(StorageAccess first, StorageAccess second) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Callable<Boolean> createFirst = () -> this.create(first, barrier);
            Callable<Boolean> createSecond = () -> this.create(second, barrier);
            Future<Boolean> one = workers.submit(createFirst);
            Future<Boolean> two = workers.submit(createSecond);
            boolean firstCreated = one.get(10, TimeUnit.SECONDS);
            boolean secondCreated = two.get(10, TimeUnit.SECONDS);
            assertNotEquals(firstCreated, secondCreated);
        } finally {
            workers.shutdownNow();
        }
    }

    private boolean create(StorageAccess storage, CyclicBarrier barrier) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        try {
            storage.saveEntity(new EntityRecord(EntityType.SERVICE, "concurrent"));
            return true;
        } catch (ConcurrentModificationException expected) {
            return false;
        }
    }
}

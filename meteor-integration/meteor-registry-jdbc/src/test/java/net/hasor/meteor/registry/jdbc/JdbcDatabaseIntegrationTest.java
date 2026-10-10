/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.jdbc;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import net.hasor.dbvisitor.jdbc.core.JdbcTemplate;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static net.hasor.meteor.registry.jdbc.JdbcTestSupport.driverDataSource;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** 可选真实数据库验证；仅使用专用空数据库，测试创建并删除两张存储表。 */
@RunWith(Parameterized.class)
public class JdbcDatabaseIntegrationTest {
    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> databases() {
        return Arrays.asList(new Object[][] { { "mysql" }, { "postgresql" }, { "oracle" }, { "sqlserver" } });
    }

    private final String database;

    public JdbcDatabaseIntegrationTest(String database) {
        this.database = database;
    }

    @Test
    public void schemaLeaseRecordsAndConcurrentWrites() throws Exception {
        // 仅在明确配置的专用数据库执行建表、租约和并发写入验证。
        String prefix = "METEOR_JDBC_" + this.database.toUpperCase(Locale.ROOT) + "_";
        String url = System.getenv(prefix + "URL");
        assumeTrue("Set " + prefix + "URL for a dedicated empty test database", url != null);
        String user = System.getenv().getOrDefault(prefix + "USER", "");
        String password = System.getenv().getOrDefault(prefix + "PASSWORD", "");
        JdbcRegistryConfiguration configuration = new JdbcRegistryConfiguration();
        configuration.setDataSource(driverDataSource(url, user, password));
        configuration.setGroup(UUID.randomUUID().toString());
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            JdbcTemplate jdbc = new JdbcTemplate(connection);
            String schemaPath = "/META-INF/meteor/schema/" + this.database + ".sql";
            InputStream schema = Objects.requireNonNull(this.getClass().getResourceAsStream(schemaPath));
            try (InputStreamReader script = new InputStreamReader(schema, StandardCharsets.UTF_8)) {
                jdbc.loadSplitSQL(";", script);
            }
            try {
                this.exercise(configuration, jdbc);
            } finally {
                jdbc.execute("DROP TABLE meteor_records");
                jdbc.execute("DROP TABLE meteor_sessions");
            }
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
            for (long expiresAt : leases) {
                assertTrue(expiresAt >= before + 15000 && expiresAt <= after + 15000);
            }

            // 长 Unicode 正文和标识可持久化，旧版本不能更新或删除。
            EntityRecord serviceRecord = new EntityRecord(service.type(), id);
            EntServiceCatalogData serviceData = new EntServiceCatalogData();
            serviceData.setName(body);
            serviceRecord.setData(serviceData);
            EntityRecord original = first.saveEntity(serviceRecord);
            EntityRecord stale = second.getEntity(service);
            assertEquals(body, ((EntServiceCatalogData) stale.getData()).getName());
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
            assertThrows(IllegalStateException.class, () -> second.deleteEntity(first.getEntity(instance)));

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
            assertNotEquals(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
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

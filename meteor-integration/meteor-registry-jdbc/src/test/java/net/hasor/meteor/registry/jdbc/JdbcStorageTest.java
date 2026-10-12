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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import net.hasor.dbvisitor.jdbc.core.JdbcTemplate;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static net.hasor.meteor.registry.jdbc.JdbcTestSupport.*;
import static org.junit.Assert.*;

public class JdbcStorageTest {

    @Rule
    public TemporaryFolder directory = new TemporaryFolder();

    @Test
    public void slowWriteAllowsReadsButCloseWaitsForSessionCleanup() throws Exception {
        // 在首次写入前阻塞驱动，观察查询与关闭是否受在途写入影响。
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(3);
        String url = createDatabase();
        try (JdbcDriverProbe probe = new JdbcDriverProbe(url); //
             StorageAccess store = configuration(probe.url()).openStorage(); //
             StorageAccess observer = configuration(url).openStorage()) {
            EntityRef durable = new EntityRef(EntityType.SERVICE, "existing");
            store.saveEntity(new EntityRecord(durable));
            probe.beforeExecute(sql -> {
                if (sql.startsWith("INSERT INTO meteor_records")) {
                    writing.countDown();
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                }
            });
            EntityRef temporary = new EntityRef(EntityType.INSTANCE, "pending");
            EntityRecord input = new EntityRecord(temporary);
            input.setLifecycle(RecordLifecycle.EPHEMERAL);
            Future<EntityRecord> pending = workers.submit(() -> store.saveEntity(input));
            Future<?> closing;
            try {
                assertTrue(writing.await(5, TimeUnit.SECONDS));

                // 同一存储会话的慢写入不阻塞独立查询。
                EntityRecord found = workers.submit(() -> store.getEntity(durable)).get(5, TimeUnit.SECONDS);
                assertEquals(durable, found.getRef());

                // 关闭必须等在途写入结束，才能完整清理本会话的临时数据。
                CountDownLatch closeStarted = new CountDownLatch(1);
                closing = workers.submit(() -> {
                    closeStarted.countDown();
                    store.close();
                    return null;
                });
                assertTrue(closeStarted.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> closing.get(150, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            pending.get(5, TimeUnit.SECONDS);
            closing.get(5, TimeUnit.SECONDS);
            assertNull(observer.getEntity(temporary));
            assertNotNull(observer.getEntity(durable));
            assertThrows(IllegalStateException.class, () -> store.getEntity(durable));
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    public void sameSessionStillUsesCasForConcurrentCreates() throws Exception {
        try (JdbcDriverProbe probe = new JdbcDriverProbe(createDatabase()); //
             StorageAccess store = configuration(probe.url()).openStorage()) {
            // 同一会话内的两个请求都读到不存在，再由数据库唯一键裁决。
            CyclicBarrier inserting = new CyclicBarrier(2);
            probe.beforeExecute(sql -> {
                if (sql.startsWith("INSERT INTO meteor_records")) {
                    inserting.await(5, TimeUnit.SECONDS);
                }
            });
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Callable<Boolean> create = () -> {
                    try {
                        store.saveEntity(new EntityRecord(EntityType.SERVICE, "concurrent"));
                        return true;
                    } catch (ConcurrentModificationException conflict) {
                        return false;
                    }
                };
                Future<Boolean> first = workers.submit(create);
                Future<Boolean> second = workers.submit(create);
                boolean firstCreated = first.get(10, TimeUnit.SECONDS);
                boolean secondCreated = second.get(10, TimeUnit.SECONDS);
                assertNotEquals(firstCreated, secondCreated);
                assertNotNull(store.getEntity(new EntityRef(EntityType.SERVICE, "concurrent")));
            } finally {
                workers.shutdownNow();
            }
        }
    }

    @Test
    public void parentAndSectionQueriesKeepRulesAndRuntimeIsolated() throws Exception {
        // 同名服务配置归属不同实例；实例配置与运行登记也必须分区隔离。
        try (StorageAccess store = configuration(createDatabase()).openStorage()) {
            EntityRef a = new EntityRef(EntityType.INSTANCE, "a");
            EntityRef b = new EntityRef(EntityType.INSTANCE, "b");
            EntityRef localA = new EntityRef(EntityType.SERVICE, "echo", a, EntitySection.CONFIGURATION);
            EntityRef localB = new EntityRef(EntityType.SERVICE, "echo", b, EntitySection.CONFIGURATION);
            EntityRef runtime = new EntityRef(EntityType.INSTANCE, "a", null, EntitySection.RUNTIME);
            EntityRef ruleA = new EntityRef(EntityType.RULE, "SERVICE_ROUTE", localA, EntitySection.CONFIGURATION);
            EntityRef ruleB = new EntityRef(EntityType.RULE, "SERVICE_ROUTE", localB, EntitySection.CONFIGURATION);
            store.saveEntity(new EntityRecord(a));
            store.saveEntity(new EntityRecord(localA));
            store.saveEntity(new EntityRecord(localB));
            store.saveEntity(new EntityRecord(runtime));
            EntRuleConfigurationData body = new EntRuleConfigurationData();
            body.setRule("<route><return value='addresses'/></route>");
            EntityRecord rule = new EntityRecord(ruleA);
            rule.setData(body);
            store.saveEntity(rule);
            EntRuleConfigurationData loaded = (EntRuleConfigurationData) store.getEntity(ruleA).getData();
            assertEquals(body.getRule(), loaded.getRule());
            store.saveEntity(new EntityRecord(ruleB));

            // 从实例定位服务，再切换运行区域或子规则，不越过父级范围。
            EntityQuery query = new EntityQuery();
            query.setType(EntityType.SERVICE);
            query.setParent(a);
            var services = store.findEntities(query).records();
            assertEquals(1, services.size());
            assertEquals(localA, services.get(0).getRef());
            query.setType(EntityType.INSTANCE);
            query.setParent(null);
            assertEquals(a, store.findEntities(query).records().get(0).getRef());
            query.setSection(EntitySection.RUNTIME);
            assertEquals(runtime, store.findEntities(query).records().get(0).getRef());
            query.setType(EntityType.RULE);
            query.setSection(EntitySection.CONFIGURATION);
            query.setParent(localA);
            var rules = store.findEntities(query).records();
            assertEquals(1, rules.size());
            assertEquals(ruleA, rules.get(0).getRef());
            query.setParent(localB);
            assertEquals(ruleB, store.findEntities(query).records().get(0).getRef());
        }
    }

    @Test
    public void persistentEntitiesAndRelationsSurviveReopenAndSessionCloseIsIsolated() throws Exception {
        // 关闭一个会话仅移除它的临时记录，持久记录和其他会话不受影响。
        String url = "jdbc:h2:" + this.directory.getRoot().toPath().resolve("registry");
        JdbcRegistryConfiguration configuration = configuration(url);
        EntityRef instance = new EntityRef(EntityType.INSTANCE, "node/中文");
        EntityRef service = new EntityRef(EntityType.SERVICE, "echo:1");
        try (Connection connection = DriverManager.getConnection(url, "", "")) {
            initialize(connection);
        }
        try (StorageAccess first = configuration.openStorage(); //
             StorageAccess second = configuration.openStorage()) {
            first.saveEntity(new EntityRecord(service.type(), service.id()));
            EntityRecord instanceRecord = new EntityRecord(instance.type(), instance.id());
            instanceRecord.setLifecycle(RecordLifecycle.EPHEMERAL);
            first.saveEntity(instanceRecord);
            RelationRecord provider = new RelationRecord(RelationType.PROVIDES, instance, service);
            provider.setLifecycle(RecordLifecycle.EPHEMERAL);
            RelationRecord provided = first.saveRelation(provider);
            RelationRecord override = new RelationRecord(RelationType.CONSUMES, instance, service);
            RefServiceRelationData serviceData = new RefServiceRelationData();
            serviceData.setAddresses(new String[] { "rsf://localhost:2181/changed" });
            override.setData(serviceData);
            first.saveRelation(override);
            EntityRecord otherInstance = new EntityRecord(EntityType.INSTANCE, "other");
            otherInstance.setLifecycle(RecordLifecycle.EPHEMERAL);
            EntityRecord other = second.saveEntity(otherInstance);
            assertThrows(IllegalStateException.class, () -> second.saveRelation(provided));
            assertThrows(IllegalStateException.class, () -> second.deleteRelation(provided));

            // 关闭当前会话不会移除其他会话或持久记录。
            first.close();
            assertNull(second.getEntity(instance));
            assertNotNull(second.getEntity(new EntityRef(other.getType(), other.getId())));
            assertNull(second.getRelation(RelationType.PROVIDES, instance, service));
            assertNotNull(second.getRelation(RelationType.CONSUMES, instance, service));
            RelationQuery remainingRelations = new RelationQuery();
            remainingRelations.setSource(instance);
            assertEquals(1, second.findRelations(remainingRelations).records().size());
        }

        // 完整退出后重新打开，持久记录保留，所有临时实例均已移除。
        try (StorageAccess reopened = configuration.openStorage()) {
            assertNotNull(reopened.getEntity(service));
            assertNotNull(reopened.getRelation(RelationType.CONSUMES, instance, service));
            EntityQuery remainingInstances = new EntityQuery();
            remainingInstances.setType(EntityType.INSTANCE);
            assertTrue(reopened.findEntities(remainingInstances).records().isEmpty());
        }
    }

    @Test
    public void identityDirectionPaginationAndScopeAreIndependent() throws Exception {
        // 记录身份、关系方向、分页游标和存储分组分别保持隔离。
        String url = createDatabase();
        JdbcRegistryConfiguration configuration = configuration(url);
        JdbcRegistryConfiguration otherGroup = configuration(url);
        otherGroup.setGroup("other");
        try (StorageAccess store = configuration.openStorage(); //
             StorageAccess isolated = otherGroup.openStorage()) {
            EntityRef source = new EntityRef(EntityType.INSTANCE, "a:b");
            EntityRef target = new EntityRef(EntityType.SERVICE, "c/中文");
            store.saveEntity(new EntityRecord(EntityType.INSTANCE, "same"));
            store.saveEntity(new EntityRecord(EntityType.SERVICE, "same"));
            store.saveRelation(new RelationRecord(RelationType.PROVIDES, source, target));
            store.saveRelation(new RelationRecord(RelationType.CONSUMES, source, target));
            store.saveRelation(new RelationRecord(RelationType.PROVIDES, target, source));
            RelationQuery sourceQuery = new RelationQuery();
            sourceQuery.setSource(source);
            assertEquals(2, store.findRelations(sourceQuery).records().size());
            RelationQuery targetQuery = new RelationQuery();
            targetQuery.setTarget(target);
            assertEquals(2, store.findRelations(targetQuery).records().size());
            RelationQuery providerQuery = new RelationQuery();
            providerQuery.setType(RelationType.PROVIDES);
            providerQuery.setSource(source);
            providerQuery.setTarget(target);
            assertEquals(1, store.findRelations(providerQuery).records().size());

            // 游标只能续读原查询，不能带到另一个端点条件下使用。
            RelationQuery query = new RelationQuery();
            query.setSource(source);
            query.setLimit(1);
            RecordPage<RelationRecord> page = store.findRelations(query);
            assertNotNull(page.nextCursor());
            assertThrows(IllegalArgumentException.class, () -> {
                RelationQuery mismatchedCursorQuery = new RelationQuery();
                mismatchedCursorQuery.setTarget(target);
                mismatchedCursorQuery.setCursor(page.nextCursor());
                store.findRelations(mismatchedCursorQuery);
            });
            query.setCursor(page.nextCursor());
            RecordPage<RelationRecord> next = store.findRelations(query);
            assertNull(next.nextCursor());
            assertNotEquals(page.records().get(0).getType(), next.records().get(0).getType());

            // 其他分组不可见；空 ID 集合与非法分页参数分别验证。
            assertNull(isolated.getEntity(new EntityRef(EntityType.SERVICE, "same")));
            RelationQuery isolatedQuery = new RelationQuery();
            isolatedQuery.setSource(source);
            assertTrue(isolated.findRelations(isolatedQuery).records().isEmpty());
            EntityQuery emptyIdsQuery = new EntityQuery();
            emptyIdsQuery.setType(EntityType.SERVICE);
            emptyIdsQuery.setIds(Set.of());
            assertEquals(0, store.findEntities(emptyIdsQuery).records().size());
            assertThrows(IllegalArgumentException.class, () -> {
                EntityQuery invalidLimitQuery = new EntityQuery();
                invalidLimitQuery.setType(EntityType.SERVICE);
                invalidLimitQuery.setLimit(0);
                store.findEntities(invalidLimitQuery);
            });
        }
    }

    @Test
    public void casRejectsStaleUpdatesDeletesAndRecreatedIdentities() throws Exception {
        // 旧版本不能更新、删除或覆盖已重新创建的同名记录。
        String url = createDatabase();
        JdbcRegistryConfiguration configuration = configuration(url);
        try (StorageAccess first = configuration.openStorage(); //
             StorageAccess second = configuration.openStorage()) {
            EntityRef ref = new EntityRef(EntityType.SERVICE, "echo");
            EntityRecord input = new EntityRecord(ref.type(), ref.id());
            EntityRecord created = first.saveEntity(input);
            assertEquals("", input.getRevision());
            assertThrows(ConcurrentModificationException.class, () -> second.saveEntity(input));
            EntityRecord stale = second.getEntity(ref);
            EntServiceCatalogData configurationData = new EntServiceCatalogData();
            configurationData.getMetadata().put("n", 1);
            created.setData(configurationData);
            EntityRecord current = first.saveEntity(created);
            assertThrows(ConcurrentModificationException.class, () -> second.saveEntity(stale));
            assertThrows(ConcurrentModificationException.class, () -> second.deleteEntity(stale));

            // 删除并重建同一身份后，旧版本仍不可使用。
            assertTrue(first.deleteEntity(current));
            assertFalse(first.deleteEntity(current));
            first.saveEntity(new EntityRecord(ref.type(), ref.id()));
            assertThrows(ConcurrentModificationException.class, () -> second.saveEntity(stale));

            // 关系正文同样受版本约束。
            EntityRef source = new EntityRef(EntityType.INSTANCE, "a");
            RelationRecord relation = first.saveRelation(new RelationRecord(RelationType.CONSUMES, source, ref));
            RelationRecord old = second.getRelation(relation.getType(), relation.getSource(), relation.getTarget());
            RefServiceRelationData relationData = new RefServiceRelationData();
            relationData.setAddresses(new String[] { "rsf://localhost:2181/changed" });
            relation.setData(relationData);
            first.saveRelation(relation);
            assertThrows(ConcurrentModificationException.class, () -> second.deleteRelation(old));
        }
    }

    @Test
    public void expiredLeasesHideTemporaryRecordsAndAllowReclaim() throws Exception {
        // 租约过期仅隐藏临时记录，新的会话可以重新注册。
        String url = createDatabase();
        JdbcRegistryConfiguration configuration = configuration(url);
        try (StorageAccess first = configuration.openStorage(); //
             StorageAccess second = configuration.openStorage()) {
            EntityRef ref = new EntityRef(EntityType.INSTANCE, "expired");
            EntityRecord expiredInstance = new EntityRecord(ref.type(), ref.id());
            expiredInstance.setLifecycle(RecordLifecycle.EPHEMERAL);
            first.saveEntity(expiredInstance);
            first.saveEntity(new EntityRecord(EntityType.SERVICE, "persistent"));
            try (Connection connection = DriverManager.getConnection(url, "", "")) {
                new JdbcTemplate(connection).executeUpdate("UPDATE meteor_sessions SET expires_at=0");
            }
            assertNull(second.getEntity(ref));
            EntityQuery expiredInstances = new EntityQuery();
            expiredInstances.setType(EntityType.INSTANCE);
            assertTrue(second.findEntities(expiredInstances).records().isEmpty());
            EntityRecord reclaimedInstance = new EntityRecord(ref.type(), ref.id());
            reclaimedInstance.setLifecycle(RecordLifecycle.EPHEMERAL);
            second.saveEntity(reclaimedInstance);
            assertNotNull(second.getEntity(ref));
            assertNotNull(second.getEntity(new EntityRef(EntityType.SERVICE, "persistent")));

            // 关闭当前会话不会移除其他会话或持久记录。
            first.close();
            assertNotNull(second.getEntity(ref));
        }
    }

    @Test
    public void concurrentWritersCannotBothCommitTheSameRevision() throws Exception {
        // 让两个会话同时提交同一版本，必须只有一个成功。
        String url = createDatabase();
        JdbcRegistryConfiguration configuration = configuration(url);
        try (StorageAccess first = configuration.openStorage(); //
             StorageAccess second = configuration.openStorage()) {
            EntityRecord value = first.saveEntity(new EntityRecord(EntityType.SERVICE, "race"));
            EntityRecord other = second.getEntity(new EntityRef(EntityType.SERVICE, "race"));
            CyclicBarrier barrier = new CyclicBarrier(2);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Callable<Boolean> a = () -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        first.saveEntity(value);
                        return true;
                    } catch (ConcurrentModificationException expected) {
                        return false;
                    }
                };
                Callable<Boolean> b = () -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        second.saveEntity(other);
                        return true;
                    } catch (ConcurrentModificationException expected) {
                        return false;
                    }
                };
                Future<Boolean> one = workers.submit(a);
                Future<Boolean> two = workers.submit(b);
                boolean firstCommitted = one.get(5, TimeUnit.SECONDS);
                boolean secondCommitted = two.get(5, TimeUnit.SECONDS);
                assertNotEquals(firstCommitted, secondCommitted);
            } finally {
                workers.shutdownNow();
            }
        }
    }

    @Test
    public void largeUnicodeRecordsAndLongIdentitiesDoNotOverflowIndexes() throws Exception {
        // 正文与业务标识支持长 Unicode 内容，索引只使用固定长度存储键。
        try (StorageAccess storage = configuration(createDatabase()).openStorage()) {
            String id = "服务/".repeat(2000);
            String content = "{\"text\":\"" + "中文内容".repeat(20000) + "\"}";
            EntityRef ref = new EntityRef(EntityType.SERVICE, id);
            EntityRecord serviceRecord = new EntityRecord(ref.type(), id);
            EntServiceCatalogData catalogData = new EntServiceCatalogData();
            catalogData.setName(content);
            serviceRecord.setData(catalogData);
            EntityRecord saved = storage.saveEntity(serviceRecord);
            EntServiceCatalogData loaded = (EntServiceCatalogData) storage.getEntity(ref).getData();
            assertEquals(content, loaded.getName());

            // 长标识同样参与关系索引，清空正文不影响记录身份。
            EntityRef source = new EntityRef(EntityType.INSTANCE, "实例".repeat(2000));
            storage.saveRelation(new RelationRecord(RelationType.PROVIDES, source, ref));
            RelationQuery longIdQuery = new RelationQuery();
            longIdQuery.setSource(source);
            longIdQuery.setTarget(ref);
            assertEquals(1, storage.findRelations(longIdQuery).records().size());
            saved.setData(null);
            storage.saveEntity(saved);
            assertNull(storage.getEntity(ref).getData());
        }
    }

    @Test
    public void concurrentCreatorsCannotOverwriteEachOther() throws Exception {
        // 在首次 INSERT 前汇合，保证两个会话都已经读到记录不存在。
        String url = createDatabase();
        try (JdbcDriverProbe probe = new JdbcDriverProbe(url)) {
            JdbcRegistryConfiguration configuration = configuration(probe.url());
            try (StorageAccess first = configuration.openStorage(); //
                 StorageAccess second = configuration.openStorage()) {
                CyclicBarrier barrier = new CyclicBarrier(2);
                probe.beforeExecute(sql -> {
                    if (sql.startsWith("INSERT INTO meteor_records")) {
                        barrier.await(5, TimeUnit.SECONDS);
                    }
                });

                ExecutorService workers = Executors.newFixedThreadPool(2);
                try {
                    List<Future<ConcurrentModificationException>> results = new ArrayList<>();
                    for (StorageAccess storage : List.of(first, second)) {
                        Future<ConcurrentModificationException> write = workers.submit(() -> {
                            try {
                                storage.saveEntity(new EntityRecord(EntityType.SERVICE, "concurrent"));
                                return null;
                            } catch (ConcurrentModificationException conflict) {
                                return conflict;
                            }
                        });
                        results.add(write);
                    }

                    // 恰好一次成功，失败方收到可重试的冲突并保留底层 SQL 原因。
                    ConcurrentModificationException one = results.get(0).get(10, TimeUnit.SECONDS);
                    ConcurrentModificationException two = results.get(1).get(10, TimeUnit.SECONDS);
                    assertNotEquals(one == null, two == null);
                    ConcurrentModificationException conflict = one == null ? two : one;
                    assertTrue(conflict.getCause() instanceof SQLException);
                    assertNotNull(first.getEntity(new EntityRef(EntityType.SERVICE, "concurrent")));
                } finally {
                    workers.shutdownNow();
                }
            }
        }
    }

    @Test
    public void insertConflictsAreRecognizedThroughJdbcExceptionChains() throws Exception {
        // 错误来自驱动的 SQLState 和错误码，不依赖数据库产品名。
        List<SQLException> duplicates = new ArrayList<>();
        duplicates.add(new SQLException("standard unique violation", "23505"));
        duplicates.add(new SQLException("mysql duplicate", "23000", 1062));
        duplicates.add(new SQLException("oracle duplicate", "23000", 1));
        duplicates.add(new SQLException("sqlserver index", "23000", 2601));
        duplicates.add(new SQLException("sqlserver constraint", "23000", 2627));
        duplicates.add(new SQLException("sqlserver index alternate state", "42000", 2601));
        duplicates.add(new SQLException("sqlserver constraint alternate state", "42000", 2627));
        try (JdbcDriverProbe probe = new JdbcDriverProbe(createDatabase()); //
             StorageAccess storage = configuration(probe.url()).openStorage()) {
            for (SQLException duplicate : duplicates) {
                // 分别通过 cause 和 nextException 提供错误，覆盖两种驱动包装方式。
                SQLException caused = new SQLException("cause wrapper", new IllegalStateException(duplicate));
                SQLException chained = new SQLException("next wrapper");
                chained.setNextException(duplicate);
                for (SQLException reported : List.of(caused, chained)) {
                    probe.beforeExecute(sql -> {
                        if (sql.startsWith("INSERT INTO meteor_records")) {
                            throw reported;
                        }
                    });

                    EntityRecord input = new EntityRecord(EntityType.SERVICE, "duplicate");
                    var conflict = assertThrows(ConcurrentModificationException.class, () -> storage.saveEntity(input));
                    assertSame(reported, conflict.getCause().getCause());
                    assertNull(storage.getEntity(input.getRef()));
                }
            }
        }
    }

    @Test
    public void updateSqlErrorsAreNotReportedAsCreationConflicts() throws Exception {
        // 首次创建成功后，更新阶段收到唯一键 SQL 错误也不能冒充创建竞争。
        try (JdbcDriverProbe probe = new JdbcDriverProbe(createDatabase()); //
             StorageAccess storage = configuration(probe.url()).openStorage()) {
            EntityRecord saved = storage.saveEntity(new EntityRecord(EntityType.SERVICE, "existing"));
            SQLException failure = new SQLException("update constraint", "23505");
            probe.beforeExecute(sql -> {
                if (sql.startsWith("UPDATE meteor_records")) {
                    throw failure;
                }
            });

            SQLException actual = assertThrows(SQLException.class, () -> storage.saveEntity(saved));
            assertSame(failure, actual.getCause());
            assertEquals(saved.getRevision(), storage.getEntity(saved.getRef()).getRevision());
        }
    }

    @Test
    public void leasesUseApplicationEpochMilliseconds() throws Exception {
        // 租约使用应用计算的毫秒时间，不依赖数据库时间函数。
        String url = createDatabase();
        JdbcRegistryConfiguration configuration = configuration(url);
        long leaseMillis = 60000;
        configuration.setLeaseMillis(leaseMillis);
        long before = System.currentTimeMillis();
        try (StorageAccess storage = configuration.openStorage(); //
             Connection connection = DriverManager.getConnection(url, "", "")) {
            JdbcTemplate jdbc = new JdbcTemplate(connection);
            long expiresAt = jdbc.queryForObject("SELECT expires_at FROM meteor_sessions", (rs, row) -> rs.getLong(1));
            assertTrue(expiresAt >= before + leaseMillis);
            assertTrue(expiresAt <= System.currentTimeMillis() + leaseMillis);

            // 人工使租约过期，再次写入应按同一时间口径续租。
            jdbc.executeUpdate("UPDATE meteor_sessions SET expires_at=0");
            before = System.currentTimeMillis();
            storage.saveEntity(new EntityRecord(EntityType.SERVICE, "renew"));
            expiresAt = jdbc.queryForObject("SELECT expires_at FROM meteor_sessions", (rs, row) -> rs.getLong(1));
            assertTrue(expiresAt >= before + leaseMillis);
            assertTrue(expiresAt <= System.currentTimeMillis() + leaseMillis);
        }
    }

}

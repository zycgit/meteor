/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared.storage;
import java.util.ConcurrentModificationException;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import net.hasor.meteor.registry.shared.storage.domain.*;
import org.junit.Test;
import static net.hasor.meteor.registry.shared.RegistryTestUtils.await;
import static org.junit.Assert.*;

/** 内置存储的会话、并发、分页及订阅契约，全部通过正式配置入口验证。 */
public class MemoryStorageTest {
    @Test
    public void configurationsAreIsolatedAndPersistentRecordsSurviveSessions() throws Exception {
        // 准备共享配置和另一个独立配置。
        MemoryRegistryConfiguration shared = new MemoryRegistryConfiguration();
        MemoryRegistryConfiguration isolated = new MemoryRegistryConfiguration();
        EntityRef service = new EntityRef(EntityType.SERVICE, "echo");

        // 写入第一个内存库，不影响独立配置创建的存储。
        try (StorageAccess first = shared.openStorage(); //
             StorageAccess other = isolated.openStorage()) {
            first.saveEntity(new EntityRecord(service));
            assertNull(other.getEntity(service));
        }

        // 关闭后重新打开会话，持久记录仍只存在于原内存库。
        try (StorageAccess reopened = shared.openStorage(); //
             StorageAccess other = isolated.openStorage()) {
            assertNotNull(reopened.getEntity(service));
            assertNull(other.getEntity(service));
        }
    }

    @Test
    public void closingSessionRemovesOnlyItsTemporaryEntitiesAndRelations() throws Exception {
        // 准备两个会话及各自的临时运行记录。
        MemoryRegistryConfiguration configuration = new MemoryRegistryConfiguration();
        EntityRef instance = new EntityRef(EntityType.INSTANCE, "first");
        EntityRef runtime = new EntityRef(EntityType.INSTANCE, "first", null, EntitySection.RUNTIME);
        EntityRef service = new EntityRef(EntityType.SERVICE, "echo");
        EntityRef second = new EntityRef(EntityType.INSTANCE, "second", null, EntitySection.RUNTIME);

        try (StorageAccess owner = configuration.openStorage(); //
             StorageAccess observer = configuration.openStorage()) {
            // 同一会话重复写入相同临时记录，不改变版本。
            EntityRecord temporary = owner.saveEntity(new EntityRecord(runtime));
            EntityRecord unchanged = owner.saveEntity(new EntityRecord(runtime));
            assertEquals(temporary.getRevision(), unchanged.getRevision());

            // 分别写入临时关系、持久服务和另一个会话的运行记录。
            RelationRecord provided = new RelationRecord(RelationType.PROVIDES, service, instance);
            provided.setLifecycle(RecordLifecycle.EPHEMERAL);
            owner.saveRelation(provided);
            owner.saveEntity(new EntityRecord(service));
            observer.saveEntity(new EntityRecord(second));

            // 其他会话不能删除或接管当前会话的临时记录。
            assertThrows(IllegalStateException.class, () -> observer.deleteEntity(temporary));
            assertThrows(IllegalStateException.class, () -> observer.saveEntity(new EntityRecord(runtime)));
            assertThrows(IllegalStateException.class, () -> observer.saveRelation(provided));

            // 重复关闭只清理自己的临时实体与关系，保留持久及其他会话的数据。
            owner.close();
            owner.close();
            assertNull(observer.getEntity(runtime));
            assertNull(observer.getRelation(RelationType.PROVIDES, service, instance));
            assertNotNull(observer.getEntity(second));
            assertNotNull(observer.getEntity(service));
            assertThrows(IllegalStateException.class, () -> owner.getEntity(service));
        }
    }

    @Test
    public void concurrentConditionalWritesHaveExactlyOneWinner() throws Exception {
        // 准备两个存储会话和并发执行器。
        MemoryRegistryConfiguration configuration = new MemoryRegistryConfiguration();
        ExecutorService workers = Executors.newFixedThreadPool(2);

        try (StorageAccess first = configuration.openStorage(); //
             StorageAccess second = configuration.openStorage()) {
            // 两个会话读取同一个版本，分别构造不同更新。
            EntityRef service = new EntityRef(EntityType.SERVICE, "echo");
            first.saveEntity(new EntityRecord(service));
            EntityRecord a = first.getEntity(service);
            EntityRecord b = second.getEntity(service);
            EntServiceCatalogData changedData1 = new EntServiceCatalogData();
            changedData1.setName("first");
            a.setData(changedData1);
            EntServiceCatalogData changedData2 = new EntServiceCatalogData();
            changedData2.setName("second");
            b.setData(changedData2);

            // 等待两个任务就绪后同时提交，只有一个版本更新成功。
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<Boolean> one = workers.submit(() -> write(first, a, ready, start));
            Future<Boolean> two = workers.submit(() -> write(second, b, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertNotEquals(one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS));

            // 旧版本不能删除；最新版本首次删除成功，再次删除返回不存在。
            assertThrows(ConcurrentModificationException.class, () -> first.deleteEntity(a));
            EntityRecord latest = first.getEntity(service);
            assertTrue(first.deleteEntity(latest));
            assertFalse(second.deleteEntity(latest));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    public void paginationHasStableCursorAndSubscriptionsIgnorePageLimit() throws Exception {
        // 准备读写会话与两个服务记录。
        MemoryRegistryConfiguration configuration = new MemoryRegistryConfiguration();

        try (StorageAccess writer = configuration.openStorage(); //
             StorageAccess reader = configuration.openStorage()) {
            writer.saveEntity(new EntityRecord(EntityType.SERVICE, "first"));
            writer.saveEntity(new EntityRecord(EntityType.SERVICE, "second"));

            // 逐页读取，游标稳定且不重复返回记录。
            EntityQuery query = new EntityQuery();
            query.setType(EntityType.SERVICE);
            query.setLimit(1);
            RecordPage<EntityRecord> first = reader.findEntities(query);
            assertNotNull(first.nextCursor());
            query.setCursor(first.nextCursor());
            RecordPage<EntityRecord> second = reader.findEntities(query);
            assertNull(second.nextCursor());
            assertNotEquals(first.records().get(0).getRef(), second.records().get(0).getRef());

            // 订阅不受当前分页限制，其他页的变更也应通知。
            AtomicInteger notifications = new AtomicInteger();
            Cancellable watch = reader.watchEntities(query, notifications::incrementAndGet);
            await(() -> notifications.get() == 1);
            EntityRecord changed = first.records().get(0);
            EntServiceCatalogData changedData3 = new EntServiceCatalogData();
            changedData3.setName("changed outside the requested page");
            changed.setData(changedData3);
            writer.saveEntity(changed);
            await(() -> notifications.get() == 2);

            // 订阅捕获查询参数，不跟随后续对查询对象的修改。
            query.setIds(Set.of("missing"));
            query.setCursor(null);
            writer.deleteEntity(second.records().get(0));
            await(() -> notifications.get() == 3);

            // 取消订阅后不再回调，重复取消返回 false。
            assertTrue(watch.cancel());
            assertFalse(watch.cancel());
            writer.saveEntity(new EntityRecord(EntityType.SERVICE, "third"));
            Thread.sleep(1200);
            assertEquals(3, notifications.get());
        }
    }

    @Test
    public void relationWatchSeesSessionClosureAndRetriesFailedCallbacks() throws Exception {
        // 准备临时关系所属会话和订阅观察会话。
        MemoryRegistryConfiguration configuration = new MemoryRegistryConfiguration();

        try (StorageAccess owner = configuration.openStorage(); //
             StorageAccess observer = configuration.openStorage()) {
            EntityRef service = new EntityRef(EntityType.SERVICE, "echo");
            EntityRef instance = new EntityRef(EntityType.INSTANCE, "first");
            RelationQuery query = new RelationQuery();
            query.setSource(service);
            AtomicInteger attempts = new AtomicInteger();
            AtomicInteger observed = new AtomicInteger(-1);

            // 首次回调故意失败，重试后应观察到空关系集合。
            observer.watchRelations(query, () -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("Retry listener failure");
                }
                try {
                    observed.set(observer.findRelations(query).records().size());
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            await(() -> observed.get() == 0);

            // 新增临时关系后收到通知。
            RelationRecord relation = new RelationRecord(RelationType.PROVIDES, service, instance);
            relation.setLifecycle(RecordLifecycle.EPHEMERAL);
            owner.saveRelation(relation);
            await(() -> observed.get() == 1);

            // 所属会话关闭导致关系删除，观察方收到空集合。
            owner.close();
            await(() -> observed.get() == 0);

            // 观察会话关闭后停止订阅，新写入不再触发回调。
            observer.close();
            int count = attempts.get();

            try (StorageAccess another = configuration.openStorage()) {
                another.saveRelation(relation);
                Thread.sleep(1200);
                assertEquals(count, attempts.get());
            }
        }
    }

    private static boolean write(StorageAccess storage, EntityRecord record,
            CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        assertTrue(start.await(5, TimeUnit.SECONDS));
        try {
            storage.saveEntity(record);
            return true;
        } catch (ConcurrentModificationException conflict) {
            return false;
        }
    }
}

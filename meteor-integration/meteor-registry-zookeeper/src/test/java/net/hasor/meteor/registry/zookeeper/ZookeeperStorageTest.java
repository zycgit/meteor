/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.zookeeper;
import java.util.ConcurrentModificationException;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.StorageKeyUtils;
import net.hasor.meteor.registry.shared.storage.domain.*;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.KillSession;
import org.apache.curator.test.TestingServer;
import org.junit.Test;
import static org.junit.Assert.*;

public class ZookeeperStorageTest {
    private ZookeeperRegistryConfiguration configuration(String address) {
        ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
        configuration.setAddress(address);
        return configuration;
    }

    @Test
    public void parentAndSectionQueriesKeepRulesAndRuntimeIsolated() throws Exception {
        // 同名服务分别放入不同实例及配置、运行区域，查询必须保持层级隔离。
        try (TestingServer server = new TestingServer(); //
             StorageAccess store = this.configuration(server.getConnectString()).openStorage()) {
            EntityRef a = new EntityRef(EntityType.INSTANCE, "a");
            EntityRef b = new EntityRef(EntityType.INSTANCE, "b");
            EntityRef localA = new EntityRef(EntityType.SERVICE, "echo", a, EntitySection.CONFIGURATION);
            EntityRef localB = new EntityRef(EntityType.SERVICE, "echo", b, EntitySection.CONFIGURATION);
            EntityRef runtime = new EntityRef(EntityType.SERVICE, "echo", a, EntitySection.RUNTIME);
            EntityRef ruleA = new EntityRef(EntityType.RULE, "SERVICE_ROUTE", localA, EntitySection.CONFIGURATION);
            EntityRef ruleB = new EntityRef(EntityType.RULE, "SERVICE_ROUTE", localB, EntitySection.CONFIGURATION);
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

            // 查询仅选定父级和区域，不混入同名服务的其他规则。
            EntityQuery query = new EntityQuery();
            query.setType(EntityType.SERVICE);
            query.setParent(a);
            var services = store.findEntities(query).records();
            assertEquals(1, services.size());
            assertEquals(localA, services.get(0).getRef());
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
    public void indexedRelationsCasAndSessionOwnership() throws Exception {
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = this.configuration(server.getConnectString());
            try (StorageAccess first = configuration.openStorage(); //
                 StorageAccess second = configuration.openStorage()) {
                EntityRef source = new EntityRef(EntityType.INSTANCE, "节点/a");
                EntityRef target = new EntityRef(EntityType.SERVICE, "[测试]echo/服务-1");
                EntityRecord instanceRecord = new EntityRecord(source.type(), source.id());
                instanceRecord.setLifecycle(RecordLifecycle.EPHEMERAL);
                first.saveEntity(instanceRecord);
                EntityRecord service = first.saveEntity(new EntityRecord(target.type(), target.id()));
                EntityRecord stale = second.getEntity(target);
                EntServiceCatalogData serviceData = new EntServiceCatalogData();
                serviceData.getMetadata().put("revision", 2);
                service.setData(serviceData);
                EntityRecord updated = first.saveEntity(service);
                assertThrows(ConcurrentModificationException.class, () -> second.saveEntity(stale));
                assertThrows(ConcurrentModificationException.class, () -> second.deleteEntity(stale));

                // 同一实例可同时发布和订阅服务，两类关系分别建立端点索引。
                RelationRecord provider = new RelationRecord(RelationType.PROVIDES, source, target);
                provider.setLifecycle(RecordLifecycle.EPHEMERAL);
                RelationRecord relation = first.saveRelation(provider);
                RelationRecord consumer = new RelationRecord(RelationType.CONSUMES, source, target);
                consumer.setLifecycle(RecordLifecycle.EPHEMERAL);
                first.saveRelation(consumer);

                RelationQuery sourceQuery = new RelationQuery();
                sourceQuery.setSource(source);
                assertEquals(2, second.findRelations(sourceQuery).records().size());
                RelationQuery targetQuery = new RelationQuery();
                targetQuery.setType(RelationType.PROVIDES);
                targetQuery.setTarget(target);
                assertEquals(1, second.findRelations(targetQuery).records().size());
                assertThrows(IllegalStateException.class, () -> second.deleteRelation(relation));

                // 删除关系要同时清理两端索引，再次注册仍保持会话归属。
                assertTrue(first.deleteRelation(relation));
                RelationQuery deletedSourceQuery = new RelationQuery();
                deletedSourceQuery.setType(RelationType.PROVIDES);
                deletedSourceQuery.setSource(source);
                assertTrue(second.findRelations(deletedSourceQuery).records().isEmpty());
                RelationQuery deletedTargetQuery = new RelationQuery();
                deletedTargetQuery.setType(RelationType.PROVIDES);
                deletedTargetQuery.setTarget(target);
                assertTrue(second.findRelations(deletedTargetQuery).records().isEmpty());
                RelationRecord recreatedProvider = new RelationRecord(RelationType.PROVIDES, source, target);
                recreatedProvider.setLifecycle(RecordLifecycle.EPHEMERAL);
                first.saveRelation(recreatedProvider);

                // 退出仅清理当前会话的临时数据，服务实体及版本保持不变。
                first.close();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (second.getEntity(source) != null && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertNull(second.getEntity(source));
                RelationQuery closedSessionQuery = new RelationQuery();
                closedSessionQuery.setSource(source);
                assertEquals(0, second.findRelations(closedSessionQuery).records().size());
                assertEquals(updated.getRevision(), second.getEntity(target).getRevision());
            }
        }
    }

    @Test
    public void missingIndexAndExpiredSessionCanBeRebuiltByRegistration() throws Exception {
        // 精确注入索引丢失和 SDK 会话过期，验证再次注册可修复记录。
        CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder();
        builder.retryPolicy(new ExponentialBackoffRetry(50, 3));
        builder.sessionTimeoutMs(1000);
        builder.connectionTimeoutMs(5000);
        try (TestingServer server = new TestingServer(); //
             CuratorFramework client = builder.connectString(server.getConnectString()).build()) {
            client.start();
            assertTrue("ZooKeeper session did not connect", client.blockUntilConnected(5, TimeUnit.SECONDS));
            ZookeeperRegistryConfiguration configuration = this.configuration(server.getConnectString());
            configuration.setRoot("/repair");
            // 注入可控制的 SDK 会话，用于模拟索引丢失及连接故障。
            try (StorageAccess store = new ZookeeperRegistry(client, "/repair"); //
                 StorageAccess observer = configuration.openStorage()) {
                EntityRef source = new EntityRef(EntityType.INSTANCE, "node");
                EntityRef target = new EntityRef(EntityType.SERVICE, "echo");
                RelationRecord input = new RelationRecord(RelationType.PROVIDES, source, target);
                input.setLifecycle(RecordLifecycle.EPHEMERAL);
                RelationRecord saved = store.saveRelation(input);
                String sourceKey = StorageKeyUtils.entity(source);
                String relationKey = StorageKeyUtils.relation(RelationType.PROVIDES, source, target);
                String index = "/repair/records-v2/index/source/" + sourceKey + "/PROVIDES/" + relationKey;

                // 删除索引模拟部分数据丢失，再次注册必须修复。
                client.delete().forPath(index);
                RelationQuery missingIndexQuery = new RelationQuery();
                missingIndexQuery.setSource(source);
                assertTrue(observer.findRelations(missingIndexQuery).records().isEmpty());
                store.saveRelation(input);
                RelationQuery repairedIndexQuery = new RelationQuery();
                repairedIndexQuery.setSource(source);
                assertEquals(1, observer.findRelations(repairedIndexQuery).records().size());
                RelationRecord repaired = observer.getRelation(RelationType.PROVIDES, source, target);
                assertEquals(saved.getRevision(), repaired.getRevision());

                // 精确使 SDK 会话过期，等待服务端删除临时节点后再注册。
                KillSession.kill(client.getZookeeperClient().getZooKeeper());
                assertTrue("ZooKeeper session did not connect", client.blockUntilConnected(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (System.nanoTime() < deadline) {
                    RelationRecord remaining = observer.getRelation(RelationType.PROVIDES, source, target);
                    if (remaining == null) {
                        break;
                    }
                    Thread.sleep(10);
                }
                assertNull(observer.getRelation(RelationType.PROVIDES, source, target));
                store.saveRelation(input);
                RelationQuery renewedSessionQuery = new RelationQuery();
                renewedSessionQuery.setTarget(target);
                assertEquals(1, observer.findRelations(renewedSessionQuery).records().size());
            }
        }
    }

}

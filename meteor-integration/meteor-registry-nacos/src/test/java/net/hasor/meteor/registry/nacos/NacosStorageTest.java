/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.nacos;
import java.util.ConcurrentModificationException;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class NacosStorageTest {

    @Test
    public void parentAndSectionQueriesKeepRulesAndRuntimeIsolated() throws Exception {
        // 同名服务分别放入不同实例及配置、运行区域，查询必须保持层级隔离。
        try (NacosTestServer server = new NacosTestServer()) {
            NacosRegistryConfiguration configuration = server.configuration();
            try (StorageAccess store = configuration.openStorage()) {
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

                // 查询父级和区域组合，排除同名但不属于该范围的记录。
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
    }

    @Test
    public void temporaryRelationsHaveOneBodyAndRecoverFromPartialIndexWrites() throws Exception {
        try (NacosTestServer server = new NacosTestServer()) {
            NacosRegistryConfiguration configuration = server.configuration();
            EntityRef source = new EntityRef(EntityType.INSTANCE, "a/中文");
            EntityRef target = new EntityRef(EntityType.SERVICE, "echo");
            try (StorageAccess first = configuration.openStorage(); //
                 StorageAccess second = configuration.openStorage()) {
                EntityRecord instanceRecord = new EntityRecord(source.type(), source.id());
                instanceRecord.setLifecycle(RecordLifecycle.EPHEMERAL);
                EntityRecord instance = first.saveEntity(instanceRecord);
                RelationRecord relation = new RelationRecord(RelationType.PROVIDES, source, target);
                relation.setLifecycle(RecordLifecycle.EPHEMERAL);

                // 索引写入后正文失败，不得向查询方暴露半条关系。
                server.rejectPrimary = true;
                assertThrows(IllegalStateException.class, () -> first.saveRelation(relation));
                RelationQuery failedWriteQuery = new RelationQuery();
                failedWriteQuery.setSource(source);
                assertTrue(second.findRelations(failedWriteQuery).records().isEmpty());

                // 重试发布补齐正文，两个方向都可找到同一关系。
                server.rejectPrimary = false;
                first.saveRelation(relation);
                RelationQuery registeredSourceQuery = new RelationQuery();
                registeredSourceQuery.setSource(source);
                assertEquals(1, second.findRelations(registeredSourceQuery).records().size());
                RelationQuery registeredTargetQuery = new RelationQuery();
                registeredTargetQuery.setTarget(target);
                assertEquals(1, second.findRelations(registeredTargetQuery).records().size());
                assertNotNull(second.getRelation(RelationType.PROVIDES, source, target));
                long bodies = 0;
                for (var entry : server.nodes.entrySet()) {
                    if (!entry.getKey().startsWith("meteor.v2.r.")) {
                        continue;
                    }
                    for (var node : entry.getValue().values()) {
                        assertTrue("Nacos cluster name exceeds 64 characters", node.getClusterName().length() <= 64);
                        if (node.getMetadata().containsKey("record")) {
                            bodies++;
                        }
                    }
                }
                assertEquals(1, bodies);

                // 其他会话不能修改临时记录；原会话重发可修复丢失的索引。
                assertThrows(IllegalStateException.class, () -> second.saveEntity(instance));
                assertThrows(IllegalStateException.class, () -> second.deleteRelation(relation));
                server.nodes.entrySet().removeIf(entry -> entry.getKey().startsWith("meteor.v2.source."));
                RelationQuery missingIndexQuery = new RelationQuery();
                missingIndexQuery.setSource(source);
                assertTrue(second.findRelations(missingIndexQuery).records().isEmpty());
                first.saveRelation(relation);
                RelationQuery repairedIndexQuery = new RelationQuery();
                repairedIndexQuery.setSource(source);
                assertEquals(1, second.findRelations(repairedIndexQuery).records().size());

                // 删除及会话退出分别清理正文与索引。
                assertTrue(first.deleteRelation(relation));
                RelationQuery deletedRelationQuery = new RelationQuery();
                deletedRelationQuery.setTarget(target);
                assertTrue(second.findRelations(deletedRelationQuery).records().isEmpty());
                first.saveRelation(relation);
                first.close();
                assertNull(second.getEntity(source));
                RelationQuery closedSessionQuery = new RelationQuery();
                closedSessionQuery.setSource(source);
                assertTrue(second.findRelations(closedSessionQuery).records().isEmpty());
            }
        }
    }

    @Test
    public void persistentCasDeletionRecreationAndEnumerationSurviveNewClients() throws Exception {
        try (NacosTestServer server = new NacosTestServer()) {
            NacosRegistryConfiguration configuration = server.configuration();
            EntityRef source = new EntityRef(EntityType.INSTANCE, "offline");
            EntityRef target = new EntityRef(EntityType.SERVICE, "echo");
            try (StorageAccess first = configuration.openStorage(); //
                 StorageAccess second = configuration.openStorage()) {
                // 持久分片的 CAS 冲突可重试，创建后不同会话看到相同内容。
                server.conflicts = 2;
                EntityRecord serviceRecord = new EntityRecord(target.type(), target.id());
                EntServiceCatalogData serviceData = new EntServiceCatalogData();
                serviceData.getMetadata().put("label", "中文");
                serviceRecord.setData(serviceData);
                EntityRecord created = first.saveEntity(serviceRecord);
                EntServiceCatalogData stored = (EntServiceCatalogData) second.getEntity(target).getData();
                assertEquals("中文", stored.getMetadata().get("label"));
                EntityQuery registeredServices = new EntityQuery();
                registeredServices.setType(EntityType.SERVICE);
                assertEquals(1, second.findEntities(registeredServices).records().size());

                // 更新后旧版本不能再写入或删除，关系仍按端点查询。
                EntityRecord stale = second.getEntity(target);
                created.setData(null);
                EntityRecord current = first.saveEntity(created);
                assertThrows(ConcurrentModificationException.class, () -> second.saveEntity(stale));
                assertThrows(ConcurrentModificationException.class, () -> second.deleteEntity(stale));
                first.saveRelation(new RelationRecord(RelationType.CONSUMES, source, target));
                RelationQuery endpointQuery = new RelationQuery();
                endpointQuery.setSource(source);
                endpointQuery.setTarget(target);
                assertEquals(1, second.findRelations(endpointQuery).records().size());

                // 重建记录生成新版本，旧快照仍不能删除它。
                assertTrue(first.deleteEntity(current));
                assertNull(second.getEntity(target));
                EntityQuery deletedServices = new EntityQuery();
                deletedServices.setType(EntityType.SERVICE);
                assertTrue(second.findEntities(deletedServices).records().isEmpty());
                first.saveEntity(new EntityRecord(target.type(), target.id()));
                assertThrows(ConcurrentModificationException.class, () -> second.deleteEntity(stale));
            }

            // 所有原会话退出后，持久目录和关系仍可读取。
            try (StorageAccess reopened = configuration.openStorage()) {
                assertNotNull(reopened.getEntity(target));
                RelationQuery reopenedQuery = new RelationQuery();
                reopenedQuery.setType(RelationType.CONSUMES);
                reopenedQuery.setSource(source);
                assertEquals(1, reopened.findRelations(reopenedQuery).records().size());
            }
        }
    }

    @Test
    public void unhealthyTemporaryRecordsAreNotDiscoverableAndClosedAccessFails() throws Exception {
        // 不健康的临时实例不可发现，关闭后的存储不可继续使用。
        try (NacosTestServer server = new NacosTestServer()) {
            NacosRegistryConfiguration configuration = server.configuration();
            try (StorageAccess first = configuration.openStorage()) {
                EntityRef ref = new EntityRef(EntityType.INSTANCE, "node");
                EntityRecord unhealthyInstance = new EntityRecord(ref.type(), ref.id());
                unhealthyInstance.setLifecycle(RecordLifecycle.EPHEMERAL);
                first.saveEntity(unhealthyInstance);

                // 模拟 Nacos 健康检查剔除实例，记录正文仍存在但不能参与发现。
                for (var nodes : server.nodes.values()) {
                    for (var node : nodes.values()) {
                        node.setHealthy(false);
                    }
                }
                assertNull(first.getEntity(ref));
                EntityQuery unhealthyInstances = new EntityQuery();
                unhealthyInstances.setType(ref.type());
                assertTrue(first.findEntities(unhealthyInstances).records().isEmpty());

                // 关闭幂等，关闭后的会话拒绝读取。
                first.close();
                first.close();
                assertThrows(IllegalStateException.class, () -> first.getEntity(ref));
            }
        }
    }

    @Test
    public void storageFailuresRemainVisibleAndDoNotBecomeCasRetries() throws Exception {
        try (NacosTestServer server = new NacosTestServer()) {
            NacosRegistryConfiguration configuration = server.configuration();
            try (StorageAccess storage = configuration.openStorage()) {
                EntityRef ref = new EntityRef(EntityType.SERVICE, "failure");
                EntityRecord saved = storage.saveEntity(new EntityRecord(ref));

                // 普通读取、写入和删除故障都直接反馈，不能伪装成版本冲突。
                server.available = false;
                int before = server.reads.get();
                IllegalStateException read = assertThrows(IllegalStateException.class, () -> storage.getEntity(ref));
                assertEquals("Nacos unavailable", read.getMessage());
                assertEquals(before + 1, server.reads.get());
                assertThrows(IllegalStateException.class, () -> storage.saveEntity(saved));
                assertThrows(IllegalStateException.class, () -> storage.deleteEntity(saved));

                // 故障恢复后记录未被误删，同一存储会话可以继续更新。
                server.available = true;
                EntityRecord current = storage.getEntity(ref);
                assertEquals(saved.getRevision(), current.getRevision());
                assertNotEquals(saved.getRevision(), storage.saveEntity(current).getRevision());
            }
        }
    }

    @Test
    public void recordLifecycleCannotBeChangedInEitherDirection() throws Exception {
        try (NacosTestServer server = new NacosTestServer()) {
            NacosRegistryConfiguration configuration = server.configuration();
            try (StorageAccess storage = configuration.openStorage()) {
                EntityRecord persistent = storage.saveEntity(new EntityRecord(EntityType.SERVICE, "persistent"));
                EntityRecord temporary = new EntityRecord(EntityType.INSTANCE, "temporary");
                temporary.setLifecycle(RecordLifecycle.EPHEMERAL);
                EntityRecord savedTemporary = storage.saveEntity(temporary);

                // 两种生命周期使用不同的 Nacos 服务，身份检查仍必须一致。
                persistent.setLifecycle(RecordLifecycle.EPHEMERAL);
                savedTemporary.setLifecycle(RecordLifecycle.PERSISTENT);
                assertThrows(IllegalArgumentException.class, () -> storage.saveEntity(persistent));
                assertThrows(IllegalArgumentException.class, () -> storage.saveEntity(savedTemporary));
            }
        }
    }

    @Test
    public void rejectedCreationDoesNotPublishAnEmptyShard() throws Exception {
        try (NacosTestServer server = new NacosTestServer()) {
            NacosRegistryConfiguration configuration = server.configuration();
            try (StorageAccess storage = configuration.openStorage()) {
                // 记录不存在时旧版本不可用于创建，目录与正文都不应留下写入痕迹。
                EntityRecord stale = new EntityRecord(EntityType.SERVICE, "missing");
                stale.setRevision("old-revision");
                assertThrows(ConcurrentModificationException.class, () -> storage.saveEntity(stale));
                assertTrue(server.documents.isEmpty());
                assertNull(storage.getEntity(stale.getRef()));
            }
        }
    }
}

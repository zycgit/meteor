/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared.storage;
import java.io.IOException;
import java.util.Map;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import net.hasor.meteor.registry.shared.storage.domain.*;
import net.hasor.meteor.registry.shared.utils.RegistryJson;
import org.junit.Test;
import static org.junit.Assert.*;

public class StorageRecordTest {

    @Test
    public void typedBodiesRoundTripThroughTheirRecordKinds() throws Exception {
        MemoryRegistryConfiguration configuration = new MemoryRegistryConfiguration();

        try (StorageAccess store = configuration.openStorage()) {
            // 保存带继承字段的实体正文。
            EntityRef service = new EntityRef(EntityType.SERVICE, "echo");
            EntServiceCatalogData catalog = new EntServiceCatalogData();
            catalog.setName("Echo");
            catalog.setEnabled(false);
            catalog.getMetadata().put("team", "订单");
            EntityRecord entity = new EntityRecord(service);
            entity.setData(catalog);
            store.saveEntity(entity);

            // 服务目录通过 EntServiceConfigurationData 间接继承 EntData，继承字段仍正常存储。
            EntityRecord savedEntity = store.getEntity(service);
            EntServiceCatalogData storedCatalog = (EntServiceCatalogData) savedEntity.getData();
            assertEquals("Echo", storedCatalog.getName());
            assertEquals(Boolean.FALSE, storedCatalog.getEnabled());
            assertEquals("订单", storedCatalog.getMetadata().get("team"));

            // 修改输入和读取结果都不影响已保存的正文。
            catalog.getMetadata().put("team", "本地修改");
            storedCatalog.getMetadata().clear();
            EntServiceCatalogData untouched = (EntServiceCatalogData) store.getEntity(service).getData();
            assertEquals("订单", untouched.getMetadata().get("team"));

            // 关系记录保存协议挂载与发现地址，使用独立的关系正文类型。
            RefProtocolMountData endpoint = new RefProtocolMountData();
            endpoint.setProtocol("rsf");
            endpoint.setPublishedAddress("rsf://localhost:2181/default");
            RefServiceRelationData discovery = new RefServiceRelationData();
            discovery.setEndpoints(new RefProtocolMountData[] { endpoint });
            discovery.setAddresses(new String[] { endpoint.getPublishedAddress() });
            EntityRef instance = new EntityRef(EntityType.INSTANCE, "one");
            RelationRecord relation = new RelationRecord(RelationType.PROVIDES, service, instance);
            relation.setData(discovery);
            store.saveRelation(relation);

            // 读取关系后保留正文类型，修改局部结果仍不影响存储。
            RelationRecord savedRelation = store.getRelation(RelationType.PROVIDES, service, instance);
            RefServiceRelationData storedDiscovery = (RefServiceRelationData) savedRelation.getData();
            assertEquals("rsf", storedDiscovery.getEndpoints()[0].getProtocol());
            assertArrayEquals(discovery.getAddresses(), storedDiscovery.getAddresses());
            assertEquals(RegistryJson.write(discovery), RegistryJson.write(savedRelation.getData()));
            endpoint.setProtocol("changed");
            storedDiscovery.getAddresses()[0] = "changed";
            RelationRecord untouchedRelation = store.getRelation(RelationType.PROVIDES, service, instance);
            RefServiceRelationData untouchedDiscovery = (RefServiceRelationData) untouchedRelation.getData();
            assertEquals("rsf", untouchedDiscovery.getEndpoints()[0].getProtocol());
            assertEquals("rsf://localhost:2181/default", untouchedDiscovery.getAddresses()[0]);
        }
    }

    @Test
    public void documentsRestoreEveryEntityBodyType() throws Exception {
        // 准备所有实体正文及其对应的实体身份。
        EntData[] bodies = {
                new EntGlobalConfigurationData(), new EntServiceCatalogData(), new EntServiceConfigurationData(),
                new EntInstanceConfigurationData(), new EntRuleConfigurationData(), new EntInstanceRuntimeData(),
                new EntInstanceServiceRuntimeData()
        };
        EntityRef instance = new EntityRef(EntityType.INSTANCE, "one");
        EntityRef service = new EntityRef(EntityType.SERVICE, "echo");
        EntityRef localService = new EntityRef(EntityType.SERVICE, "echo", instance, EntitySection.CONFIGURATION);
        EntityRef[] identities = {
                new EntityRef(EntityType.GLOBAL, "default"), service, localService, instance,
                new EntityRef(EntityType.RULE, "SERVICE_ROUTE", service, EntitySection.CONFIGURATION),
                new EntityRef(EntityType.INSTANCE, "one", null, EntitySection.RUNTIME),
                new EntityRef(EntityType.SERVICE, "echo", instance, EntitySection.RUNTIME)
        };

        // 逐个序列化文档并还原，确认正文类型与身份一致。
        for (int index = 0; index < bodies.length; index++) {
            EntityRecord record = new EntityRecord(identities[index]);
            record.setData(bodies[index]);
            StorageDocument document = new StorageDocument();
            document.setEntity(record);

            String json = RegistryJson.write(document);
            StorageDocument restored = RegistryJson.read(json, StorageDocument.class);
            assertEquals(bodies[index].getClass(), restored.getEntity().getData().getClass());
            assertEquals(record.getRef(), restored.getEntity().getRef());

            // 文档使用结构化 data 字段和类型标识，不残留旧 content 字段。
            Map<?, ?> stored = RegistryJson.read(json, Map.class);
            Map<?, ?> entity = (Map<?, ?>) stored.get("entity");
            assertFalse(entity.containsKey("content"));
            assertTrue(entity.get("data") instanceof Map);
            assertNotNull(((Map<?, ?>) entity.get("data")).get("kind"));

            // 类型标识属于 Record 属性，不污染 DTO 自身的转换。
            assertFalse(RegistryJson.copy(bodies[index], Map.class).containsKey("kind"));
        }
    }

    @Test
    public void recordsRejectUnknownAndCrossedBodyKinds() throws Exception {
        // 实体记录拒绝关系正文标识和未知正文标识。
        EntityRecord entity = new EntityRecord(EntityType.SERVICE, "echo");
        entity.setData(new EntServiceCatalogData());
        String entityJson = RegistryJson.write(entity);
        String wrongEntity = entityJson.replace("\"serviceCatalog\"", "\"serviceRelation\"");
        String unknownEntity = entityJson.replace("\"serviceCatalog\"", "\"unknown\"");
        assertThrows(IOException.class, () -> RegistryJson.read(wrongEntity, EntityRecord.class));
        assertThrows(IOException.class, () -> RegistryJson.read(unknownEntity, EntityRecord.class));

        // 关系记录拒绝实体正文标识，但可还原正确的关系正文。
        EntityRef instance = new EntityRef(EntityType.INSTANCE, "one");
        RelationRecord relation = new RelationRecord(RelationType.PROVIDES, entity.getRef(), instance);
        relation.setData(new RefServiceRelationData());
        String relationJson = RegistryJson.write(relation);
        String wrongRelation = relationJson.replace("\"serviceRelation\"", "\"serviceCatalog\"");
        assertThrows(IOException.class, () -> RegistryJson.read(wrongRelation, RelationRecord.class));
        RelationRecord restored = RegistryJson.read(relationJson, RelationRecord.class);
        assertTrue(restored.getData() instanceof RefServiceRelationData);
    }

    @Test
    public void unchangedTemporaryBodiesKeepTheirRevision() throws Exception {
        MemoryRegistryConfiguration configuration = new MemoryRegistryConfiguration();

        try (StorageAccess store = configuration.openStorage()) {
            // 保存实例的临时运行记录，记录初始版本。
            EntityRef ref = new EntityRef(EntityType.INSTANCE, "one", null, EntitySection.RUNTIME);
            EntityRecord entity = new EntityRecord(ref);
            EntInstanceRuntimeData first = new EntInstanceRuntimeData();
            first.getSettings().put("a", 1);
            first.getSettings().put("b", 2);
            entity.setData(first);
            EntityRecord saved = store.saveEntity(entity);

            // 不同对象、不同 Map 插入顺序仍是同一份正文。
            EntInstanceRuntimeData second = new EntInstanceRuntimeData();
            second.getSettings().put("b", 2);
            second.getSettings().put("a", 1);
            entity.setData(second);
            assertEquals(saved.getRevision(), store.saveEntity(entity).getRevision());

            // 真实字段发生变化时才增加版本。
            second.setBlock(true);
            assertNotEquals(saved.getRevision(), store.saveEntity(entity).getRevision());

            // 临时关系同样按正文内容判断是否需要更新版本。
            EntityRef service = new EntityRef(EntityType.SERVICE, "echo");
            RelationRecord relation = new RelationRecord(RelationType.PROVIDES, service, ref);
            relation.setLifecycle(RecordLifecycle.EPHEMERAL);
            RefServiceRelationData discovery = new RefServiceRelationData();
            discovery.setAddresses(new String[] { "rsf://localhost:2181/default" });
            relation.setData(discovery);
            RelationRecord savedRelation = store.saveRelation(relation);
            relation.setData(RegistryJson.copy(discovery, RefServiceRelationData.class));
            assertEquals(savedRelation.getRevision(), store.saveRelation(relation).getRevision());
        }
    }

    @Test
    public void parentAndSectionQueriesKeepRulesAndRuntimeIsolated() throws Exception {
        MemoryRegistryConfiguration configuration = new MemoryRegistryConfiguration();

        try (StorageAccess store = configuration.openStorage()) {
            // 为两个实例分别保存服务配置、运行信息和所属规则。
            EntityRef a = new EntityRef(EntityType.INSTANCE, "a");
            EntityRef b = new EntityRef(EntityType.INSTANCE, "b");
            EntityRef localA = new EntityRef(EntityType.SERVICE, "echo", a, EntitySection.CONFIGURATION);
            EntityRef localB = new EntityRef(EntityType.SERVICE, "echo", b, EntitySection.CONFIGURATION);
            EntityRef runtime = new EntityRef(EntityType.SERVICE, "echo", a, EntitySection.RUNTIME);
            EntityRef ruleA = new EntityRef(EntityType.RULE, "FLOW_CONTROL", localA, EntitySection.CONFIGURATION);
            EntityRef ruleB = new EntityRef(EntityType.RULE, "FLOW_CONTROL", localB, EntitySection.CONFIGURATION);
            store.saveEntity(new EntityRecord(localA));
            store.saveEntity(new EntityRecord(localB));
            store.saveEntity(new EntityRecord(runtime));
            store.saveEntity(new EntityRecord(ruleA));
            store.saveEntity(new EntityRecord(ruleB));

            // 先按父实例查服务配置，再切换到运行记录分区。
            EntityQuery query = new EntityQuery();
            query.setType(EntityType.SERVICE);
            query.setParent(a);
            assertEquals(localA, store.findEntities(query).records().get(0).getRef());
            assertEquals(1, store.findEntities(query).records().size());
            query.setSection(EntitySection.RUNTIME);
            assertEquals(runtime, store.findEntities(query).records().get(0).getRef());

            // 规则查询按所属服务隔离，不混入另一实例的规则。
            query.setType(EntityType.RULE);
            query.setSection(EntitySection.CONFIGURATION);
            query.setParent(localA);
            assertEquals(1, store.findEntities(query).records().size());
            assertEquals(ruleA, store.findEntities(query).records().get(0).getRef());
            query.setParent(localB);
            assertEquals(ruleB, store.findEntities(query).records().get(0).getRef());
        }
    }

    @Test
    public void typedIdentityAndLengthPrefixedRelationIdsAreUnambiguous() {
        // 构造包含分隔符的身份，验证关系键的稳定性。
        EntityRef a = new EntityRef(EntityType.INSTANCE, "a:b");
        EntityRef b = new EntityRef(EntityType.SERVICE, "c");
        String id = StorageKeyUtils.relation(RelationType.PROVIDES, a, b);
        assertEquals(id, StorageKeyUtils.relation(RelationType.PROVIDES, new EntityRef(EntityType.INSTANCE, "a:b"), b));

        // 关系类型、方向和身份边界均参与编码，不能出现碰撞。
        assertNotEquals(id, StorageKeyUtils.relation(RelationType.CONSUMES, a, b));
        assertNotEquals(id, StorageKeyUtils.relation(RelationType.PROVIDES, b, a));
        EntityRef alternateSource = new EntityRef(EntityType.INSTANCE, "a");
        EntityRef alternateTarget = new EntityRef(EntityType.SERVICE, "b:c");
        String alternateId = StorageKeyUtils.relation(RelationType.PROVIDES, alternateSource, alternateTarget);
        assertNotEquals(id, alternateId);
        assertNotEquals(StorageKeyUtils.entity(a), StorageKeyUtils.entity(new EntityRef(EntityType.SERVICE, a.id())));

        // 身份类型和标识不能为空。
        assertThrows(IllegalArgumentException.class, () -> new EntityRef(null, "a"));
        assertThrows(IllegalArgumentException.class, () -> new EntityRef(EntityType.SERVICE, " "));
    }

    @Test
    public void returnedRecordsAreDetachedAndLifecycleCannotBeChanged() throws Exception {
        MemoryRegistryConfiguration configuration = new MemoryRegistryConfiguration();

        try (StorageAccess store = configuration.openStorage()) {
            // 保存实体并修改返回正文，存储数据及原输入仍独立。
            EntityRef ref = new EntityRef(EntityType.SERVICE, "echo");
            EntityRecord input = new EntityRecord(ref.type(), ref.id());
            EntityRecord saved = store.saveEntity(input);
            assertEquals("", input.getRevision());
            EntServiceCatalogData changedData1 = new EntServiceCatalogData();
            changedData1.setName("changed locally");
            saved.setData(changedData1);
            assertNull(store.getEntity(ref).getData());

            // 已有记录不允许改变持久或临时生命周期。
            assertThrows(IllegalArgumentException.class, () -> {
                saved.setLifecycle(RecordLifecycle.EPHEMERAL);
                store.saveEntity(saved);
            });

            // 分页集合不可修改，其中的记录正文也与存储隔离。
            EntityRef source = new EntityRef(EntityType.INSTANCE, "a");
            RelationRecord inputRelation = new RelationRecord(RelationType.PROVIDES, source, ref);
            RelationRecord relation = store.saveRelation(inputRelation);
            RelationQuery relationQuery = new RelationQuery();
            relationQuery.setTarget(ref);
            RecordPage<RelationRecord> page = store.findRelations(relationQuery);
            assertThrows(UnsupportedOperationException.class, () -> page.records().clear());
            RefServiceRelationData changedData2 = new RefServiceRelationData();
            changedData2.setAddresses(new String[] { "rsf://localhost:2181/changed" });
            page.records().get(0).setData(changedData2);
            assertNull(store.getRelation(relation.getType(), relation.getSource(), ref).getData());

            // 删除实体不隐式级联删除关系；会话关闭后拒绝继续写入。
            assertTrue(store.deleteEntity(store.getEntity(ref)));
            assertNotNull(store.getRelation(relation.getType(), relation.getSource(), ref));
            store.close();
            assertThrows(IllegalStateException.class, () -> store.saveRelation(relation));
        }
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared.storage;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.registry.shared.storage.domain.*;

/**
 * 实体与关系记录的存储访问；查询允许最终一致，单条持久记录更新使用版本校验。
 */
public interface StorageAccess extends AutoCloseable {

    // 实体

    /** 获取实体，不存在时返回 null。 */
    EntityRecord getEntity(EntityRef ref) throws Exception;

    /** 按类型、父对象、数据片段及可选 ID 集合分页查询；父对象为空时只查根对象。 */
    RecordPage<EntityRecord> findEntities(EntityQuery query) throws Exception;

    /** 保存实体；持久记录空版本表示仅创建，非空版本表示条件更新。 */
    EntityRecord saveEntity(EntityRecord record) throws Exception;

    /** 删除实体；持久记录检查版本，临时记录仅允许所属会话删除。 */
    boolean deleteEntity(EntityRecord record) throws Exception;

    // 关系

    /** 获取指定关系，不存在时返回 null。 */
    RelationRecord getRelation(RelationType type, EntityRef source, EntityRef target) throws Exception;

    /** 按关系类型、起点、终点组合分页查询。 */
    RecordPage<RelationRecord> findRelations(RelationQuery query) throws Exception;

    /** 保存关系；不会隐式创建两端实体，不进行跨记录事务。 */
    RelationRecord saveRelation(RelationRecord record) throws Exception;

    /** 删除关系，保留两端实体。 */
    boolean deleteRelation(RelationRecord record) throws Exception;

    // 变更订阅

    /** 订阅全部匹配实体；异步首次通知及后续变化、故障恢复通知，忽略分页参数，回调中重新读取数据。 */
    Cancellable watchEntities(EntityQuery query, Runnable listener);

    /** 订阅全部匹配关系；包含删除与临时记录失效，查询失败不表示空结果，关闭存储自动取消订阅。 */
    Cancellable watchRelations(RelationQuery query, Runnable listener);
}

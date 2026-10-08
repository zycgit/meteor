/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared.storage.domain;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** 发布、订阅关系的存储封装；type + source + target 唯一定位，data 保存关系正文。 */
public final class RelationRecord {
    private final RelationType    type;
    private final EntityRef       source;
    private final EntityRef       target;
    private       RecordLifecycle lifecycle = RecordLifecycle.PERSISTENT;
    private       String          revision  = "";
    // 类型标识仅用于存储正文，普通 DTO 与 RPC 对象之间的转换不受影响。
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({ @JsonSubTypes.Type(value = RefServiceRelationData.class, name = "serviceRelation") })
    private       RefData         data;

    @JsonCreator
    public RelationRecord(@JsonProperty("type") RelationType type, @JsonProperty("source") EntityRef source, @JsonProperty("target") EntityRef target) {
        this.type = type;
        this.source = source;
        this.target = target;
    }

    public RelationType getType() {
        return this.type;
    }

    public EntityRef getSource() {
        return this.source;
    }

    public EntityRef getTarget() {
        return this.target;
    }

    public RecordLifecycle getLifecycle() {
        return this.lifecycle;
    }

    public void setLifecycle(RecordLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    public String getRevision() {
        return this.revision;
    }

    public void setRevision(String revision) {
        this.revision = revision;
    }

    public RefData getData() {
        return this.data;
    }

    public void setData(RefData data) {
        this.data = data;
    }
}

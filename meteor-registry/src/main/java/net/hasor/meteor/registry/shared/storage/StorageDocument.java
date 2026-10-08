/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared.storage;
import com.fasterxml.jackson.annotation.JsonIgnore;
import net.hasor.meteor.registry.shared.storage.domain.EntityRecord;
import net.hasor.meteor.registry.shared.storage.domain.RecordLifecycle;
import net.hasor.meteor.registry.shared.storage.domain.RelationRecord;

/**适配器内部正文；只持有一种记录，tempSessionId 标识临时记录所属会话。*/
public final class StorageDocument {
    private EntityRecord   entity;
    private RelationRecord relation;
    private String         sessionId;

    public EntityRecord getEntity() {
        return this.entity;
    }

    public void setEntity(EntityRecord entity) {
        this.entity = entity;
    }

    public RelationRecord getRelation() {
        return this.relation;
    }

    public void setRelation(RelationRecord relation) {
        this.relation = relation;
    }

    public String getSessionId() {
        return this.sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    @JsonIgnore
    public String getKey() {
        if (this.entity != null) {
            return StorageKeyUtils.entity(this.entity.getRef());
        } else {
            return StorageKeyUtils.relation(this.relation.getType(), this.relation.getSource(), this.relation.getTarget());
        }
    }

    @JsonIgnore
    public String getKind() {
        return this.entity != null ? "e" : "r";
    }

    @JsonIgnore
    public String getType() {
        return this.entity != null ? this.entity.getType().name() : this.relation.getType().name();
    }

    @JsonIgnore
    public RecordLifecycle getLifecycle() {
        return this.entity != null ? this.entity.getLifecycle() : this.relation.getLifecycle();
    }

    @JsonIgnore
    public String getRevision() {
        return this.entity != null ? this.entity.getRevision() : this.relation.getRevision();
    }

    @JsonIgnore
    public Object getData() {
        return this.entity != null ? this.entity.getData() : this.relation.getData();
    }

    public void revision(String revision) {
        if (this.entity != null) {
            this.entity.setRevision(revision);
        } else {
            this.relation.setRevision(revision);
        }
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared.storage.domain;
import java.util.LinkedHashSet;
import java.util.Set;

/** 实体查询条件：按类型、归属、数据片段和 ID 集合筛选，并按游标分页。 */
public final class EntityQuery {
    private EntityType    type;
    private EntityRef     parent;
    private EntitySection section = EntitySection.CONFIGURATION;
    private Set<String>   ids;
    private int           limit   = 100;
    private String        cursor;

    public EntityRef getParent() {
        return this.parent;
    }

    public void setParent(EntityRef value) {
        this.parent = value;
    }

    public EntitySection getSection() {
        return this.section;
    }

    public void setSection(EntitySection value) {
        this.section = value;
    }

    public EntityType getType() {
        return this.type;
    }

    public void setType(EntityType type) {
        this.type = type;
    }

    public Set<String> getIds() {
        return this.ids;
    }

    public void setIds(Set<String> ids) {
        this.ids = ids;
    }

    public int getLimit() {
        return this.limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public String getCursor() {
        return this.cursor;
    }

    public void setCursor(String cursor) {
        this.cursor = cursor;
    }

    /** 复制查询条件和分页信息，修改副本不会改变原查询。 */
    public EntityQuery copy() {
        EntityQuery copy = new EntityQuery();
        copy.setType(this.type);
        copy.setParent(this.parent);
        copy.setSection(this.section);
        copy.setIds(this.ids == null ? null : new LinkedHashSet<>(this.ids));
        copy.setLimit(this.limit);
        copy.setCursor(this.cursor);
        return copy;
    }
}

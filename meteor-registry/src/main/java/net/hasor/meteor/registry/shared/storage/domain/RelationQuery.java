/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared.storage.domain;

/** 发布、订阅关系查询条件；source 为服务，target 为实例，未设置的条件不限制。 */
public final class RelationQuery {
    private RelationType type;
    private EntityRef    source;
    private EntityRef    target;
    private int          limit = 100;
    private String       cursor;

    public RelationType getType() {
        return this.type;
    }

    public void setType(RelationType type) {
        this.type = type;
    }

    public EntityRef getSource() {
        return this.source;
    }

    public void setSource(EntityRef source) {
        this.source = source;
    }

    public EntityRef getTarget() {
        return this.target;
    }

    public void setTarget(EntityRef target) {
        this.target = target;
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
    public RelationQuery copy() {
        RelationQuery copy = new RelationQuery();
        copy.setType(this.type);
        copy.setSource(this.source);
        copy.setTarget(this.target);
        copy.setLimit(this.limit);
        copy.setCursor(this.cursor);
        return copy;
    }
}

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
import net.hasor.cobble.ObjectUtils;
import net.hasor.cobble.StringUtils;

/** 实体身份：type + id + parent + section；归属与配置、运行片段均参与唯一定位。 */
public record EntityRef(EntityType type, String id, EntityRef parent, EntitySection section) {
    public EntityRef(EntityType type, String id) {
        this(type, id, null, EntitySection.CONFIGURATION);
    }

    @JsonCreator
    public EntityRef(@JsonProperty("type") EntityType type, @JsonProperty("id") String id, @JsonProperty("parent") EntityRef parent, @JsonProperty("section") EntitySection section) {
        if (type == null || StringUtils.isBlank(id) || section == null) {
            throw new IllegalArgumentException("Entity type, ID and section are required");
        }
        if (parent != null && parent.section() != EntitySection.CONFIGURATION) {
            throw new IllegalArgumentException("Parent must identify the logical configuration object");
        }
        boolean serviceChild = type == EntityType.SERVICE && parent != null;
        if (serviceChild && (parent.type() != EntityType.INSTANCE || parent.parent() != null)) {
            throw new IllegalArgumentException("A local service must belong to an instance");
        }
        if (type == EntityType.RULE && (parent == null || parent.type() == EntityType.RULE)) {
            throw new IllegalArgumentException("Rule owner must be global, instance or service");
        }
        if ((type == EntityType.GLOBAL || type == EntityType.INSTANCE) && parent != null) {
            throw new IllegalArgumentException("Global and instance objects cannot have a parent");
        }
        boolean runtimeObject = type == EntityType.INSTANCE;
        if (section == EntitySection.RUNTIME && !runtimeObject) {
            throw new IllegalArgumentException("Runtime data belongs to an instance");
        }

        this.type = type;
        this.id = id;
        this.parent = parent;
        this.section = section;
    }

    @Override
    public boolean equals(Object value) {
        if (!(value instanceof EntityRef other)) {
            return false;
        }
        boolean sameKind = this.type == other.type && this.section == other.section;
        boolean sameId = StringUtils.equals(this.id, other.id);
        boolean sameParent = ObjectUtils.equals(this.parent, other.parent);
        return sameKind && sameId && sameParent;
    }

}

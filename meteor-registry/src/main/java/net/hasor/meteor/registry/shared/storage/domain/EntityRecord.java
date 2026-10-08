/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared.storage.domain;
import com.fasterxml.jackson.annotation.*;

/** 实体存储封装；ref 唯一定位实体，data 保存实体正文，revision 用于 CAS。 */
public final class EntityRecord {
    private final EntityRef       ref;
    private       RecordLifecycle lifecycle;
    private       String          revision = "";
    // 类型标识仅用于存储正文，普通 DTO 与 RPC 对象之间的转换不受影响。
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
    @JsonSubTypes({//
            @JsonSubTypes.Type(value = EntGlobalConfigurationData.class, name = "globalConfiguration"),     //
            @JsonSubTypes.Type(value = EntServiceCatalogData.class, name = "serviceCatalog"),               //
            @JsonSubTypes.Type(value = EntServiceConfigurationData.class, name = "serviceConfiguration"),   //
            @JsonSubTypes.Type(value = EntInstanceConfigurationData.class, name = "instanceConfiguration"), //
            @JsonSubTypes.Type(value = EntRuleConfigurationData.class, name = "ruleConfiguration"),         //
            @JsonSubTypes.Type(value = EntInstanceRuntimeData.class, name = "instanceRuntime"),             //
            @JsonSubTypes.Type(value = EntInstanceServiceRuntimeData.class, name = "instanceServiceRuntime")//
    })
    private       EntData         data;

    public EntityRecord(EntityType type, String id) {
        this(new EntityRef(type, id));
    }

    @JsonCreator
    public EntityRecord(@JsonProperty("ref") EntityRef ref) {
        this.ref = ref;
        boolean runtime = ref.section() == EntitySection.RUNTIME;
        this.lifecycle = runtime ? RecordLifecycle.EPHEMERAL : RecordLifecycle.PERSISTENT;
    }

    public EntityRef getRef() {
        return this.ref;
    }

    @JsonIgnore
    public EntityType getType() {
        return this.ref.type();
    }

    @JsonIgnore
    public String getId() {
        return this.ref.id();
    }

    public RecordLifecycle getLifecycle() {
        return this.lifecycle;
    }

    public void setLifecycle(RecordLifecycle value) {
        this.lifecycle = value;
    }

    public String getRevision() {
        return this.revision;
    }

    public void setRevision(String value) {
        this.revision = value;
    }

    public EntData getData() {
        return this.data;
    }

    public void setData(EntData data) {
        this.data = data;
    }
}

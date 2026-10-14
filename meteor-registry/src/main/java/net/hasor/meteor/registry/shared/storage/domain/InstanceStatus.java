/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.shared.storage.domain;

/** 存储查询结果：实例身份和可为空的有效运行信息，不作为存储正文。 */
public class InstanceStatus {
    private EntInstanceCatalogData instance;
    private EntInstanceRuntimeData runtime;

    public EntInstanceCatalogData getInstance() {
        return this.instance;
    }

    public void setInstance(EntInstanceCatalogData instance) {
        this.instance = instance;
    }

    public EntInstanceRuntimeData getRuntime() {
        return this.runtime;
    }

    public void setRuntime(EntInstanceRuntimeData runtime) {
        this.runtime = runtime;
    }
}

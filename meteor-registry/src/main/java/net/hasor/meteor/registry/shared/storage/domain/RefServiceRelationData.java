/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.shared.storage.domain;

/** 服务下独立登记的完整成员；直接携带实例身份与服务声明，不再解引用。 */
public class RefServiceRelationData {
    private EntInstanceRuntimeData instance;
    private EntServiceRuntimeData  service;

    public EntInstanceRuntimeData getInstance() {
        return this.instance;
    }

    public void setInstance(EntInstanceRuntimeData instance) {
        this.instance = instance;
    }

    public EntServiceRuntimeData getService() {
        return this.service;
    }

    public void setService(EntServiceRuntimeData service) {
        this.service = service;
    }
}

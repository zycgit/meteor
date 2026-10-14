/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.client.support;
import java.util.ArrayList;
import java.util.List;
import net.hasor.meteor.registry.shared.storage.domain.EntInstanceRuntimeData;
import net.hasor.meteor.registry.shared.storage.domain.EntServiceRuntimeData;

/** 一次登记采集；单服务推送不携带实例快照，不采集运行配置和地址本。 */
final class RegistrationSnapshot {
    private       String                      instanceId;
    private       EntInstanceRuntimeData      instance;
    private final List<EntServiceRuntimeData> services = new ArrayList<>();

    String getInstanceId() {
        return this.instanceId;
    }

    void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    EntInstanceRuntimeData getInstance() {
        return this.instance;
    }

    void setInstance(EntInstanceRuntimeData instance) {
        this.instance = instance;
    }

    List<EntServiceRuntimeData> getServices() {
        return this.services;
    }
}

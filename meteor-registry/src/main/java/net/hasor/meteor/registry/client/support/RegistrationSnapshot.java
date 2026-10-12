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

/** 一次登记采集；单服务推送不携带实例快照，不采集运行配置和地址本。 */
final class RegistrationSnapshot {
    private       String                    instanceId;
    private       EntInstanceRuntimeData    instance;
    private final List<ServiceRegistration> services = new ArrayList<>();

    public String getInstanceId() {
        return this.instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public EntInstanceRuntimeData getInstance() {
        return this.instance;
    }

    public void setInstance(EntInstanceRuntimeData instance) {
        this.instance = instance;
    }

    public List<ServiceRegistration> getServices() {
        return this.services;
    }
}

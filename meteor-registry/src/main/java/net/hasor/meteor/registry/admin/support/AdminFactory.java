/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin.support;
import java.util.Map;
import java.util.function.Supplier;
import net.hasor.meteor.registry.admin.MetClusterAdmin;
import net.hasor.meteor.registry.admin.MetInstanceAdmin;
import net.hasor.meteor.registry.admin.MetServiceAdmin;
import net.hasor.meteor.registry.shared.storage.StorageAccess;

/** 组装管理接口，存储会话由外层入口管理。 */
public final class AdminFactory {
    private final GovernanceStore     governance;
    private final String              provider;
    private final Map<String, String> scope;

    /** 接收已完成配置的存储位置描述，调用方不再修改。 */
    public AdminFactory(Supplier<StorageAccess> storage, String provider, Map<String, String> scope) {
        this.governance = new GovernanceStore(storage);
        this.provider = provider;
        this.scope = scope;
    }

    public MetClusterAdmin cluster() {
        return new DefaultClusterAdmin(this.governance, this.provider, this.scope);
    }

    public MetServiceAdmin service(String serviceId, String instanceId) {
        return new DefaultServiceAdmin(this.governance, serviceId, instanceId);
    }

    public MetInstanceAdmin instance(String instanceId) {
        return new DefaultInstanceAdmin(this.governance, instanceId);
    }
}

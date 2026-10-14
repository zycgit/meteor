/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin.support;
import net.hasor.meteor.registry.admin.*;
import net.hasor.meteor.registry.shared.storage.domain.DirectoryQuery;
import net.hasor.meteor.registry.shared.storage.domain.EntServiceRuntimeData;
import net.hasor.meteor.registry.shared.storage.domain.InstanceStatus;
import net.hasor.meteor.registry.shared.storage.domain.RecordPage;
import static net.hasor.meteor.registry.admin.support.GovernanceStore.call;

/** 读取实例及其当前服务，保存实例治理配置，不直接操作应用进程。 */
final class DefaultInstanceAdmin implements MetInstanceAdmin {
    private final GovernanceStore governance;
    private final String          instanceId;

    public DefaultInstanceAdmin(GovernanceStore governance, String instanceId) {
        this.governance = governance.scope(instanceId, null);
        this.instanceId = instanceId;
        this.instance();
    }

    @Override
    public MetInstanceInfo instance() {
        return call(() -> {
            InstanceStatus status = this.governance.storage().getInstance(this.instanceId);
            if (status == null) {
                throw new IllegalArgumentException("Unknown instance: " + this.instanceId);
            } else {
                return DirectoryMapping.instance(status);
            }
        });
    }

    @Override
    public boolean isBlock() {
        Boolean desired = this.governance.state().getBlock();
        return desired == null ? this.instance().isBlock() : desired;
    }

    @Override
    public void block() {
        this.governance.updateState(data -> data.setBlock(true));
    }

    @Override
    public void unblock() {
        this.governance.updateState(data -> data.setBlock(false));
    }

    @Override
    public MetPage<MetServiceInfo> getServiceList(MetDirectoryQuery query) {
        return call(() -> {
            DirectoryQuery selection = DirectoryMapping.query(query);
            RecordPage<EntServiceRuntimeData> page = this.governance.storage().instanceServices(this.instanceId, selection);
            return DirectoryMapping.page(page, DirectoryMapping::service);
        });
    }

    @Override
    public MetServiceInfo getServiceById(String serviceId) {
        return call(() -> {
            EntServiceRuntimeData service = this.governance.storage().getInstanceService(this.instanceId, serviceId);
            if (service == null) {
                throw new IllegalArgumentException("Service is not active on instance: " + serviceId);
            } else {
                return DirectoryMapping.service(service);
            }
        });
    }

    // 实例规则

    @Override
    public String fetchRule(MetRuleType type) {
        return this.governance.fetchRule(type);
    }

    @Override
    public void updateRule(MetRuleType type, String rule) {
        this.governance.updateRule(type, rule);
    }

    @Override
    public void resetRule(MetRuleType type) {
        this.updateRule(type, null);
    }

    @Override
    public boolean isRuleEnabled(MetRuleType type) {
        return this.governance.isRuleEnabled(type);
    }

    @Override
    public void enableRule(MetRuleType type) {
        this.governance.setRuleEnabled(type, true);
    }

    @Override
    public void disableRule(MetRuleType type) {
        this.governance.setRuleEnabled(type, false);
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin.support;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.registry.admin.*;
import net.hasor.meteor.registry.shared.storage.domain.*;
import net.hasor.meteor.registry.shared.utils.RegistryGovernanceUtils;
import net.hasor.meteor.registry.shared.utils.RegistryJson;
import static net.hasor.meteor.registry.admin.support.GovernanceStore.call;

/** 读取实例及其当前服务，保存实例治理配置，不直接操作应用进程。 */
final class DefaultInstanceAdmin implements MetInstanceAdmin {
    private final GovernanceStore governance;
    private final EntityRef       instance;

    public DefaultInstanceAdmin(GovernanceStore governance, String instanceId) {
        this.governance = governance;
        this.instance = RegistryGovernanceUtils.instance(instanceId);
        // 获取实例管理接口时，确认目标实例当前存在。
        this.snapshot();
    }

    // 实例状态与当前运行配置

    private EntInstanceRuntimeData snapshot() {
        return call(() -> {
            EntityRef ref = RegistryGovernanceUtils.runtime(this.instance);
            EntityRecord record = this.governance.storage().getEntity(ref);
            if (record == null) {
                throw new IllegalArgumentException("Unknown instance: " + this.instance.id());
            }
            return (EntInstanceRuntimeData) record.getData();
        });
    }

    @Override
    public MetInstanceInfo instance() {
        EntInstanceRuntimeData snapshot = this.snapshot();
        MetInstanceInfo result = RegistryJson.copy(snapshot, MetInstanceInfo.class);
        result.setInstanceId(this.instance.id());
        result.setManagementAddress(snapshot.getManagementAddresses());
        return result;
    }

    @Override
    public Map<String, Object> settings() {
        return this.snapshot().getSettings();
    }

    @Override
    public boolean isBlock() {
        EntInstanceRuntimeData reported = this.snapshot();
        return call(() -> {
            EntityRecord record = this.governance.storage().getEntity(this.instance);
            if (record != null && record.getData() != null) {
                EntInstanceConfigurationData desired = (EntInstanceConfigurationData) record.getData();
                if (desired.getBlock() != null) {
                    return desired.getBlock();
                }
            }
            return reported.isBlock();
        });
    }

    @Override
    public void block() {
        this.governance.update(this.instance, EntInstanceConfigurationData.class, data -> data.setBlock(true));
    }

    @Override
    public void unblock() {
        this.governance.update(this.instance, EntInstanceConfigurationData.class, data -> data.setBlock(false));
    }

    // 当前实例声明的服务和地址本

    @Override
    public MetServiceInfo[] getServiceList() {
        this.snapshot();
        return call(() -> {
            EntityQuery query = new EntityQuery();
            query.setType(EntityType.SERVICE);
            query.setParent(this.instance);
            query.setSection(EntitySection.RUNTIME);
            List<MetServiceInfo> result = new ArrayList<>();
            for (EntityRecord record : RegistryGovernanceUtils.entities(this.governance.storage(), query)) {
                EntInstanceServiceRuntimeData runtime = (EntInstanceServiceRuntimeData) record.getData();
                result.add(serviceInfo(record.getId(), runtime.getService()));
            }
            return result.toArray(MetServiceInfo[]::new);
        });
    }

    @Override
    public MetServiceInfo getServiceById(String serviceId) {
        this.snapshot();
        return serviceInfo(serviceId, this.serviceRuntime(serviceId).getService());
    }

    @Override
    public AddressInfo getAddressInfo(String serviceId) {
        return RegistryJson.copy(this.serviceRuntime(serviceId).getAddressBook(), AddressInfo.class);
    }

    private EntInstanceServiceRuntimeData serviceRuntime(String serviceId) {
        return call(() -> {
            EntityRef local = RegistryGovernanceUtils.localService(this.instance.id(), serviceId);
            EntityRecord record = this.governance.storage().getEntity(RegistryGovernanceUtils.runtime(local));
            if (record == null) {
                throw new IllegalArgumentException("Service is not active on instance: " + serviceId);
            }
            return (EntInstanceServiceRuntimeData) record.getData();
        });
    }

    private static MetServiceInfo serviceInfo(String serviceId, EntServiceDeclarationData declaration) {
        MetServiceInfo result = RegistryJson.copy(declaration, MetServiceInfo.class);
        result.setServiceId(serviceId);
        boolean provider = StringUtils.equals("Provider", declaration.getRole());
        result.setProviderCount(provider ? 1 : 0);
        result.setConsumerCount(provider ? 0 : 1);
        return result;
    }

    // 实例级请求和响应选项

    @Override
    public void setRequestOption(String name, String value) {
        requireOption(name, value);
        this.governance.update(this.instance, EntInstanceConfigurationData.class, data -> {
            data.getRequestOptions().put(name, value);
        });
    }

    @Override
    public void removeRequestOption(String name) {
        requireName(name);
        this.governance.update(this.instance, EntInstanceConfigurationData.class, data -> {
            data.getRequestOptions().put(name, null);
        });
    }

    @Override
    public void setResponseOption(String name, String value) {
        requireOption(name, value);
        this.governance.update(this.instance, EntInstanceConfigurationData.class, data -> {
            data.getResponseOptions().put(name, value);
        });
    }

    @Override
    public void removeResponseOption(String name) {
        requireName(name);
        this.governance.update(this.instance, EntInstanceConfigurationData.class, data -> {
            data.getResponseOptions().put(name, null);
        });
    }

    private static void requireOption(String name, String value) {
        requireName(name);
        if (value == null) {
            throw new IllegalArgumentException("Option value is required");
        }
    }

    private static void requireName(String name) {
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("Option name must not be blank");
        }
    }

    // 实例规则

    @Override
    public String fetchRule(MetRuleType type) {
        return this.governance.fetchRule(this.instance, type);
    }

    @Override
    public void updateRule(MetRuleType type, String rule) {
        this.governance.updateRule(this.instance, type, rule);
    }

    @Override
    public void resetRule(MetRuleType type) {
        this.updateRule(type, null);
    }

    @Override
    public boolean isRuleEnabled(MetRuleType type) {
        return this.governance.isRuleEnabled(this.instance, type);
    }

    @Override
    public void enableRule(MetRuleType type) {
        this.governance.setRuleEnabled(this.instance, type, true);
    }

    @Override
    public void disableRule(MetRuleType type) {
        this.governance.setRuleEnabled(this.instance, type, false);
    }
}

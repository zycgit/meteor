/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin.support;
import java.util.*;
import net.hasor.cobble.ExceptionUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.registry.admin.*;
import net.hasor.meteor.registry.shared.rpc.ValidationData;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import net.hasor.meteor.registry.shared.utils.RegistryJson;
import static net.hasor.meteor.registry.admin.support.GovernanceStore.call;

/** 查询集群服务与实例目录，管理全局治理配置和注册中心信息。 */
final class DefaultClusterAdmin implements MetClusterAdmin {
    private final GovernanceStore     governance;
    private final String              provider;
    private final Map<String, String> scope;

    public DefaultClusterAdmin(GovernanceStore governance, String provider, Map<String, String> scope) {
        this.governance = governance;
        this.provider = provider;
        this.scope = scope;
    }

    // 注册中心信息与连通状态

    @Override
    public RegistryInfo info() {
        this.governance.storage();
        RegistryInfo result = new RegistryInfo();
        result.setProvider(this.provider);
        result.setScope(new LinkedHashMap<>(this.scope));
        return result;
    }

    @Override
    public RegistryStatus status() {
        RegistryStatus result = new RegistryStatus();
        result.setCheckedAt(System.currentTimeMillis());
        try {
            DirectoryQuery query = new DirectoryQuery();
            query.setLimit(1);
            this.governance.storage().applications(query);
            result.setReachable(true);
        } catch (Exception failure) {
            result.setError(ExceptionUtils.getStackTrace(failure));
        }
        return result;
    }

    // 持久应用、实例和服务目录

    @Override
    public MetPage<MetServiceInfo> services(String filter, MetDirectoryQuery query) {
        return call(() -> {
            StorageAccess storage = this.governance.storage();
            DirectoryQuery selection = DirectoryMapping.query(query);
            selection.setSearch(filter);
            RecordPage<EntServiceCatalogData> page = storage.services(selection);

            // 只补充当前页的成员摘要，不展开其他服务或实例。
            List<String> ids = new ArrayList<>();
            for (EntServiceCatalogData service : page.getItems()) {
                ids.add(service.getServiceId());
            }

            Map<String, ServiceSummary> summaries = storage.serviceSummaries(ids);
            return DirectoryMapping.page(page, service -> {
                ServiceSummary summary = summaries.get(service.getServiceId());
                return DirectoryMapping.service(service, summary);
            });
        });
    }

    @Override
    public MetPage<String> applications(MetDirectoryQuery query) {
        return call(() -> {
            DirectoryQuery selection = DirectoryMapping.query(query);
            RecordPage<EntApplicationCatalogData> page = this.governance.storage().applications(selection);
            return DirectoryMapping.page(page, EntApplicationCatalogData::getApplicationName);
        });
    }

    @Override
    public MetPage<MetInstanceInfo> instances(String applicationName, MetDirectoryQuery query) {
        String application = StringUtils.isBlank(applicationName) ? null : applicationName;
        return call(() -> {
            DirectoryQuery selection = DirectoryMapping.query(query);
            RecordPage<InstanceStatus> page = this.governance.storage().instances(application, selection);
            return DirectoryMapping.page(page, DirectoryMapping::instance);
        });
    }

    @Override
    public String[] units(String application) {
        return call(() -> this.governance.storage().applicationUnits(application));
    }

    // 全局规则和元信息

    @Override
    public String fetchRule(MetRuleType type) {
        return this.governance.fetchRule(type);
    }

    @Override
    public void resetRule(MetRuleType type) {
        this.updateRule(type, null);
    }

    @Override
    public MetValidationResult validateRule(MetRuleType type, String rule) {
        ValidationData validation = this.governance.validateRule(type, rule);
        return RegistryJson.copy(validation, MetValidationResult.class);
    }

    @Override
    public void updateRule(MetRuleType type, String rule) {
        this.governance.updateRule(type, rule);
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

    @Override
    public Map<String, Object> fetchMeta() {
        return this.governance.fetchMeta();
    }

    @Override
    public void putMeta(String key, Object value) {
        this.putMeta(Collections.singletonMap(key, value));
    }

    @Override
    public void putMeta(Map<String, Object> values) {
        this.governance.putMeta(values);
    }

    @Override
    public void removeMeta(String... keys) {
        this.governance.removeMeta(keys);
    }
}

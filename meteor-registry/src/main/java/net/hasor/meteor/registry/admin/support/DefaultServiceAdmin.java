/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin.support;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.admin.*;
import net.hasor.meteor.registry.shared.rpc.ValidationData;
import net.hasor.meteor.registry.shared.storage.DirectoryFilter;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import net.hasor.meteor.registry.shared.utils.RegistryJson;
import static net.hasor.meteor.registry.admin.support.GovernanceStore.call;

/** 查询当前服务的声明和成员，管理服务级或指定实例上的服务治理配置。 */
final class DefaultServiceAdmin implements MetServiceAdmin {
    private final GovernanceStore governance;
    private final String          serviceId;
    private final String          instanceId;

    public DefaultServiceAdmin(GovernanceStore governance, String serviceId, String instanceId) {
        if (StringUtils.isBlank(serviceId)) {
            throw new IllegalArgumentException("Service ID must not be blank");
        }

        this.governance = governance.scope(instanceId, serviceId);
        this.serviceId = serviceId;
        this.instanceId = instanceId;
    }

    @Override
    public String[] units() {
        return call(() -> {
            StorageAccess storage = this.governance.storage();
            if (this.instanceId == null) {
                return storage.serviceUnits(this.serviceId);
            }

            InstanceStatus status = storage.getInstance(this.instanceId);
            if (status == null || status.getInstance().getUnitName() == null) {
                return new String[0];
            } else {
                return new String[] { status.getInstance().getUnitName() };
            }
        });
    }

    @Override
    public MetPage<MetServiceMember> providers(MetEndpointFilter filter, MetDirectoryQuery query) {
        return this.members(MetServiceType.Provider, filter, query);
    }

    @Override
    public MetPage<MetServiceMember> consumers(MetEndpointFilter filter, MetDirectoryQuery query) {
        return this.members(MetServiceType.Consumer, filter, query);
    }

    private MetPage<MetServiceMember> members(MetServiceType role, MetEndpointFilter filter, MetDirectoryQuery query) {
        return call(() -> {
            DirectoryQuery selection = DirectoryMapping.query(query);
            MetPage<MetServiceMember> result = new MetPage<>();
            String selected = this.instanceId;
            if (filter != null && StringUtils.isNotBlank(filter.getInstanceId())) {
                if (selected != null && !StringUtils.equals(selected, filter.getInstanceId())) {
                    return result;
                }
                selected = filter.getInstanceId();
            }

            // 指定实例时直接读取成员，不遍历服务的整个成员目录。
            StorageAccess storage = this.governance.storage();
            if (selected != null) {
                RefServiceRelationData record = storage.getMember(this.serviceId, role, selected);
                if (record == null || selection.getCursor() != null) {
                    return result;
                }

                if (this.matchesDirectory(record, selection)) {
                    this.addMember(record, filter, result.getItems());
                }
                return result;
            }

            // 端点条件在成员映射后筛选，每批只读取剩余容量，避免跨页丢弃尚未返回的成员。
            int limit = selection.getLimit();
            do {
                selection.setLimit(limit - result.getItems().size());
                RecordPage<RefServiceRelationData> page = storage.members(this.serviceId, role, selection);
                for (RefServiceRelationData record : page.getItems()) {
                    this.addMember(record, filter, result.getItems());
                }
                selection.setCursor(page.getNextCursor());
            } while (result.getItems().size() < limit && selection.getCursor() != null);

            result.setNextCursor(selection.getCursor());
            return result;
        });
    }

    private boolean matchesDirectory(RefServiceRelationData record, DirectoryQuery query) {
        EntInstanceRuntimeData instance = record.getInstance();
        String unit = query.getUnitName();
        if (StringUtils.isNotBlank(unit) && !StringUtils.equals(unit, instance.getUnitName())) {
            return false;
        }
        if (!DirectoryFilter.protocol(record, query.getProtocol())) {
            return false;
        }
        if (StringUtils.isBlank(query.getSearch())) {
            return true;
        }

        String identity = instance.getInstanceId() + " " + instance.getIpAddress() + " " + instance.getProcessId();
        String text = DirectoryFilter.text(record, query.getField(), identity);
        String search = query.getSearch().toLowerCase(Locale.ROOT);
        return text.toLowerCase(Locale.ROOT).contains(search);
    }

    @Override
    public MetServiceInfo info() {
        return call(() -> {
            StorageAccess storage = this.governance.storage();
            if (this.instanceId != null) {
                EntServiceRuntimeData service = storage.getInstanceService(this.instanceId, this.serviceId);
                if (service == null) {
                    EntServiceCatalogData declaration = storage.getService(this.serviceId);
                    if (storage.getInstance(this.instanceId) == null || declaration == null) {
                        throw new IllegalArgumentException("Unknown instance or service: " + this.serviceId);
                    } else {
                        return DirectoryMapping.service(declaration, null);
                    }
                }
                return DirectoryMapping.service(service);
            }

            EntServiceCatalogData service = storage.getService(this.serviceId);
            if (service == null) {
                throw new IllegalArgumentException("Unknown service: " + this.serviceId);
            }

            ServiceSummary summary = storage.serviceSummaries(List.of(this.serviceId)).get(this.serviceId);
            return DirectoryMapping.service(service, summary);
        });
    }

    private void addMember(RefServiceRelationData record, MetEndpointFilter filter, List<MetServiceMember> result) {
        if (record != null) {
            MetServiceMember member = DirectoryMapping.member(record);
            if (MemberMatcherUtils.matches(member, filter)) {
                result.add(member);
            }
        }
    }

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
    public boolean isRule(MetRuleType type) {
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
    public boolean isEnable() {
        EntConfigurationData service = this.governance.scope(null, this.serviceId).state();
        boolean enabled = !Boolean.FALSE.equals(service.getEnabled());
        if (this.instanceId != null) {
            enabled &= !Boolean.FALSE.equals(this.governance.state().getEnabled());
        }
        return enabled;
    }

    @Override
    public void enable() {
        this.governance.updateState(data -> data.setEnabled(true));
    }

    @Override
    public void disable() {
        this.governance.updateState(data -> data.setEnabled(false));
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

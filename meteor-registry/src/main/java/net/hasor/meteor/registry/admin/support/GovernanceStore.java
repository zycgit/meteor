/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.admin.support;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.registry.admin.MetRuleType;
import net.hasor.meteor.registry.shared.rpc.RuleTypeData;
import net.hasor.meteor.registry.shared.rpc.ValidationData;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import net.hasor.meteor.registry.shared.utils.RegistryGovernanceUtils;
import net.hasor.meteor.registry.shared.utils.RegistryJson;

/** 一个明确范围的治理存取；独立规则、元信息和开关均只重试单条 CAS 冲突。 */
final class GovernanceStore {
    private final Supplier<StorageAccess> storage;
    private final String                  instanceId;
    private final String                  serviceId;

    public GovernanceStore(Supplier<StorageAccess> storage, String instanceId, String serviceId) {
        this.storage = storage;
        this.instanceId = instanceId;
        this.serviceId = serviceId;
    }

    public GovernanceStore scope(String instanceId, String serviceId) {
        return new GovernanceStore(this.storage, instanceId, serviceId);
    }

    public StorageAccess storage() {
        return this.storage.get();
    }

    public ValidationData validateRule(MetRuleType type, String rule) {
        this.storage();
        RuleTypeData selected = type == null ? null : RuleTypeData.valueOf(type.name());
        return RegistryGovernanceUtils.validateRule(selected, rule);
    }

    private EntityRecord<EntRuleConfigurationData> rule(MetRuleType type) throws Exception {
        RuleKind kind = RuleKind.valueOf(Objects.requireNonNull(type, "rule type").name());
        return this.storage().getRule(this.instanceId, this.serviceId, kind);
    }

    public String fetchRule(MetRuleType type) {
        return call(() -> {
            EntityRecord<EntRuleConfigurationData> rule = this.rule(type);
            return rule == null ? null : rule.getData().getRule();
        });
    }

    public void updateRule(MetRuleType type, String rule) {
        ValidationData validation = this.validateRule(type, rule);
        if (!validation.isValid()) {
            throw new IllegalArgumentException(String.join("; ", validation.getErrors()));
        } else {
            this.updateRuleRecord(type, data -> data.setRule(rule));
        }
    }

    public boolean isRuleEnabled(MetRuleType type) {
        return call(() -> {
            EntityRecord<EntRuleConfigurationData> rule = this.rule(type);
            return rule == null || rule.getData().isEnabled();
        });
    }

    public void setRuleEnabled(MetRuleType type, boolean enabled) {
        this.updateRuleRecord(type, data -> data.setEnabled(enabled));
    }

    private void updateRuleRecord(MetRuleType type, Consumer<EntRuleConfigurationData> change) {
        RuleKind kind = RuleKind.valueOf(Objects.requireNonNull(type, "rule type").name());
        this.update(() -> this.rule(type), EntRuleConfigurationData::new, change, value -> {
            this.storage().saveRule(this.instanceId, this.serviceId, kind, value);
        });
    }

    public EntConfigurationData state() {
        return call(() -> {
            EntityRecord<EntConfigurationData> value = this.storage().getConfiguration(this.instanceId, this.serviceId);
            return value == null ? new EntConfigurationData() : value.getData();
        });
    }

    public void updateState(Consumer<EntConfigurationData> change) {
        this.update(() -> this.storage().getConfiguration(this.instanceId, this.serviceId), EntConfigurationData::new, change, value -> {
            this.storage().saveConfiguration(this.instanceId, this.serviceId, value);
        });
    }

    public Map<String, Object> fetchMeta() {
        return call(() -> {
            EntityRecord<EntMetadataData> record = this.storage().getMetadata(this.instanceId, this.serviceId);
            return record == null ? new LinkedHashMap<>() : record.getData().getMetadata();
        });
    }

    public void putMeta(Map<String, Object> values) {
        validateMetadata(values);
        this.updateMetadata(data -> {
            data.putAll(RegistryJson.copy(values, Map.class));
            validateMetadata(data);
        });
    }

    public void removeMeta(String[] keys) {
        if (keys == null) {
            throw new IllegalArgumentException("Metadata keys must not be null");
        }
        for (String key : keys) {
            if (StringUtils.isBlank(key)) {
                throw new IllegalArgumentException("Metadata key must not be blank");
            }
        }
        if (keys.length > 0) {
            this.updateMetadata(data -> {
                for (String key : keys) {
                    data.remove(key);
                }
            });
        }
    }

    private void updateMetadata(Consumer<Map<String, Object>> change) {
        this.update(() -> this.storage().getMetadata(this.instanceId, this.serviceId), EntMetadataData::new, data -> change.accept(data.getMetadata()), value -> {
            this.storage().saveMetadata(this.instanceId, this.serviceId, value);
        });
    }

    private static void validateMetadata(Map<String, Object> values) {
        if (values == null || values.size() > MetBindInfo.MAX_METADATA_SIZE) {
            throw new IllegalArgumentException("Metadata map is required and cannot exceed " + MetBindInfo.MAX_METADATA_SIZE);
        }

        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (StringUtils.isBlank(entry.getKey()) || entry.getValue() == null) {
                throw new IllegalArgumentException("Metadata key must be nonblank and value must not be null");
            }
        }
    }

    private interface Writer<T> {
        void save(EntityRecord<T> value) throws Exception;
    }

    private <T> void update(Callable<EntityRecord<T>> reader, Supplier<T> initial, Consumer<T> change, Writer<T> writer) {
        call(() -> {
            // 尚无提供者的服务也可以先建立持久目录并进行治理。
            if (this.serviceId != null && this.storage().getService(this.serviceId) == null) {
                EntServiceCatalogData service = new EntServiceCatalogData();
                service.setServiceId(this.serviceId);
                this.storage().saveService(service);
            }

            for (int attempt = 0; attempt < 8; attempt++) {
                EntityRecord<T> value = reader.call();
                if (value == null) {
                    value = new EntityRecord<>();
                    value.setData(initial.get());
                }

                change.accept(value.getData());
                try {
                    writer.save(value);
                    return null;
                } catch (ConcurrentModificationException conflict) {
                    if (attempt == 7) {
                        throw conflict;
                    }
                }
            }
            throw new IllegalStateException("Governance update failed");
        });
    }

    public static <T> T call(Callable<T> action) {
        try {
            return action.call();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Registry operation failed", failure);
        }
    }
}

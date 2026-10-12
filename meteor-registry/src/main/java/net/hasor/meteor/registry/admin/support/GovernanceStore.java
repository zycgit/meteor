/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin.support;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.hasor.cobble.ClassUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.registry.admin.MetRuleType;
import net.hasor.meteor.registry.shared.rpc.RuleTypeData;
import net.hasor.meteor.registry.shared.rpc.ValidationData;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import net.hasor.meteor.registry.shared.utils.RegistryGovernanceUtils;
import net.hasor.meteor.registry.shared.utils.RegistryJson;

/** 多个管理维度共用的治理记录读写和 CAS 合并，不负责业务查询或操作范围选择。 */
final class GovernanceStore {
    private final Supplier<StorageAccess> storage;

    public GovernanceStore(Supplier<StorageAccess> storage) {
        this.storage = storage;
    }

    public StorageAccess storage() {
        return this.storage.get();
    }

    // 规则记录

    public ValidationData validateRule(MetRuleType type, String rule) {
        this.storage();
        return RegistryGovernanceUtils.validateRule(ruleType(type), rule);
    }

    public String fetchRule(EntityRef owner, MetRuleType type) {
        return call(() -> {
            EntityRef ref = RegistryGovernanceUtils.ruleRef(owner, ruleType(type));
            EntityRecord record = this.storage().getEntity(ref);
            return record == null ? null : ((EntRuleConfigurationData) record.getData()).getRule();
        });
    }

    public void updateRule(EntityRef owner, MetRuleType type, String rule) {
        ValidationData validation = this.validateRule(type, rule);
        if (!validation.isValid()) {
            throw new IllegalArgumentException(String.join("; ", validation.getErrors()));
        }
        EntityRef ref = RegistryGovernanceUtils.ruleRef(owner, ruleType(type));
        this.update(ref, EntRuleConfigurationData.class, data -> {
            data.setRule(rule);
        });
    }

    public boolean isRuleEnabled(EntityRef owner, MetRuleType type) {
        return call(() -> {
            EntityRef ref = RegistryGovernanceUtils.ruleRef(owner, ruleType(type));
            EntityRecord record = this.storage().getEntity(ref);
            return record == null || ((EntRuleConfigurationData) record.getData()).isEnabled();
        });
    }

    public void setRuleEnabled(EntityRef owner, MetRuleType type, boolean enabled) {
        EntityRef ref = RegistryGovernanceUtils.ruleRef(owner, ruleType(type));
        this.update(ref, EntRuleConfigurationData.class, data -> data.setEnabled(enabled));
    }

    private static RuleTypeData ruleType(MetRuleType type) {
        return type == null ? null : RuleTypeData.valueOf(type.name());
    }

    // 所属对象的元信息和配置

    public Map<String, Object> fetchMeta(EntityRef owner) {
        return call(() -> {
            EntityRecord record = this.storage().getEntity(owner);
            if (record == null || record.getData() == null) {
                return new LinkedHashMap<>();
            }

            Map<String, Object> metadata;
            if (owner.type() == EntityType.GLOBAL) {
                metadata = ((EntGlobalConfigurationData) record.getData()).getMetadata();
            } else {
                metadata = ((EntServiceConfigurationData) record.getData()).getMetadata();
            }
            return RegistryJson.copy(metadata, Map.class);
        });
    }

    public void putMeta(EntityRef owner, Map<String, Object> values) {
        validateMetadata(values);
        this.updateMetadata(owner, metadata -> {
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                metadata.put(entry.getKey(), RegistryJson.copy(entry.getValue(), Object.class));
            }
            validateMetadata(metadata);
        });
    }

    public void removeMeta(EntityRef owner, String[] keys) {
        if (keys == null) {
            throw new IllegalArgumentException("Metadata keys must not be null");
        }
        String[] selected = keys.clone();
        for (String key : selected) {
            if (StringUtils.isBlank(key)) {
                throw new IllegalArgumentException("Metadata key must not be blank");
            }
        }
        if (selected.length == 0) {
            return;
        }
        this.updateMetadata(owner, metadata -> {
            for (String key : selected) {
                metadata.remove(key);
            }
        });
    }

    private static void validateMetadata(Map<String, Object> values) {
        if (values == null || values.size() > MetBindInfo.MAX_METADATA_SIZE) {
            String limit = "Metadata map is required and cannot exceed " + MetBindInfo.MAX_METADATA_SIZE;
            throw new IllegalArgumentException(limit + " entries");
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (StringUtils.isBlank(entry.getKey())) {
                throw new IllegalArgumentException("Metadata key must not be blank");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("Use removeMeta to restore an inherited value");
            }
        }
    }

    private void updateMetadata(EntityRef owner, Consumer<Map<String, Object>> change) {
        if (owner.type() == EntityType.GLOBAL) {
            this.update(owner, EntGlobalConfigurationData.class, data -> change.accept(data.getMetadata()));
        } else if (owner.parent() == null) {
            this.update(owner, EntServiceCatalogData.class, data -> change.accept(data.getMetadata()));
        } else {
            this.update(owner, EntServiceConfigurationData.class, data -> change.accept(data.getMetadata()));
        }
    }

    // CAS 提交

    public <T extends EntData> void update(EntityRef ref, Class<T> type, Consumer<T> change) {
        call(() -> {
            StorageAccess storage = this.storage();
            // 实例服务首次保存规则时，保留其持久配置归属。
            boolean localRule = ref.type() == EntityType.RULE && ref.parent().parent() != null;
            if (localRule) {
                this.ensureOwner(storage, ref.parent());
            }

            // 只重试 CAS 冲突；重读当前正文再合并，保留其他管理操作提交的字段。
            for (int attempt = 0; attempt < 8; attempt++) {
                EntityRecord previous = storage.getEntity(ref);
                T data;
                if (previous == null || previous.getData() == null) {
                    data = ClassUtils.newInstance(type);
                } else {
                    data = RegistryJson.copy(previous.getData(), type);
                }
                change.accept(data);
                EntityRecord next = new EntityRecord(ref);
                next.setData(data);
                if (previous != null && RegistryJson.sameData(next.getData(), previous.getData())) {
                    return null;
                }

                next.setRevision(previous == null ? "" : previous.getRevision());
                try {
                    storage.saveEntity(next);
                } catch (ConcurrentModificationException conflict) {
                    if (attempt == 7) {
                        throw conflict;
                    }
                    continue;
                }
                return null;
            }
            throw new IllegalStateException("Governance update failed");
        });
    }

    private void ensureOwner(StorageAccess storage, EntityRef owner) throws Exception {
        if (storage.getEntity(owner) != null) {
            return;
        }

        try {
            storage.saveEntity(new EntityRecord(owner));
        } catch (ConcurrentModificationException conflict) {
            if (storage.getEntity(owner) == null) {
                throw conflict;
            }
        }
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

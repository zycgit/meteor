/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.nacos;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.codec.MD5;
import net.hasor.meteor.registry.shared.storage.domain.EntRuleConfigurationData;
import net.hasor.meteor.registry.shared.storage.domain.EntityRecord;
import net.hasor.meteor.registry.shared.storage.domain.RuleKind;
import net.hasor.meteor.registry.shared.utils.RegistryJson;

/** 规则正文原样保存 XML；规则开关归入对应范围的详情，不另建开关节点。 */
final class NacosRuleStore {
    private final NacosConfigStore configs;
    private final NacosGovernance  governance;

    NacosRuleStore(NacosConfigStore configs, NacosGovernance governance) {
        this.configs = configs;
        this.governance = governance;
    }

    EntityRecord<EntRuleConfigurationData> get(String instanceId, String serviceId, RuleKind kind) throws Exception {
        return this.read(instanceId, serviceId, kind, false);
    }

    private EntityRecord<EntRuleConfigurationData> read(String instanceId, String serviceId, RuleKind kind, boolean fresh) throws Exception {
        String scope = NacosKeyUtils.governance(instanceId, serviceId);
        String field = "rule." + kind.name();
        String body;
        Map<?, ?> detail;
        if (instanceId == null) {
            body = this.configs.read(scope + "." + field);
            String key = controlKey(null, serviceId);
            detail = this.configs.get(key, Map.class);
        } else {
            Map<String, String> fields = this.governance.readRuntime(scope, instanceId, fresh);
            if (fields == null) {
                return null;
            }
            body = fields.get(field);
            String key = this.runtimeControlKey(instanceId, serviceId);
            Map<String, String> metadata = this.governance.readRuntime(key, instanceId, fresh);
            detail = runtimeDetail(metadata);
        }
        if (StringUtils.isBlank(body)) {
            body = cleared(detail).contains(kind.name()) ? "" : null;
        }
        Boolean enabled = controls(detail).get(kind.name());
        if (body == null && enabled == null) {
            return null;
        }

        // 存储契约的版本由正文和显式开关共同生成，不向 XML 注入版本字段。
        String version = RegistryJson.write(Arrays.asList(body, enabled));
        EntityRecord<EntRuleConfigurationData> result = new EntityRecord<>();
        result.setRevision(MD5.getMD5(version.getBytes(StandardCharsets.UTF_8)));
        EntRuleConfigurationData data = new EntRuleConfigurationData();
        data.setRule(body);
        data.setEnabled(enabled == null || enabled);
        result.setData(data);
        return result;
    }

    boolean save(String instanceId, String serviceId, RuleKind kind, EntityRecord<EntRuleConfigurationData> value, boolean delete) throws Exception {
        String scope = NacosKeyUtils.governance(instanceId, serviceId);
        long deadline = NacosGovernance.writeDeadline();
        return this.governance.locked(scope, () -> {
            // 同一范围的规则变更串行执行，旧内容版本不能覆盖新值。
            EntityRecord<EntRuleConfigurationData> previous = this.read(instanceId, serviceId, kind, true);
            if (delete && previous == null) {
                return false;
            }
            String revision = previous == null ? null : previous.getRevision();
            if (!StringUtils.equals(revision, value.getRevision())) {
                throw new ConcurrentModificationException("Rule changed: " + scope + "." + kind);
            }
            EntRuleConfigurationData old = previous == null ? null : previous.getData();
            EntRuleConfigurationData updated = delete ? null : value.getData();
            if (instanceId == null) {
                this.savePersistentRule(serviceId, kind, old, updated);
            } else {
                this.saveRuntimeRule(instanceId, serviceId, kind, old, updated, deadline);
            }
            return true;
        });
    }

    private void savePersistentRule(String serviceId, RuleKind kind, EntRuleConfigurationData previous, EntRuleConfigurationData value) throws Exception {
        String scope = NacosKeyUtils.governance(null, serviceId);
        String key = scope + ".rule." + kind.name();
        String old = this.configs.read(key);
        String expectedBody = previous == null ? null : previous.getRule();
        String actualBody = StringUtils.isBlank(old) ? null : old;
        if (StringUtils.isBlank(expectedBody)) {
            expectedBody = null;
        }
        if (!StringUtils.equals(expectedBody, actualBody)) {
            throw new ConcurrentModificationException("Rule changed: " + key);
        }

        // 正文只写 XML。Nacos 拒绝空白 Config，清除时删除正文 Key。
        String body = value == null ? null : value.getRule();
        if (StringUtils.isBlank(body)) {
            if (old != null) {
                this.configs.removeRule(key);
            }
        } else if (!StringUtils.equals(old, body)) {
            if (!this.configs.compareAndSetRule(key, old, body)) {
                throw new ConcurrentModificationException("Rule changed: " + key);
            }
        }

        // 正文与开关跨节点非事务，合并详情后由订阅最终收敛。
        Boolean enabled = value == null ? null : value.isEnabled();
        this.savePersistentControl(serviceId, kind, enabled, body);
    }

    private void saveRuntimeRule(String instanceId, String serviceId, RuleKind kind, EntRuleConfigurationData previous, EntRuleConfigurationData value, long deadline) throws Exception {
        String key = this.runtimeControlKey(instanceId, serviceId);
        Map<String, String> detail = this.governance.readRuntime(key, instanceId, true);
        if (detail == null) {
            throw new IllegalStateException("Rule target is not registered: " + key);
        }
        String body = value == null ? null : value.getRule();
        Boolean enabled = value == null ? null : value.isEnabled();
        Map<String, Object> state = runtimeDetail(detail);
        updateControl(state, kind, enabled, body);

        // 规则正文仍归治理节点，开关只更新实例主记录或当前角色的成员关系。
        String oldBody = previous == null ? null : previous.getRule();
        if (!StringUtils.equals(oldBody, body)) {
            String scope = NacosKeyUtils.governance(instanceId, serviceId);
            String content = body == null ? "" : body;
            this.governance.writeRuntime(scope, instanceId, Map.of("rule." + kind.name(), content), deadline);
        }
        Map<String, String> fields = encodeControls(state);
        if (!detail.entrySet().containsAll(fields.entrySet())) {
            this.governance.writeRuntime(key, instanceId, fields, deadline);
        }
    }

    private void savePersistentControl(String serviceId, RuleKind kind, Boolean enabled, String body) throws Exception {
        String key = controlKey(null, serviceId);
        this.configs.update(key, Map.class, previous -> {
            Map<String, Object> detail = previous == null ? new LinkedHashMap<>() : previous;
            if (serviceId != null) {
                detail.put("serviceId", serviceId);
            }
            updateControl(detail, kind, enabled, body);
            return detail;
        });
    }

    void moveControls(String instanceId, String source, String target, long deadline) throws Exception {
        // 调用方持有范围锁，并在搬迁完成后撤回旧关系。
        Map<String, String> previous = this.governance.readRuntime(source, instanceId, true);
        Map<String, String> fields = encodeControls(runtimeDetail(previous));
        this.governance.writeRuntime(target, instanceId, fields, deadline);
    }

    private String runtimeControlKey(String instanceId, String serviceId) throws Exception {
        if (serviceId == null) {
            return controlKey(instanceId, null);
        }
        String prefix = NacosKeyUtils.service(serviceId);
        if (this.governance.readRuntime(prefix + ".providers", instanceId, false) != null) {
            return prefix + ".providers";
        }
        return prefix + ".consumers";
    }

    private static String controlKey(String instanceId, String serviceId) {
        if (instanceId != null) {
            return NacosKeyUtils.instance(instanceId);
        }
        return serviceId == null ? NacosKeyUtils.governance(null, null) + ".state" : NacosKeyUtils.service(serviceId);
    }

    private static Map<String, Boolean> controls(Map<?, ?> detail) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        if (detail != null && detail.get("ruleEnabled") instanceof Map<?, ?> values) {
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                result.put((String) entry.getKey(), (Boolean) entry.getValue());
            }
        }
        return result;
    }

    private static Map<String, Object> runtimeDetail(Map<String, String> metadata) throws Exception {
        Map<String, Object> detail = new LinkedHashMap<>();
        if (metadata != null) {
            for (String field : Arrays.asList("ruleEnabled", "ruleCleared")) {
                String content = metadata.get(field);
                if (StringUtils.isNotBlank(content)) {
                    detail.put(field, RegistryJson.read(content, Object.class));
                }
            }
        }
        return detail;
    }

    private static Set<String> cleared(Map<?, ?> detail) {
        Set<String> cleared = new LinkedHashSet<>();
        if (detail != null && detail.get("ruleCleared") instanceof Collection<?> values) {
            for (Object value : values) {
                cleared.add((String) value);
            }
        }
        return cleared;
    }

    private static Map<String, String> encodeControls(Map<?, ?> detail) throws Exception {
        // 空集合用于覆盖 Naming 管理元信息缓存，不能仅省略原有字段。
        return Map.of("ruleEnabled", RegistryJson.write(controls(detail)), //
                "ruleCleared", RegistryJson.write(cleared(detail)));
    }

    private static void updateControl(Map<String, Object> detail, RuleKind kind, Boolean enabled, String body) {
        Map<String, Boolean> switches = controls(detail);
        if (enabled == null) {
            switches.remove(kind.name());
        } else {
            switches.put(kind.name(), enabled);
        }
        detail.put("ruleEnabled", switches);

        // 现有 API 区分 null（恢复继承）与空正文（显式清空），标识也随详情保存。
        Set<String> cleared = cleared(detail);
        if (body != null && StringUtils.isBlank(body)) {
            cleared.add(kind.name());
        } else {
            cleared.remove(kind.name());
        }
        if (cleared.isEmpty()) {
            detail.remove("ruleCleared");
        } else {
            detail.put("ruleCleared", cleared);
        }
    }
}

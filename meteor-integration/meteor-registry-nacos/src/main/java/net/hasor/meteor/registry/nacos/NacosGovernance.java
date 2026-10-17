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
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import com.alibaba.nacos.api.lock.LockService;
import com.alibaba.nacos.api.lock.NacosLockFactory;
import com.alibaba.nacos.api.lock.common.LockConstants;
import com.alibaba.nacos.api.lock.model.LockInstance;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.api.naming.pojo.Service;
import com.alibaba.nacos.maintainer.client.naming.NamingMaintainerFactory;
import com.alibaba.nacos.maintainer.client.naming.NamingMaintainerService;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.codec.MD5;
import net.hasor.meteor.registry.shared.storage.domain.EntRuleConfigurationData;
import net.hasor.meteor.registry.shared.storage.domain.EntityRecord;
import net.hasor.meteor.registry.shared.storage.domain.RuleKind;
import net.hasor.meteor.registry.shared.utils.RegistryJson;

/** 持久治理使用 Config CAS，实例治理只修改应用持有的 Naming 临时节点。 */
final class NacosGovernance implements AutoCloseable {
    private static final long                    WRITE_LEASE_MILLIS = 30000;
    private final        NacosConfigStore        configs;
    private final        NacosNamingStore        naming;
    private final        Properties              properties;
    private final        String                  group;
    private              NamingMaintainerService admin;
    private              LockService             locks;
    private              boolean                 closed;

    NacosGovernance(NacosConfigStore configs, NacosNamingStore naming, Properties properties, String group) {
        this.configs = configs;
        this.naming = naming;
        this.properties = properties;
        this.group = group;
    }

    <T> EntityRecord<T> get(String instanceId, String serviceId, String item, Class<T> type) throws Exception {
        String key = NacosKeyUtils.governance(instanceId, serviceId);
        String content;
        if (instanceId == null) {
            content = this.configs.read(key + "." + item);
        } else {
            Instance node = this.naming.find(key, instanceId);
            content = node == null ? null : node.getMetadata().get(item);
        }
        return decode(content, type);
    }

    boolean save(String instanceId, String serviceId, String item, EntityRecord<?> value, boolean delete) throws Exception {
        String key = NacosKeyUtils.governance(instanceId, serviceId);
        if (instanceId != null) {
            return this.saveRuntime(key, instanceId, item, value, delete);
        }
        String dataId = key + "." + item;
        String previous = this.configs.read(dataId);
        if (!checkRevision(previous, value.getRevision(), delete)) {
            return false;
        }
        String updated = encode(value.getData(), delete);
        // 全局状态与规则开关共用详情节点，只替换状态自身的版本和正文。
        if (serviceId == null && StringUtils.equals(item, "state") && previous != null) {
            Map<String, Object> document = RegistryJson.read(previous, Map.class);
            document.putAll(RegistryJson.read(updated, Map.class));
            updated = RegistryJson.write(document);
        }
        if (!this.configs.compareAndSet(dataId, previous, updated)) {
            throw new ConcurrentModificationException("Governance changed: " + dataId);
        }
        return true;
    }

    EntityRecord<EntRuleConfigurationData> getRule(String instanceId, String serviceId, RuleKind kind) throws Exception {
        return this.readRule(instanceId, serviceId, kind, false);
    }

    boolean saveRule(String instanceId, String serviceId, RuleKind kind, EntityRecord<EntRuleConfigurationData> value, boolean delete) throws Exception {
        String scope = NacosKeyUtils.governance(instanceId, serviceId);
        long deadline = writeDeadline();
        return this.locked(scope, () -> {
            // 同一范围的规则变更串行执行，旧内容版本不能覆盖新值。
            EntityRecord<EntRuleConfigurationData> previous = this.readRule(instanceId, serviceId, kind, true);
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

    void moveRuleControls(String instanceId, String source, String target, long deadline) throws Exception {
        // 调用方持有范围锁，并在搬迁完成后撤回旧关系。
        Map<String, String> previous = this.readRuntime(source, instanceId, true);
        Map<String, String> fields = encodeRuleControls(runtimeDetail(previous));
        this.writeRuntime(target, instanceId, fields, deadline);
    }

    private EntityRecord<EntRuleConfigurationData> readRule(String instanceId, String serviceId, RuleKind kind, boolean fresh) throws Exception {
        String scope = NacosKeyUtils.governance(instanceId, serviceId);
        String field = "rule." + kind.name();
        String body;
        Map<?, ?> detail;
        if (instanceId == null) {
            body = this.configs.read(scope + "." + field);
            String key = ruleControlKey(null, serviceId);
            detail = this.configs.get(key, Map.class);
        } else {
            Map<String, String> fields = this.readRuntime(scope, instanceId, fresh);
            if (fields == null) {
                return null;
            }
            body = fields.get(field);
            String key = this.runtimeRuleControlKey(instanceId, serviceId);
            Map<String, String> metadata = this.readRuntime(key, instanceId, fresh);
            detail = runtimeDetail(metadata);
        }
        if (StringUtils.isBlank(body)) {
            body = clearedRules(detail).contains(kind.name()) ? "" : null;
        }
        Boolean enabled = ruleControls(detail).get(kind.name());
        if (body == null && enabled == null) {
            return null;
        }

        // 版本摘要固定按 [正文, 显式开关] 编码，两项均允许为 null，不向 XML 注入版本字段。
        List<Object> revisionValues = new ArrayList<>(2);
        revisionValues.add(body);
        revisionValues.add(enabled);
        String version = RegistryJson.write(revisionValues);
        EntityRecord<EntRuleConfigurationData> result = new EntityRecord<>();
        result.setRevision(MD5.getMD5(version.getBytes(StandardCharsets.UTF_8)));
        EntRuleConfigurationData data = new EntRuleConfigurationData();
        data.setRule(body);
        data.setEnabled(enabled == null || enabled);
        result.setData(data);
        return result;
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
        } else if (!StringUtils.equals(old, body) && !this.configs.compareAndSetRule(key, old, body)) {
            throw new ConcurrentModificationException("Rule changed: " + key);
        }

        // 正文与开关跨节点非事务，合并详情后由订阅最终收敛。
        Boolean enabled = value == null ? null : value.isEnabled();
        this.savePersistentRuleControl(serviceId, kind, enabled, body);
    }

    private void saveRuntimeRule(String instanceId, String serviceId, RuleKind kind, EntRuleConfigurationData previous, EntRuleConfigurationData value, long deadline) throws Exception {
        String key = this.runtimeRuleControlKey(instanceId, serviceId);
        Map<String, String> detail = this.readRuntime(key, instanceId, true);
        if (detail == null) {
            throw new IllegalStateException("Rule target is not registered: " + key);
        }
        String body = value == null ? null : value.getRule();
        Boolean enabled = value == null ? null : value.isEnabled();
        Map<String, Object> state = runtimeDetail(detail);
        updateRuleControl(state, kind, enabled, body);

        // 规则正文仍归治理节点，开关只更新实例主记录或当前角色的成员关系。
        String oldBody = previous == null ? null : previous.getRule();
        if (!StringUtils.equals(oldBody, body)) {
            String scope = NacosKeyUtils.governance(instanceId, serviceId);
            String content = body == null ? "" : body;
            this.writeRuntime(scope, instanceId, Map.of("rule." + kind.name(), content), deadline);
        }
        Map<String, String> fields = encodeRuleControls(state);
        if (!detail.entrySet().containsAll(fields.entrySet())) {
            this.writeRuntime(key, instanceId, fields, deadline);
        }
    }

    private void savePersistentRuleControl(String serviceId, RuleKind kind, Boolean enabled, String body) throws Exception {
        String key = ruleControlKey(null, serviceId);
        this.configs.update(key, Map.class, previous -> {
            Map<String, Object> detail = previous == null ? new LinkedHashMap<>() : previous;
            if (serviceId != null) {
                detail.put("serviceId", serviceId);
            }
            updateRuleControl(detail, kind, enabled, body);
            return detail;
        });
    }

    private String runtimeRuleControlKey(String instanceId, String serviceId) throws Exception {
        if (serviceId == null) {
            return ruleControlKey(instanceId, null);
        }
        String prefix = NacosKeyUtils.service(serviceId);
        if (this.readRuntime(prefix + ".providers", instanceId, false) != null) {
            return prefix + ".providers";
        }
        return prefix + ".consumers";
    }

    private static String ruleControlKey(String instanceId, String serviceId) {
        if (instanceId != null) {
            return NacosKeyUtils.instance(instanceId);
        }
        if (serviceId == null) {
            return NacosKeyUtils.governance(null, null) + ".state";
        }
        return NacosKeyUtils.service(serviceId);
    }

    private static Map<String, Boolean> ruleControls(Map<?, ?> detail) {
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

    private static Set<String> clearedRules(Map<?, ?> detail) {
        Set<String> cleared = new LinkedHashSet<>();
        if (detail != null && detail.get("ruleCleared") instanceof Collection<?> values) {
            for (Object value : values) {
                cleared.add((String) value);
            }
        }
        return cleared;
    }

    private static Map<String, String> encodeRuleControls(Map<?, ?> detail) throws Exception {
        // 空集合用于覆盖 Naming 管理元信息缓存，不能仅省略原有字段。
        return Map.of("ruleEnabled", RegistryJson.write(ruleControls(detail)), //
                "ruleCleared", RegistryJson.write(clearedRules(detail)));
    }

    private static void updateRuleControl(Map<String, Object> detail, RuleKind kind, Boolean enabled, String body) {
        Map<String, Boolean> switches = ruleControls(detail);
        if (enabled == null) {
            switches.remove(kind.name());
        } else {
            switches.put(kind.name(), enabled);
        }
        detail.put("ruleEnabled", switches);

        // 现有 API 区分 null（恢复继承）与空正文（显式清空），标识也随详情保存。
        Set<String> cleared = clearedRules(detail);
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

    private boolean saveRuntime(String key, String instanceId, String item, EntityRecord<?> value, boolean delete) throws Exception {
        long deadline = writeDeadline();
        return this.locked(key, () -> {
            Map<String, String> metadata = this.readRuntime(key, instanceId, true);
            if (metadata == null) {
                if (delete) {
                    return false;
                }
                throw new IllegalStateException("Instance governance is not registered: " + key);
            }
            if (!checkRevision(metadata.get(item), value.getRevision(), delete)) {
                return false;
            }
            this.writeRuntime(key, instanceId, Map.of(item, encode(value.getData(), delete)), deadline);
            return true;
        });
    }

    synchronized <T> T locked(String key, Callable<T> action) throws Exception {
        // 原生锁协调同一治理范围的管理写入，不创建会话或锁占位 Config。
        if (this.closed) {
            throw new IllegalStateException("Nacos governance is closed");
        }
        this.openAdmin();
        String lockKey = this.namespace() + ":" + this.group + ":" + key;
        LockInstance lock = new LockInstance(lockKey, WRITE_LEASE_MILLIS, LockConstants.NACOS_LOCK_TYPE);
        long deadline = writeDeadline();
        if (!Boolean.TRUE.equals(this.locks.lock(lock))) {
            throw new ConcurrentModificationException("Governance write is busy: " + key);
        }
        try {
            return action.call();
        } finally {
            if (System.nanoTime() < deadline) {
                this.locks.unLock(lock);
            }
        }
    }

    static long writeDeadline() {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WRITE_LEASE_MILLIS);
    }

    private String namespace() {
        String namespace = this.properties.getProperty("namespace");
        return StringUtils.isBlank(namespace) ? "public" : namespace;
    }

    private Service service(String key) {
        Service service = new Service();
        service.setNamespaceId(this.namespace());
        service.setGroupName(this.group);
        service.setName(key);
        service.setEphemeral(true);
        return service;
    }

    Map<String, String> readRuntime(String key, String instanceId, boolean fresh) throws Exception {
        Instance node = this.naming.find(key, instanceId);
        if (node == null) {
            return null;
        }
        // 写入时从管理接口读取当前值，普通查询沿用 Naming 订阅缓存。
        if (fresh) {
            node = this.admin.getInstanceDetail(this.service(key), node);
        }
        return new LinkedHashMap<>(node.getMetadata());
    }

    void writeRuntime(String key, String instanceId, Map<String, String> fields, long deadline) throws Exception {
        Instance identity = this.naming.find(key, instanceId);
        if (identity == null) {
            throw new IllegalStateException("Instance is not registered: " + key);
        }
        if (System.nanoTime() >= deadline) {
            throw new IllegalStateException("Nacos governance write lease expired: " + key);
        }
        // 局部更新不会取得临时节点所有权，也不会覆盖应用上报字段。
        Service service = this.service(key);
        identity = RegistryJson.copy(identity, Instance.class);
        identity.setMetadata(fields);
        this.admin.partialUpdateInstance(service, identity);

        // 写接口与查询快照异步传播，确认全部字段可读后才释放治理锁。
        while (System.nanoTime() < deadline) {
            Instance current = this.admin.getInstanceDetail(service, identity);
            if (current.getMetadata().entrySet().containsAll(fields.entrySet())) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        throw new IllegalStateException("Nacos governance write was not confirmed: " + key);
    }

    private void openAdmin() throws Exception {
        if (this.admin == null) {
            this.admin = NamingMaintainerFactory.createNamingMaintainerService(this.properties);
        }
        if (this.locks == null) {
            this.locks = NacosLockFactory.createLockService(this.properties);
        }
    }

    private static boolean checkRevision(String content, String expected, boolean delete) throws Exception {
        EntityRecord<Object> previous = decode(content, Object.class);
        if (delete && previous == null) {
            return false;
        }
        String revision = previous == null ? null : previous.getRevision();
        if (!StringUtils.equals(revision, expected)) {
            throw new ConcurrentModificationException("Governance changed");
        }
        return true;
    }

    private static String encode(Object body, boolean delete) throws Exception {
        EntityRecord<Object> record = new EntityRecord<>();
        record.setData(delete ? null : body);
        record.setRevision(UUID.randomUUID().toString());
        return RegistryJson.write(record);
    }

    private static <T> EntityRecord<T> decode(String content, Class<T> type) throws Exception {
        if (content == null) {
            return null;
        }
        Map<?, ?> document = RegistryJson.read(content, Map.class);
        if (document.get("data") == null) {
            return null;
        }
        EntityRecord<T> result = new EntityRecord<>();
        result.setRevision((String) document.get("revision"));
        result.setData(RegistryJson.copy(document.get("data"), type));
        return result;
    }

    @Override
    public synchronized void close() throws Exception {
        if (this.closed) {
            return;
        }
        this.closed = true;
        try {
            if (this.admin != null) {
                this.admin.shutdown();
            }
        } finally {
            if (this.locks != null) {
                this.locks.shutdown();
            }
        }
    }
}

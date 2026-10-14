/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.nacos;
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
import net.hasor.meteor.registry.shared.storage.domain.EntityRecord;
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

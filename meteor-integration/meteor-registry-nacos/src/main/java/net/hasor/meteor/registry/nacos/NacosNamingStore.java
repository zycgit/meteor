/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.nacos;
import java.util.*;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.registry.shared.storage.domain.EntInstanceRuntimeData;
import net.hasor.meteor.registry.shared.utils.RegistryJson;

/** 只维护本客户端发布的临时成员；连接生命周期交给 Nacos。 */
final class NacosNamingStore {
    private final NamingService                      naming;
    private final String                             group;
    private final Map<String, Map<String, Instance>> registrations = new LinkedHashMap<>();

    NacosNamingStore(NamingService naming, String group) {
        this.naming = naming;
        this.group = group;
    }

    List<Instance> members(String key) throws Exception {
        List<Instance> result = new ArrayList<>();
        for (Instance node : this.naming.getAllInstances(key, this.group, false)) {
            if (node.isHealthy() && node.isEnabled()) {
                result.add(node);
            }
        }
        return result;
    }

    Instance find(String key, String instanceId) throws Exception {
        for (Instance node : this.members(key)) {
            if (StringUtils.equals(instanceId, node.getMetadata().get("instanceId"))) {
                return node;
            }
        }
        return null;
    }

    synchronized boolean owns(String key, String instanceId) {
        return this.registrations.getOrDefault(key, Map.of()).containsKey(instanceId);
    }

    synchronized void publish(String key, EntInstanceRuntimeData instance, Map<String, String> values) throws Exception {
        String id = instance.getInstanceId();
        Map<String, Instance> previous = this.registrations.getOrDefault(key, Map.of());
        Map<String, String> metadata = new HashMap<>(values);
        metadata.put("instanceId", id);
        Instance old = previous.get(id);
        boolean unchanged = old != null && old.getMetadata().equals(metadata);
        if (unchanged && StringUtils.equals(old.getIp(), instance.getIpAddress())) {
            return;
        }

        String cluster = NacosKeyUtils.encode(id);
        if (cluster.length() > 64) {
            throw new IllegalArgumentException("Nacos instance identity exceeds 64 encoded characters: " + id);
        }
        Instance node = new Instance();
        node.setIp(instance.getIpAddress());
        node.setPort(0);
        node.setClusterName(cluster);
        node.setEphemeral(true);
        node.setMetadata(metadata);

        // 同一客户端在同一服务下的多个成员需要整批发布，避免后一次登记覆盖前一次。
        Map<String, Instance> updated = new LinkedHashMap<>(previous);
        updated.put(id, node);
        this.naming.batchRegisterInstance(key, this.group, new ArrayList<>(updated.values()));
        this.registrations.put(key, updated);
    }

    synchronized void withdraw(String key, String instanceId) throws Exception {
        Map<String, Instance> previous = this.registrations.get(key);
        if (previous == null || !previous.containsKey(instanceId)) {
            return;
        }

        Map<String, Instance> updated = new LinkedHashMap<>(previous);
        updated.remove(instanceId);
        this.naming.batchRegisterInstance(key, this.group, new ArrayList<>(updated.values()));
        if (updated.isEmpty()) {
            this.registrations.remove(key);
        } else {
            this.registrations.put(key, updated);
        }
    }

    Map<String, String> snapshot(String key) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        for (Instance node : this.members(key)) {
            result.put(node.getMetadata().get("instanceId"), RegistryJson.write(node.getMetadata()));
        }
        return result;
    }

    synchronized void close() throws Exception {
        Exception failure = null;
        for (String key : this.registrations.keySet()) {
            try {
                this.naming.batchRegisterInstance(key, this.group, List.of());
            } catch (Exception cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else if (failure != cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        this.registrations.clear();
        if (failure != null) {
            throw failure;
        }
    }
}

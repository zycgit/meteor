/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.nacos;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.lock.LockService;
import com.alibaba.nacos.api.lock.NacosLockFactory;
import com.alibaba.nacos.api.lock.model.LockInstance;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.api.naming.pojo.Service;
import com.alibaba.nacos.maintainer.client.naming.NamingMaintainerFactory;
import com.alibaba.nacos.maintainer.client.naming.NamingMaintainerService;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.registry.shared.utils.RegistryJson;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.*;

/** 仅模拟 Nacos SDK；用例通过具体配置打开真实存储实现。 */
public final class MockNacosSdk implements AutoCloseable {
    private final MockedStatic<NacosFactory>            sdk;
    private final MockedStatic<NamingMaintainerFactory> adminFactory;
    private final MockedStatic<NacosLockFactory>        lockFactory;
    private final Set<String>                           locks              = ConcurrentHashMap.newKeySet();
    private final Map<String, Map<String, String>>      overrides          = new ConcurrentHashMap<>();
    final         Map<String, Map<String, Instance>>    nodes              = new ConcurrentHashMap<>();
    final         Map<String, String>                   documents          = new ConcurrentHashMap<>();
    final         Map<String, Set<Listener>>            configListeners    = new ConcurrentHashMap<>();
    final         Map<String, Set<EventListener>>       namingListeners    = new ConcurrentHashMap<>();
    final         AtomicInteger                         reads              = new AtomicInteger();
    final         AtomicInteger                         disconnectedChecks = new AtomicInteger();
    final         AtomicInteger                         adminCreations     = new AtomicInteger();
    final         AtomicInteger                         lockCreations      = new AtomicInteger();
    final         List<NamingService>                   namingClients      = new ArrayList<>();
    final         List<ConfigService>                   configClients      = new ArrayList<>();
    volatile      boolean                               available          = true;
    volatile      boolean                               notify             = true;
    volatile      boolean                               failConfigSubscribeAfterInstall;
    volatile      boolean                               rejectPrimary;
    volatile      String                                rejectBatchKey;
    volatile      int                                   rejectBatchWrites;
    volatile      int                                   conflicts;
    volatile      int                                   adminVisibilityDelay;
    final         AtomicInteger                         adminReads         = new AtomicInteger();
    private final Map<String, Instance>                 delayedSnapshots   = new ConcurrentHashMap<>();
    private final Map<String, Integer>                  delayedReads       = new ConcurrentHashMap<>();
    volatile      String                                conflictPrefix     = "";

    void configChanged(String name) {
        if (this.notify) {
            String content = this.documents.get(name);
            for (Listener listener : this.configListeners.getOrDefault(name, Set.of())) {
                listener.receiveConfigInfo(content);
            }
        }
    }

    void namingChanged(String name) {
        if (!this.notify) {
            return;
        }

        for (EventListener listener : this.namingListeners.getOrDefault(name, Set.of())) {
            listener.onEvent(new NamingEvent(name, List.of()));
        }
    }

    public int listenerCount() {
        int configCount = this.configListeners.values().stream().mapToInt(Set::size).sum();
        int namingCount = this.namingListeners.values().stream().mapToInt(Set::size).sum();
        return configCount + namingCount;
    }

    public MockNacosSdk() {
        this.sdk = mockStatic(NacosFactory.class);
        this.adminFactory = mockStatic(NamingMaintainerFactory.class);
        this.lockFactory = mockStatic(NacosLockFactory.class);
        this.adminFactory //
                .when(() -> NamingMaintainerFactory.createNamingMaintainerService(any(Properties.class))) //
                .thenAnswer(call -> {
                    this.adminCreations.incrementAndGet();
                    return this.adminService();
                });
        this.lockFactory.when(() -> NacosLockFactory.createLockService(any(Properties.class))) //
                .thenAnswer(call -> {
                    this.lockCreations.incrementAndGet();
                    return this.lockService();
                });
        var namingFactory = this.sdk.when(() -> NacosFactory.createNamingService(any(Properties.class)));
        namingFactory.thenAnswer(call -> this.namingService());
        var configFactory = this.sdk.when(() -> NacosFactory.createConfigService(any(Properties.class)));
        configFactory.thenAnswer(call -> this.configService());
    }

    public NacosRegistryConfiguration configuration() {
        NacosRegistryConfiguration configuration = new NacosRegistryConfiguration();
        configuration.setAddress("nacos.test:8848");
        configuration.setGroup("test");
        configuration.setTimeoutMillis(1000);
        return configuration;
    }

    @Override
    public void close() {
        this.lockFactory.close();
        this.adminFactory.close();
        this.sdk.close();
    }

    private NamingMaintainerService adminService() throws Exception {
        NamingMaintainerService admin = mock(NamingMaintainerService.class);
        when(admin.getInstanceDetail(any(Service.class), any(Instance.class))).thenAnswer(call -> {
            Service service = call.getArgument(0);
            Instance query = call.getArgument(1);
            String key = service.getName() + ":" + query.getClusterName();
            this.adminReads.incrementAndGet();
            int remaining = this.delayedReads.getOrDefault(key, 0);
            if (remaining > 0) {
                this.delayedReads.put(key, remaining - 1);
                return RegistryJson.copy(this.delayedSnapshots.get(key), Instance.class);
            }
            Instance node = this.nodes.get(service.getName()).get(query.getClusterName());
            return RegistryJson.copy(node, Instance.class);
        });
        when(admin.partialUpdateInstance(any(Service.class), any(Instance.class))).thenAnswer(call -> {
            Service service = call.getArgument(0);
            Instance change = call.getArgument(1);
            Instance node = this.nodes.get(service.getName()).get(change.getClusterName());
            String key = service.getName() + ":" + change.getClusterName();
            this.delayedSnapshots.put(key, RegistryJson.copy(node, Instance.class));
            this.delayedReads.put(key, this.adminVisibilityDelay);
            node.getMetadata().putAll(change.getMetadata());
            this.overrides.computeIfAbsent(key, ignored -> new ConcurrentHashMap<>()).putAll(change.getMetadata());
            this.namingChanged(service.getName());
            return "ok";
        });
        return admin;
    }

    private LockService lockService() throws Exception {
        LockService service = mock(LockService.class);
        when(service.lock(any(LockInstance.class))).thenAnswer(call -> {
            LockInstance lock = call.getArgument(0);
            return this.locks.add(lock.getKey());
        });
        when(service.unLock(any(LockInstance.class))).thenAnswer(call -> {
            LockInstance lock = call.getArgument(0);
            return this.locks.remove(lock.getKey());
        });
        return service;
    }

    private NamingService namingService() throws Exception {
        NamingService naming = mock(NamingService.class);
        this.namingClients.add(naming);
        when(naming.getServerStatus()).thenAnswer(call -> this.available ? "UP" : "DOWN");

        // 原生订阅与临时实例使用共享的服务端数据，各存储会话创建自己的 SDK 客户端。
        doAnswer(call -> {
            String name = call.getArgument(0);
            var listeners = this.namingListeners.computeIfAbsent(name, key -> ConcurrentHashMap.newKeySet());
            listeners.add(call.getArgument(2));
            return null;
        }).when(naming).subscribe(anyString(), eq("test"), any(EventListener.class));
        doAnswer(call -> {
            this.namingListeners.getOrDefault(call.getArgument(0), Set.of()).remove(call.getArgument(2));
            return null;
        }).when(naming).unsubscribe(anyString(), eq("test"), any(EventListener.class));
        // 查询仅提供 SDK 对端数据，不替代注册中心的目录与筛选实现。
        when(naming.getAllInstances(anyString(), eq("test"), eq(false))).thenAnswer(call -> {
            Map<String, Instance> instances = this.nodes.getOrDefault(call.getArgument(0), Map.of());
            return new ArrayList<>(instances.values());
        });
        // 同一 SDK 对同一服务使用整批替换，其他会话的批次不受影响。
        Map<String, Set<String>> batches = new ConcurrentHashMap<>();
        doAnswer(call -> {
            String name = call.getArgument(0);
            List<Instance> input = call.getArgument(2);
            if (this.rejectBatchWrites > 0 && StringUtils.equals(this.rejectBatchKey, name)) {
                this.rejectBatchWrites--;
                throw new IllegalStateException("batch write failed");
            }
            if (this.rejectPrimary && name.endsWith(".providers")) {
                throw new IllegalStateException("write failed");
            }
            Map<String, Instance> values = this.nodes.computeIfAbsent(name, key -> new ConcurrentHashMap<>());
            for (String old : batches.getOrDefault(name, Set.of())) {
                values.remove(old);
            }
            Set<String> owned = new HashSet<>();
            for (Instance node : input) {
                Instance copy = new Instance();
                copy.setIp(node.getIp());
                copy.setPort(node.getPort());
                copy.setClusterName(node.getClusterName());
                copy.setHealthy(true);
                copy.setEnabled(true);
                copy.setEphemeral(true);
                copy.setMetadata(new HashMap<>(node.getMetadata()));
                String overrideKey = name + ":" + node.getClusterName();
                copy.getMetadata().putAll(this.overrides.getOrDefault(overrideKey, Map.of()));
                values.put(copy.getClusterName(), copy);
                owned.add(copy.getClusterName());
            }
            batches.put(name, owned);
            this.namingChanged(name);
            return null;
        }).when(naming).batchRegisterInstance(anyString(), eq("test"), anyList());
        // 连接断开时服务端清理该 SDK 持有的临时登记。
        doAnswer(call -> {
            for (var batch : batches.entrySet()) {
                Map<String, Instance> values = this.nodes.getOrDefault(batch.getKey(), Map.of());
                for (String cluster : batch.getValue()) {
                    values.remove(cluster);
                }
                this.namingChanged(batch.getKey());
            }
            batches.clear();
            return null;
        }).when(naming).shutDown();
        return naming;
    }

    private ConfigService configService() throws Exception {
        ConfigService configs = mock(ConfigService.class);
        this.configClients.add(configs);
        when(configs.getServerStatus()).thenAnswer(call -> {
            if (!this.available) {
                this.disconnectedChecks.incrementAndGet();
                return "DOWN";
            }
            return "UP";
        });

        // 配置监听器可注入安装失败，持久数据读写保留真实存储的 CAS 调用。
        doAnswer(call -> {
            String name = call.getArgument(0);
            Set<Listener> listeners = this.configListeners.computeIfAbsent(name, key -> ConcurrentHashMap.newKeySet());
            listeners.add(call.getArgument(2));
            if (this.failConfigSubscribeAfterInstall) {
                throw new IllegalStateException("listener registration failed");
            }
            return null;
        }).when(configs).addListener(anyString(), eq("test"), any(Listener.class));
        doAnswer(call -> {
            this.configListeners.getOrDefault(call.getArgument(0), Set.of()).remove(call.getArgument(2));
            return null;
        }).when(configs).removeListener(anyString(), eq("test"), any(Listener.class));

        // 配置点查可注入原始 SDK 读取异常。
        when(configs.getConfig(anyString(), eq("test"), anyLong())).thenAnswer(call -> {
            this.reads.incrementAndGet();
            if (!this.available) {
                throw new IllegalStateException("Nacos unavailable");
            }
            return this.documents.get(call.<String>getArgument(0));
        });
        when(configs.publishConfigCas(anyString(), eq("test"), anyString(), anyString())).thenAnswer(call -> {
            String key = call.getArgument(0);
            String content = call.getArgument(2);
            String expectedMd5 = call.getArgument(3);
            return this.publishConfig(key, content, expectedMd5);
        });
        when(configs.publishConfigCas(anyString(), eq("test"), anyString(), anyString(), eq("xml"))) //
                .thenAnswer(call -> {
                    return this.publishConfig(call.getArgument(0), call.getArgument(2), call.getArgument(3));
                });
        when(configs.removeConfig(anyString(), eq("test"))).thenAnswer(call -> {
            String key = call.getArgument(0);
            this.documents.remove(key);
            this.configChanged(key);
            return true;
        });
        return configs;
    }

    private boolean publishConfig(String key, String content, String expectedMd5) throws Exception {
        // 先模拟竞争失败，再按 Nacos 正文 MD5 执行原子比较更新。
        synchronized (this.documents) {
            if (this.conflicts > 0 && key.startsWith(this.conflictPrefix)) {
                this.conflicts--;
                return false;
            }
            String old = this.documents.getOrDefault(key, "");
            byte[] bytes = old.getBytes(StandardCharsets.UTF_8);
            byte[] digest = MessageDigest.getInstance("MD5").digest(bytes);
            String md5 = HexFormat.of().formatHex(digest);
            if (!StringUtils.equals(md5, expectedMd5)) {
                return false;
            }
            this.documents.put(key, content);
            this.configChanged(key);
            return true;
        }
    }
}

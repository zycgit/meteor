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
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.FuzzyWatchChangeEvent;
import com.alibaba.nacos.api.naming.listener.FuzzyWatchEventWatcher;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.Instance;
import com.alibaba.nacos.api.naming.pojo.ListView;
import net.hasor.cobble.StringUtils;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.*;

/** 仅模拟 Nacos SDK；用例通过具体配置打开真实存储实现。 */
final class NacosTestServer implements AutoCloseable {
    private final MockedStatic<NacosFactory>               sdk;
    final         Map<String, Map<String, Instance>>       nodes              = new ConcurrentHashMap<>();
    final         Map<String, String>                      documents          = new ConcurrentHashMap<>();
    final         Map<String, Set<Listener>>               configListeners    = new ConcurrentHashMap<>();
    final         Map<String, Set<EventListener>>          namingListeners    = new ConcurrentHashMap<>();
    final         Map<String, Set<FuzzyWatchEventWatcher>> discoveryListeners = new ConcurrentHashMap<>();
    final         AtomicInteger                            reads              = new AtomicInteger();
    volatile      boolean                                  available          = true;
    volatile      boolean                                  notify             = true;
    volatile      boolean                                  rejectConfigListener;
    volatile      boolean                                  rejectPrimary;
    volatile      int                                      conflicts;

    void configChanged(String name) {
        if (this.notify) {
            String content = this.documents.get(name);
            for (Listener listener : this.configListeners.getOrDefault(name, Set.of())) {
                listener.receiveConfigInfo(content);
            }
        }
    }

    void namingChanged(String name, boolean created) {
        if (!this.notify) {
            return;
        }

        if (created) {
            for (var entry : this.discoveryListeners.entrySet()) {
                String pattern = entry.getKey();
                String prefix = pattern.substring(0, pattern.length() - 1);
                if (name.startsWith(prefix)) {
                    String eventType = "FUZZY_WATCH_RESOURCE_CHANGED";
                    var event = new FuzzyWatchChangeEvent(name, "test", "", "ADD_SERVICE", eventType);
                    for (FuzzyWatchEventWatcher listener : entry.getValue()) {
                        listener.onEvent(event);
                    }
                }
            }
        }

        for (EventListener listener : this.namingListeners.getOrDefault(name, Set.of())) {
            listener.onEvent(new NamingEvent(name, List.of()));
        }
    }

    int listenerCount() {
        int configCount = this.configListeners.values().stream().mapToInt(Set::size).sum();
        int namingCount = this.namingListeners.values().stream().mapToInt(Set::size).sum();
        int discoveryCount = this.discoveryListeners.values().stream().mapToInt(Set::size).sum();
        return configCount + namingCount + discoveryCount;
    }

    NacosTestServer() {
        this.sdk = mockStatic(NacosFactory.class);
        var namingFactory = this.sdk.when(() -> NacosFactory.createNamingService(any(Properties.class)));
        namingFactory.thenAnswer(call -> this.namingService());
        var configFactory = this.sdk.when(() -> NacosFactory.createConfigService(any(Properties.class)));
        configFactory.thenAnswer(call -> this.configService());
    }

    NacosRegistryConfiguration configuration() {
        NacosRegistryConfiguration configuration = new NacosRegistryConfiguration();
        configuration.setAddress("nacos.test:8848");
        configuration.setGroup("test");
        configuration.setTimeoutMillis(1000);
        return configuration;
    }

    @Override
    public void close() {
        this.sdk.close();
    }

    private NamingService namingService() throws Exception {
        NamingService naming = mock(NamingService.class);
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
        doAnswer(call -> {
            String name = call.getArgument(0);
            var listeners = this.discoveryListeners.computeIfAbsent(name, key -> ConcurrentHashMap.newKeySet());
            listeners.add(call.getArgument(2));
            return null;
        }).when(naming).fuzzyWatch(anyString(), eq("test"), any(FuzzyWatchEventWatcher.class));
        doAnswer(call -> {
            this.discoveryListeners.getOrDefault(call.getArgument(0), Set.of()).remove(call.getArgument(2));
            return null;
        }).when(naming).cancelFuzzyWatch(anyString(), eq("test"), any(FuzzyWatchEventWatcher.class));
        doAnswer(call -> {
            String service = call.getArgument(0);
            Instance input = call.getArgument(2);
            this.registerInstance(service, input);
            return null;
        }).when(naming).registerInstance(anyString(), eq("test"), any(Instance.class));
        doAnswer(call -> {
            Map<String, Instance> values = this.nodes.get(call.<String>getArgument(0));
            if (values != null) {
                values.remove(call.<String>getArgument(4));
                this.namingChanged(call.getArgument(0), false);
            }
            return null;
        }).when(naming).deregisterInstance(anyString(), eq("test"), anyString(), anyInt(), anyString());
        when(naming.getAllInstances(anyString(), eq("test"), eq(false))).thenAnswer(call -> {
            Map<String, Instance> instances = this.nodes.getOrDefault(call.getArgument(0), Map.of());
            return new ArrayList<>(instances.values());
        });
        when(naming.getServicesOfServer(anyInt(), anyInt(), eq("test"))).thenAnswer(call -> {
            List<String> names = new ArrayList<>(new TreeSet<>(this.nodes.keySet()));
            int page = call.getArgument(0);
            int size = call.getArgument(1);
            int start = Math.min(names.size(), (page - 1) * size);
            int end = Math.min(names.size(), start + size);
            ListView<String> view = new ListView<>();
            view.setCount(names.size());
            view.setData(names.subList(start, end));
            return view;
        });
        return naming;
    }

    private void registerInstance(String service, Instance input) {
        if (this.rejectPrimary && service.startsWith("meteor.v2.r.")) {
            throw new IllegalStateException("write failed");
        }

        // SDK 不共享调用方可变对象，保存服务端接收到的实例副本。
        Instance copy = new Instance();
        copy.setHealthy(true);
        copy.setEnabled(true);
        copy.setEphemeral(input.isEphemeral());
        copy.setClusterName(input.getClusterName());
        copy.setIp(input.getIp());
        copy.setPort(input.getPort());
        copy.setMetadata(new HashMap<>(input.getMetadata()));

        // 写入后先通知名称发现，再交付该名称上的内容变化。
        boolean created = !this.nodes.containsKey(service);
        this.nodes.computeIfAbsent(service, key -> new ConcurrentHashMap<>()).put(copy.getClusterName(), copy);
        this.namingChanged(service, created);
    }

    private ConfigService configService() throws Exception {
        ConfigService configs = mock(ConfigService.class);
        when(configs.getServerStatus()).thenAnswer(call -> this.available ? "UP" : "DOWN");

        // 配置监听器可注入安装失败，持久数据读写保留真实存储的 CAS 调用。
        doAnswer(call -> {
            if (this.rejectConfigListener) {
                throw new IllegalStateException("listener registration failed");
            }
            String name = call.getArgument(0);
            Set<Listener> listeners = this.configListeners.computeIfAbsent(name, key -> ConcurrentHashMap.newKeySet());
            listeners.add(call.getArgument(2));
            return null;
        }).when(configs).addListener(anyString(), eq("test"), any(Listener.class));
        doAnswer(call -> {
            this.configListeners.getOrDefault(call.getArgument(0), Set.of()).remove(call.getArgument(2));
            return null;
        }).when(configs).removeListener(anyString(), eq("test"), any(Listener.class));
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
        return configs;
    }

    private boolean publishConfig(String key, String content, String expectedMd5) throws Exception {
        // 先模拟竞争失败，再按 Nacos 正文 MD5 执行原子比较更新。
        synchronized (this.documents) {
            if (this.conflicts > 0) {
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

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.dubbo.metadata;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.ACLProvider;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.NodeCache;
import org.apache.curator.retry.RetryNTimes;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.threadpool.manager.FrameworkExecutorRepository;
import org.apache.dubbo.metadata.MappingChangedEvent;
import org.apache.dubbo.metadata.MappingListener;
import org.apache.dubbo.metadata.ServiceNameMapping;
import org.apache.dubbo.metadata.store.zookeeper.ZookeeperMetadataReport;
import org.apache.dubbo.remoting.zookeeper.curator5.ZookeeperClientManager;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import static org.apache.dubbo.common.constants.CommonConstants.*;

/** 使用会话私有缓存和 SDK 调度器监听原生服务映射，避免静态缓存串用其他应用。 */
final class DubboZookeeperMetadataReport extends ZookeeperMetadataReport {
    private final CuratorFramework                  client;
    private final Executor                          executor;
    private final Map<String, NodeCache>            caches    = new LinkedHashMap<>();
    private final Map<String, Set<MappingListener>> listeners = new LinkedHashMap<>();

    DubboZookeeperMetadataReport(URL address, ZookeeperClientManager clients) {
        super(address, clients);
        try {
            this.executor = address.getOrDefaultApplicationModel().getFrameworkModel().getBeanFactory() //
                    .getBean(FrameworkExecutorRepository.class).getMappingRefreshingExecutor();
            this.client = this.createClient(address);
        } catch (RuntimeException | Error failure) {
            try {
                super.destroy();
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    @Override
    public Set<String> getServiceAppMapping(String serviceKey, MappingListener listener, URL address) {
        String path = this.toRootDir() + ServiceNameMapping.DEFAULT_MAPPING_GROUP + "/" + serviceKey;
        synchronized (this.caches) {
            this.listeners.computeIfAbsent(path, ignored -> new LinkedHashSet<>()).add(listener);
            if (!this.caches.containsKey(path)) {
                NodeCache cache = new NodeCache(this.client, path);
                cache.getListenable().addListener(() -> this.changed(serviceKey, path, cache), this.executor);
                this.caches.put(path, cache);
                try {
                    cache.start();
                } catch (Exception failure) {
                    this.caches.remove(path);
                    this.listeners.remove(path);
                    try {
                        cache.close();
                    } catch (IOException cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                    throw new IllegalStateException("Cannot watch Dubbo service mapping: " + path, failure);
                }
            }
        }
        // 同步读取与 SDK 原有 mapping lock 配合，保留订阅开始时已经存在的映射。
        return super.getServiceAppMapping(serviceKey, address);
    }

    private void changed(String serviceKey, String path, NodeCache source) {
        List<MappingListener> current;
        ChildData data;
        synchronized (this.caches) {
            if (this.caches.get(path) != source) {
                return;
            }
            data = source.getCurrentData();
            if (data == null) {
                return;
            }
            current = new ArrayList<>(this.listeners.get(path));
        }

        // 不在 ZooKeeper EventThread 执行 SDK 映射回调，其内部会等待实例监听完成初始化。
        byte[] bytes = data.getData();
        String content = bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
        Set<String> applications = ServiceNameMapping.getAppNames(content);
        MappingChangedEvent event = new MappingChangedEvent(serviceKey, applications);
        for (MappingListener listener : current) {
            listener.onEvent(event);
        }
    }

    @Override
    public void removeServiceAppMappingListener(String serviceKey, MappingListener listener) {
        String path = this.toRootDir() + ServiceNameMapping.DEFAULT_MAPPING_GROUP + "/" + serviceKey;
        NodeCache removed = null;
        synchronized (this.caches) {
            Set<MappingListener> current = this.listeners.get(path);
            if (current != null) {
                current.remove(listener);
                if (current.isEmpty()) {
                    this.listeners.remove(path);
                    removed = this.caches.remove(path);
                }
            }
        }
        if (removed != null) {
            try {
                removed.close();
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot close Dubbo service mapping watcher: " + path, failure);
            }
        }
    }

    @Override
    public void destroy() {
        List<NodeCache> owned;
        synchronized (this.caches) {
            owned = new ArrayList<>(this.caches.values());
            this.caches.clear();
            this.listeners.clear();
        }
        RuntimeException failure = null;
        try {
            for (NodeCache cache : owned) {
                try {
                    cache.close();
                } catch (IOException cleanup) {
                    if (failure == null) {
                        failure = new IllegalStateException("Cannot close Dubbo service mapping watchers", cleanup);
                    } else {
                        failure.addSuppressed(cleanup);
                    }
                }
            }
        } finally {
            try {
                this.client.close();
            } finally {
                super.destroy();
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private CuratorFramework createClient(URL address) {
        // 连接参数与官方 Curator5 客户端一致；此连接及其缓存仅由本报告对象持有。
        int timeout = address.getParameter(TIMEOUT_KEY, 5000);
        CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder() //
                .connectString(address.getBackupAddress()) //
                .retryPolicy(new RetryNTimes(1, 1000)) //
                .connectionTimeoutMs(timeout) //
                .sessionTimeoutMs(address.getParameter(SESSION_KEY, 60000)) //
                .ensembleTracker(address.getParameter(ZOOKEEPER_ENSEMBLE_TRACKER_KEY, true));
        String credentials = address.getUserInformation();
        if (credentials != null && !credentials.isEmpty()) {
            builder.authorization("digest", credentials.getBytes(StandardCharsets.UTF_8));
            builder.aclProvider(new ACLProvider() {
                @Override
                public List<ACL> getDefaultAcl() {
                    return ZooDefs.Ids.CREATOR_ALL_ACL;
                }

                @Override
                public List<ACL> getAclForPath(String path) {
                    return ZooDefs.Ids.CREATOR_ALL_ACL;
                }
            });
        }
        CuratorFramework created = builder.build();
        try {
            created.start();
            if (!created.blockUntilConnected(timeout, TimeUnit.MILLISECONDS)) {
                String target = address.getAddress();
                throw new IllegalStateException("Dubbo mapping ZooKeeper connection timed out: " + target);
            }
            return created;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            created.close();
            throw new IllegalStateException("Interrupted while connecting Dubbo mapping ZooKeeper", failure);
        } catch (RuntimeException failure) {
            created.close();
            throw failure;
        }
    }
}

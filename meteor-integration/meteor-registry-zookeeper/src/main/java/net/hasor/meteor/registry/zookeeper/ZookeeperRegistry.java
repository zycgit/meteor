/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.zookeeper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.registry.shared.storage.RecordStorageAccess;
import net.hasor.meteor.registry.shared.storage.StorageDocument;
import net.hasor.meteor.registry.shared.storage.StorageKeyUtils;
import net.hasor.meteor.registry.shared.storage.domain.*;
import net.hasor.meteor.registry.shared.utils.RegistryJson;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.transaction.CuratorOp;
import org.apache.curator.framework.recipes.cache.CuratorCache;
import org.apache.curator.framework.recipes.cache.CuratorCacheListener;
import org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;

/**
 * 独立正文与双向引用索引；记录与索引通过 multi 一起创建、删除。
 */
final class ZookeeperRegistry extends RecordStorageAccess {
    private final CuratorFramework           client;
    private final String                     root;
    private final Set<ZookeeperStorageWatch> subscriptions           = ConcurrentHashMap.newKeySet();
    private       ScheduledExecutorService   watchExecutor;
    private       CuratorCache               watchCache;
    private final ConnectionStateListener    watchConnectionListener = (client, state) -> {
        this.subscriptions.forEach(ZookeeperStorageWatch::refresh);
        if (state.isConnected()) {
            this.subscriptions.forEach(ZookeeperStorageWatch::signal);
        }
    };

    @Override
    public Cancellable watchEntities(EntityQuery query, Runnable listener) {
        this.lifecycleLock.readLock().lock();
        try {
            this.requireOpen();
            if (query == null || query.getType() == null) {
                throw new IllegalArgumentException("Entity type is required");
            }

            EntityQuery selection = query.copy();
            return this.watch(() -> this.readVersions(selection), listener);
        } finally {
            this.lifecycleLock.readLock().unlock();
        }
    }

    @Override
    public Cancellable watchRelations(RelationQuery query, Runnable listener) {
        this.lifecycleLock.readLock().lock();
        try {
            this.requireOpen();

            boolean scoped = query != null &&//
                    (query.getType() != null || query.getSource() != null || query.getTarget() != null);
            if (!scoped) {
                throw new IllegalArgumentException("Specify relation type or endpoint");
            }

            RelationQuery selection = query.copy();
            // 关系正文和起终点索引的修复都会影响查询结果。
            return this.watch(() -> this.readVersions(selection), listener);
        } finally {
            this.lifecycleLock.readLock().unlock();
        }
    }

    private Map<String, String> readVersions(EntityQuery query) throws Exception {
        EntityQuery pageQuery = query.copy();
        pageQuery.setLimit(1000);
        pageQuery.setCursor(null);
        Map<String, String> versions = new TreeMap<>();

        do {
            RecordPage<EntityRecord> page = this.findEntities(pageQuery);
            for (EntityRecord record : page.records()) {
                EntityRef entity = record.getRef();
                String key = StorageKeyUtils.entity(entity);
                versions.put(key, record.getRevision());
            }
            pageQuery.setCursor(page.nextCursor());
        } while (pageQuery.getCursor() != null);
        return versions;
    }

    private Map<String, String> readVersions(RelationQuery query) throws Exception {
        RelationQuery pageQuery = query.copy();
        pageQuery.setLimit(1000);
        pageQuery.setCursor(null);
        Map<String, String> versions = new TreeMap<>();

        do {
            RecordPage<RelationRecord> page = this.findRelations(pageQuery);
            for (RelationRecord record : page.records()) {
                String key = StorageKeyUtils.relation(record.getType(), record.getSource(), record.getTarget());
                versions.put(key, record.getRevision());
            }
            pageQuery.setCursor(page.nextCursor());
        } while (pageQuery.getCursor() != null);
        return versions;
    }

    private synchronized Cancellable watch(Callable<Map<String, String>> snapshot, Runnable listener) {
        if (this.watchExecutor == null) {
            ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
                Thread thread = new Thread(task, "Meteor-zookeeper-watch-" + this.tempSessionId);
                thread.setDaemon(true);
                return thread;
            });

            executor.setRemoveOnCancelPolicy(true);
            this.watchExecutor = executor;
            // 同一个存储会话只维护一份原生节点缓存，所有查询订阅共享通知源。
            this.watchCache = CuratorCache.build(this.client, this.root);
            CuratorCacheListener cacheListener = CuratorCacheListener.builder().forAll((type, oldData, data) -> {
                this.subscriptions.forEach(ZookeeperStorageWatch::signal);
            }).forInitialized(() -> {
                this.subscriptions.forEach(ZookeeperStorageWatch::signal);
            }).build();

            this.watchCache.listenable().addListener(cacheListener);
            this.client.getConnectionStateListenable().addListener(this.watchConnectionListener);

            try {
                this.watchCache.start();
            } catch (RuntimeException failure) {
                this.client.getConnectionStateListenable().removeListener(this.watchConnectionListener);
                this.watchCache.close();
                this.watchCache = null;
                this.watchExecutor.shutdownNow();
                this.watchExecutor = null;
                throw failure;
            }
        }

        ZookeeperStorageWatch watch = new ZookeeperStorageWatch(this.client, snapshot, listener, this.watchExecutor, this.subscriptions::remove);
        this.subscriptions.add(watch);
        watch.signal();
        return watch;
    }

    ZookeeperRegistry(CuratorFramework client, String root) {
        this.client = client;
        this.root = StringUtils.equals(root, "/") ? "/records-v2" : root + "/records-v2";
    }

    private String path(String key) {
        return this.root + "/data/" + key.replace('.', '/');
    }

    private StorageDocument read(String key, Stat stat) throws Exception {
        try {
            byte[] content = this.client.getData().storingStatIn(stat).forPath(this.path(key));
            String json = new String(content, StandardCharsets.UTF_8);
            return RegistryJson.read(json, StorageDocument.class);
        } catch (KeeperException.NoNodeException absent) {
            return null;
        }
    }

    @Override
    protected StorageDocument readDocument(String key) throws Exception {
        return this.read(key, new Stat());
    }

    private List<String> children(String path) throws Exception {
        try {
            return this.client.getChildren().forPath(path);
        } catch (KeeperException.NoNodeException absent) {
            return List.of();
        }
    }

    private String index(String direction, EntityRef endpoint, String type) {
        return this.root + "/index/" + direction + "/" + StorageKeyUtils.entity(endpoint) + "/" + type;
    }

    private List<String> indexes(StorageDocument document) {
        if (document.getEntity() != null) {
            EntityRef parent = document.getEntity().getRef().parent();
            if (parent == null) {
                return List.of();
            }
            return List.of(this.index("source", parent, document.getType()) + "/" + document.getKey());
        }

        RelationRecord relation = document.getRelation();
        String source = this.index("source", relation.getSource(), document.getType()) + "/" + document.getKey();
        String target = this.index("target", relation.getTarget(), document.getType()) + "/" + document.getKey();
        return List.of(source, target);
    }

    @Override
    protected List<StorageDocument> queryDocuments(String kind, String type, EntityRef source, EntityRef target) throws Exception {
        List<StorageDocument> result = new ArrayList<>();
        List<String> types = new ArrayList<>();
        if (type == null) {
            for (RelationType relationType : RelationType.values()) {
                types.add(relationType.name());
            }
        } else {
            types.add(type);
        }

        for (String current : types) {
            if (source != null || target != null) {
                String direction = source != null ? "source" : "target";
                EntityRef endpoint = source != null ? source : target;
                String parent = this.index(direction, endpoint, current);
                for (String key : this.children(parent)) {
                    StorageDocument record = this.readDocument(key);
                    if (record != null) {
                        result.add(record);
                    }
                }
            } else {
                for (String id : this.children(this.root + "/data/" + kind + "/" + current)) {
                    StorageDocument record = this.readDocument(kind + "." + current + "." + id);
                    if (record != null) {
                        result.add(record);
                    }
                }
            }
        }
        return result;
    }

    private void ensureParent(String path) throws Exception {
        String parent = path.substring(0, path.lastIndexOf('/'));
        try {
            this.client.create().creatingParentsIfNeeded().forPath(parent);
        } catch (KeeperException.NodeExistsException exists) {
            /* Shared index directory. */
        }
    }

    @Override
    protected StorageDocument writeDocument(StorageDocument input) throws Exception {
        Stat stat = new Stat();
        StorageDocument previous = this.read(input.getKey(), stat);
        StorageDocument saved = this.prepareWrite(previous, input);
        String path = this.path(input.getKey());
        byte[] content = RegistryJson.write(saved).getBytes(StandardCharsets.UTF_8);
        CreateMode mode = CreateMode.PERSISTENT;

        if (saved.getLifecycle() == RecordLifecycle.EPHEMERAL) {
            mode = CreateMode.EPHEMERAL;
        }
        try {
            List<CuratorOp> operations = new ArrayList<>();
            if (previous != null) {
                if (!StringUtils.equals(saved.getRevision(), previous.getRevision())) {
                    int version = stat.getVersion();
                    CuratorOp update = this.client.transactionOp().setData().withVersion(version).forPath(path, content);
                    operations.add(update);
                } else {
                    operations.add(this.client.transactionOp().check().withVersion(stat.getVersion()).forPath(path));
                }
            } else {
                this.ensureParent(path);
                operations.add(this.client.transactionOp().create().withMode(mode).forPath(path, content));
            }

            for (String index : this.indexes(saved)) {
                Stat indexState = this.client.checkExists().forPath(index);
                if (indexState == null) {
                    this.ensureParent(index);
                    operations.add(this.client.transactionOp().create().withMode(mode).forPath(index, new byte[0]));
                } else if (mode == CreateMode.EPHEMERAL) {
                    long sessionId = this.client.getZookeeperClient().getZooKeeper().getSessionId();
                    if (indexState.getEphemeralOwner() != sessionId) {
                        throw new IllegalStateException("Relation index belongs to another session");
                    }
                } else if (indexState.getEphemeralOwner() != 0) {
                    throw new IllegalStateException("Relation index lifecycle does not match");
                }
            }

            this.client.transaction().forOperations(operations);
            return saved;
        } catch (KeeperException.BadVersionException | KeeperException.NodeExistsException | KeeperException.NoNodeException conflict) {
            throw new ConcurrentModificationException("Record changed during write", conflict);
        }
    }

    @Override
    protected boolean removeDocument(StorageDocument input) throws Exception {
        Stat stat = new Stat();
        StorageDocument previous = this.read(input.getKey(), stat);
        if (previous == null) {
            return false;
        }

        this.checkMutation(previous, input);
        List<CuratorOp> operations = new ArrayList<>();
        int version = stat.getVersion();
        String path = this.path(input.getKey());
        CuratorOp delete = this.client.transactionOp().delete().withVersion(version).forPath(path);
        operations.add(delete);

        for (String index : this.indexes(previous)) {
            if (this.client.checkExists().forPath(index) != null) {
                operations.add(this.client.transactionOp().delete().forPath(index));
            }
        }

        try {
            this.client.transaction().forOperations(operations);
            return true;
        } catch (KeeperException.BadVersionException | KeeperException.NoNodeException conflict) {
            throw new ConcurrentModificationException("Record changed during deletion", conflict);
        }
    }

    @Override
    protected void closeStorage() {
        this.subscriptions.forEach(ZookeeperStorageWatch::cancel);
        if (this.watchCache != null) {
            this.client.getConnectionStateListenable().removeListener(this.watchConnectionListener);
            this.watchCache.close();
        }

        if (this.watchExecutor != null) {
            this.watchExecutor.shutdownNow();
        }
        this.client.close();
    }
}

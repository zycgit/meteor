/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.memory;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.registry.shared.storage.RecordStorageAccess;
import net.hasor.meteor.registry.shared.storage.StorageDocument;
import net.hasor.meteor.registry.shared.storage.StorageKeyUtils;
import net.hasor.meteor.registry.shared.storage.domain.*;

/** 单个内存存储会话；同一配置创建的会话共享记录，临时记录随所属会话关闭而删除。 */
final class MemoryStorage extends RecordStorageAccess {
    private final Map<String, StorageDocument> records;
    private       ScheduledExecutorService     watches;

    public MemoryStorage(Map<String, StorageDocument> records) {
        this.records = records;
    }

    @Override
    public Cancellable watchEntities(EntityQuery query, Runnable listener) {
        this.lifecycleLock.readLock().lock();
        try {
            this.requireOpen();
            if (query == null || query.getType() == null) {
                throw new IllegalArgumentException("Entity type is required");
            } else {
                EntityQuery selection = query.copy();
                return this.watch(() -> this.readVersions(selection), listener);
            }
        } finally {
            this.lifecycleLock.readLock().unlock();
        }
    }

    @Override
    public Cancellable watchRelations(RelationQuery query, Runnable listener) {
        this.lifecycleLock.readLock().lock();
        try {
            this.requireOpen();
            if (query == null) {
                throw new IllegalArgumentException("Specify relation type or endpoint");
            }

            boolean scoped = query.getType() != null || query.getSource() != null || query.getTarget() != null;
            if (!scoped) {
                throw new IllegalArgumentException("Specify relation type or endpoint");
            }

            RelationQuery selection = query.copy();
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
        Objects.requireNonNull(listener, "listener");
        if (this.watches == null) {
            ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
                Thread thread = new Thread(task, "Meteor-memory-watch-" + this.tempSessionId);
                thread.setDaemon(true);
                return thread;
            });
            executor.setRemoveOnCancelPolicy(true);
            this.watches = executor;
        }

        MemoryStorageWatch watch = new MemoryStorageWatch(snapshot, listener);
        watch.scheduled(this.watches.scheduleWithFixedDelay(watch, 0, 1000, TimeUnit.MILLISECONDS));
        return watch;
    }

    @Override
    protected StorageDocument readDocument(String key) {
        return this.records.get(key);
    }

    @Override
    protected List<StorageDocument> queryDocuments(String kind, String type, EntityRef source, EntityRef target) {
        List<StorageDocument> values = new ArrayList<>();
        for (StorageDocument value : this.records.values()) {
            boolean sameKind = StringUtils.equals(value.getKind(), kind);
            boolean sameType = type == null || StringUtils.equals(value.getType(), type);
            if (sameKind && sameType) {
                values.add(value);
            }
        }
        return values;
    }

    @Override
    protected StorageDocument writeDocument(StorageDocument value) {
        synchronized (this.records) {
            StorageDocument saved = this.prepareWrite(this.readDocument(value.getKey()), value);
            this.records.put(saved.getKey(), saved);
            return saved;
        }
    }

    @Override
    protected boolean removeDocument(StorageDocument value) {
        synchronized (this.records) {
            StorageDocument previous = this.readDocument(value.getKey());
            if (previous == null) {
                return false;
            }

            this.checkMutation(previous, value);
            this.records.remove(value.getKey());
            return true;
        }
    }

    @Override
    protected void closeStorage() {
        if (this.watches != null) {
            this.watches.shutdownNow();
        }

        synchronized (this.records) {
            this.records.values().removeIf(value -> {
                return StringUtils.equals(this.tempSessionId, value.getSessionId());
            });
        }
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared.storage;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import net.hasor.cobble.ObjectUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.registry.shared.storage.domain.*;
import net.hasor.meteor.registry.shared.utils.RegistryJson;

/**
 * 统一校验、身份和分页语义；原子读写与会话清理由具体存储完成。
 */
public abstract class RecordStorageAccess implements StorageAccess {
    // 存取共享生命周期锁；关闭独占它，防止清理会话后又写入临时记录。
    protected final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock(true);
    protected final String                 tempSessionId = UUID.randomUUID().toString();
    private         boolean                closed;

    protected abstract StorageDocument readDocument(String key) throws Exception;

    protected StorageDocument readRelationDocument(RelationType type, EntityRef source, EntityRef target) throws Exception {
        return this.readDocument(StorageKeyUtils.relation(type, source, target));
    }

    protected abstract List<StorageDocument> queryDocuments(String kind, String type, EntityRef source, EntityRef target) throws Exception;

    protected abstract StorageDocument writeDocument(StorageDocument record) throws Exception;

    protected abstract boolean removeDocument(StorageDocument record) throws Exception;

    protected abstract void closeStorage() throws Exception;

    @Override
    public EntityRecord getEntity(EntityRef ref) throws Exception {
        this.lifecycleLock.readLock().lock();
        try {
            this.requireOpen();

            StorageDocument value = this.readDocument(StorageKeyUtils.entity(ref));
            return value == null ? null : copy(value).getEntity();
        } finally {
            this.lifecycleLock.readLock().unlock();
        }
    }

    @Override
    public RelationRecord getRelation(RelationType type, EntityRef source, EntityRef target) throws Exception {
        this.lifecycleLock.readLock().lock();
        try {
            this.requireOpen();

            StorageDocument value = this.readRelationDocument(type, source, target);
            return value == null ? null : copy(value).getRelation();
        } finally {
            this.lifecycleLock.readLock().unlock();
        }
    }

    @Override
    public EntityRecord saveEntity(EntityRecord record) throws Exception {
        this.lifecycleLock.readLock().lock();
        try {
            this.requireOpen();

            StorageDocument value = new StorageDocument();
            value.setEntity(record);
            this.validate(value);
            return copy(this.writeDocument(copy(value))).getEntity();
        } finally {
            this.lifecycleLock.readLock().unlock();
        }
    }

    @Override
    public RelationRecord saveRelation(RelationRecord record) throws Exception {
        this.lifecycleLock.readLock().lock();
        try {
            this.requireOpen();

            StorageDocument value = new StorageDocument();
            value.setRelation(record);
            this.validate(value);
            return copy(this.writeDocument(copy(value))).getRelation();
        } finally {
            this.lifecycleLock.readLock().unlock();
        }
    }

    @Override
    public boolean deleteEntity(EntityRecord record) throws Exception {
        this.lifecycleLock.readLock().lock();
        try {
            this.requireOpen();

            StorageDocument value = new StorageDocument();
            value.setEntity(record);
            this.validate(value);
            return this.removeDocument(value);
        } finally {
            this.lifecycleLock.readLock().unlock();
        }
    }

    @Override
    public boolean deleteRelation(RelationRecord record) throws Exception {
        this.lifecycleLock.readLock().lock();
        try {
            this.requireOpen();

            StorageDocument value = new StorageDocument();
            value.setRelation(record);
            this.validate(value);
            return this.removeDocument(value);
        } finally {
            this.lifecycleLock.readLock().unlock();
        }
    }

    @Override
    public RecordPage<EntityRecord> findEntities(EntityQuery query) throws Exception {
        this.lifecycleLock.readLock().lock();
        try {
            this.requireOpen();

            if (query == null || query.getType() == null) {
                throw new IllegalArgumentException("Entity type is required");
            }

            List<StorageDocument> values;
            if (query.getIds() != null) {
                values = new ArrayList<>();
                for (String id : query.getIds()) {
                    StorageDocument value = this.readDocument(StorageKeyUtils.entity(new EntityRef(query.getType(), id, query.getParent(), query.getSection())));
                    if (value != null) {
                        values.add(value);
                    }
                }
            } else {
                values = this.queryDocuments("e", query.getType().name(), query.getParent(), null);
            }

            values.removeIf(value -> {
                return !ObjectUtils.equals(query.getParent(), value.getEntity().getRef().parent()) ||//
                        query.getSection() != value.getEntity().getRef().section();
            });

            String ids;
            if (query.getIds() == null) {
                ids = "*";
            } else {
                ids = RegistryJson.write(new TreeSet<>(query.getIds()));
            }

            String scope = StorageKeyUtils.hash(query.getType().name(), ids, RegistryJson.write(query.getParent()), query.getSection().name());
            return page(values, query.getLimit(), query.getCursor(), scope, StorageDocument::getEntity);
        } finally {
            this.lifecycleLock.readLock().unlock();
        }
    }

    @Override
    public RecordPage<RelationRecord> findRelations(RelationQuery query) throws Exception {
        this.lifecycleLock.readLock().lock();
        try {
            this.requireOpen();

            boolean scoped = query != null && (query.getType() != null || query.getSource() != null || query.getTarget() != null);
            if (!scoped) {
                throw new IllegalArgumentException("Specify relation type or endpoint");
            }

            String type = null;
            if (query.getType() != null) {
                type = query.getType().name();
            }

            List<StorageDocument> values = this.queryDocuments("r", type, query.getSource(), query.getTarget());
            values.removeIf(value -> {
                RelationRecord relation = value.getRelation();
                boolean wrongType = query.getType() != null && relation.getType() != query.getType();
                boolean wrongSource = query.getSource() != null && !query.getSource().equals(relation.getSource());
                boolean wrongTarget = query.getTarget() != null && !query.getTarget().equals(relation.getTarget());
                return wrongType || wrongSource || wrongTarget;
            });

            String scopeType = String.valueOf(query.getType());
            String source = RegistryJson.write(query.getSource());
            String target = RegistryJson.write(query.getTarget());
            String scope = StorageKeyUtils.hash(scopeType, source, target);
            return page(values, query.getLimit(), query.getCursor(), scope, StorageDocument::getRelation);
        } finally {
            this.lifecycleLock.readLock().unlock();
        }
    }

    private static <T> RecordPage<T> page(List<StorageDocument> values, int limit, String cursor, String scope, Function<StorageDocument, T> mapper) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("Page limit must be between 1 and 1000");
        }

        String after = "";
        if (cursor != null) {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            if (!decoded.startsWith(scope + ":")) {
                throw new IllegalArgumentException("Cursor belongs to another query");
            }
            after = decoded.substring(scope.length() + 1);
        }
        values.sort(Comparator.comparing(StorageDocument::getKey));

        List<T> result = new ArrayList<>();
        String last = null;
        boolean more = false;
        for (StorageDocument value : values) {
            if (value.getKey().compareTo(after) <= 0) {
                continue;
            }
            if (result.size() == limit) {
                more = true;
                break;
            }
            result.add(mapper.apply(copy(value)));
            last = value.getKey();
        }

        String next = null;
        if (more) {
            byte[] cursorBytes = (scope + ":" + last).getBytes(StandardCharsets.UTF_8);
            next = Base64.getUrlEncoder().withoutPadding().encodeToString(cursorBytes);
        }
        return new RecordPage<>(result, next);
    }

    private void validate(StorageDocument value) {
        if (value.getEntity() == null && value.getRelation() == null) {
            throw new IllegalArgumentException("Record is required");
        }

        value.getKey();
        if (value.getLifecycle() == null || value.getRevision() == null) {
            throw new IllegalArgumentException("Lifecycle and revision are required");
        }
    }

    /**
     * 必须在适配器的事务或 CAS 操作中使用，持久记录不可无条件覆盖。
     */
    protected final StorageDocument prepareWrite(StorageDocument previous, StorageDocument input) {
        this.checkMutation(previous, input);
        boolean temporary = input.getLifecycle() == RecordLifecycle.EPHEMERAL;
        boolean sameContent = previous != null && temporary && RegistryJson.sameData(input.getData(), previous.getData());
        if (sameContent) {
            return copy(previous);
        }

        StorageDocument result = copy(input);
        result.setSessionId(temporary ? this.tempSessionId : null);
        result.revision(UUID.randomUUID().toString());
        return result;
    }

    protected final void checkMutation(StorageDocument previous, StorageDocument input) {
        if (previous != null && previous.getLifecycle() != input.getLifecycle()) {
            throw new IllegalArgumentException("Record lifecycle cannot change");
        }

        if (input.getLifecycle() == RecordLifecycle.EPHEMERAL) {
            if (previous != null && !StringUtils.equals(this.tempSessionId, previous.getSessionId())) {
                throw new IllegalStateException("Temporary record belongs to another session");
            }
        } else if (!StringUtils.equals(previous == null ? "" : previous.getRevision(), input.getRevision())) {
            throw new ConcurrentModificationException("Record changed; reload before writing");
        }
    }

    protected static StorageDocument copy(StorageDocument value) {
        return RegistryJson.copy(value, StorageDocument.class);
    }

    protected final void requireOpen() {
        if (this.closed) {
            throw new IllegalStateException("Storage is closed");
        }
    }

    @Override
    public void close() throws Exception {
        this.lifecycleLock.writeLock().lock();
        try {
            if (!this.closed) {
                this.closed = true;
                this.closeStorage();
            }
        } finally {
            this.lifecycleLock.writeLock().unlock();
        }
    }
}

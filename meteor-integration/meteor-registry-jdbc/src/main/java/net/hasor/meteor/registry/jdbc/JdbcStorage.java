/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.jdbc;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;
import net.hasor.dbvisitor.jdbc.core.JdbcTemplate;
import net.hasor.meteor.registry.shared.storage.RecordStorageAccess;
import net.hasor.meteor.registry.shared.storage.StorageDocument;
import net.hasor.meteor.registry.shared.storage.StorageKeyUtils;
import net.hasor.meteor.registry.shared.storage.domain.*;
import net.hasor.meteor.registry.shared.utils.RegistryJson;

/** dbVisitor 存储访问；应用时钟驱动租约，应用负责执行对应数据库的建表脚本。 */
final class JdbcStorage extends RecordStorageAccess {
    private static final Logger                   LOGGER = LoggerFactory.getLogger(JdbcStorage.class);
    private final        DataSource               dataSource;
    private final        String                   scope;
    private final        long                     leaseMillis;
    private final        ScheduledExecutorService heartbeat;
    private              ScheduledExecutorService watches;

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
            boolean scoped = query != null && (query.getType() != null || query.getSource() != null || query.getTarget() != null);
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
                Thread thread = new Thread(task, "Meteor-jdbc-watch-" + this.tempSessionId);
                thread.setDaemon(true);
                return thread;
            });
            executor.setRemoveOnCancelPolicy(true);
            this.watches = executor;
        }

        JdbcStorageWatch watch = new JdbcStorageWatch(snapshot, listener);
        watch.scheduled(this.watches.scheduleWithFixedDelay(watch, 0, 1000, TimeUnit.MILLISECONDS));
        return watch;
    }

    JdbcStorage(DataSource dataSource, String group, long leaseMillis) throws Exception {
        this.dataSource = dataSource;
        this.scope = group;
        this.leaseMillis = leaseMillis;

        this.renew();

        this.heartbeat = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "Meteor-jdbc-lease");
            thread.setDaemon(true);
            return thread;
        });

        long renewalInterval = this.leaseMillis / 3;
        this.heartbeat.scheduleWithFixedDelay(this::heartbeat, renewalInterval, renewalInterval, TimeUnit.MILLISECONDS);
    }

    private void heartbeat() {
        this.lifecycleLock.readLock().lock();
        try {
            if (this.heartbeat.isShutdown()) {
                return;
            }

            try {
                this.renew();
            } catch (Exception failure) {
                LOGGER.warn("Cannot renew JDBC registry session", failure);
            }
        } finally {
            this.lifecycleLock.readLock().unlock();
        }
    }

    private synchronized void renew() throws Exception {
        this.transaction(jdbc -> {
            long now = System.currentTimeMillis();
            // 先清理已过期会话的临时记录，再续约当前会话。
            String expiredRecords = """
                    DELETE FROM meteor_records WHERE scope=? AND owner_id IS NOT NULL
                    AND NOT EXISTS (SELECT 1 FROM meteor_sessions s
                        WHERE s.scope=meteor_records.scope
                        AND s.owner_id=meteor_records.owner_id AND s.expires_at>?)
                    """;
            jdbc.executeUpdate(expiredRecords, new Object[] { this.scope, now });
            long expiresAt = now + this.leaseMillis;

            String update = "UPDATE meteor_sessions SET expires_at=? WHERE scope=? AND owner_id=?";
            Object[] renewal = { expiresAt, this.scope, this.tempSessionId };
            int count = jdbc.executeUpdate(update, renewal);
            if (count == 0) {
                String insert = "INSERT INTO meteor_sessions(scope,owner_id,expires_at) VALUES (?,?,?)";
                Object[] session = { this.scope, this.tempSessionId, expiresAt };
                jdbc.executeUpdate(insert, session);
            }
            jdbc.executeUpdate("DELETE FROM meteor_sessions WHERE scope=? AND expires_at<=?", new Object[] { this.scope, now });
            return null;
        });
    }

    private static String live() {
        return """
                 AND (r.owner_id IS NULL OR EXISTS (SELECT 1 FROM meteor_sessions s
                    WHERE s.scope=r.scope AND s.owner_id=r.owner_id AND s.expires_at>?))
                """;
    }

    private StorageDocument read(JdbcTemplate jdbc, String key) throws Exception {
        String sql = "SELECT r.body FROM meteor_records r WHERE r.scope=? AND r.record_key=?" + live();
        Object[] parameters = { this.scope, StorageKeyUtils.hash(key), System.currentTimeMillis() };
        List<String> bodies = jdbc.queryForList(sql, parameters, (rs, row) -> rs.getString(1));
        return bodies.isEmpty() ? null : RegistryJson.read(bodies.get(0), StorageDocument.class);
    }

    @Override
    protected StorageDocument readDocument(String key) throws Exception {
        try (Connection connection = this.dataSource.getConnection()) {
            return this.read(new JdbcTemplate(connection), key);
        }
    }

    @Override
    protected List<StorageDocument> queryDocuments(String kind, String type, EntityRef source, EntityRef target) throws Exception {
        List<Object> parameters = new ArrayList<>(List.of(this.scope, kind));
        String sql = "SELECT r.body FROM meteor_records r WHERE r.scope=? AND r.kind=?";
        if (type != null) {
            sql += " AND r.record_type=?";
            parameters.add(type);
        }
        if (source != null) {
            sql += " AND r.source_ref=?";
            parameters.add(StorageKeyUtils.hash(StorageKeyUtils.entity(source)));
        }
        if (target != null) {
            sql += " AND r.target_ref=?";
            parameters.add(StorageKeyUtils.hash(StorageKeyUtils.entity(target)));
        }

        try (Connection connection = this.dataSource.getConnection()) {
            JdbcTemplate jdbc = new JdbcTemplate(connection);
            parameters.add(System.currentTimeMillis());
            List<String> bodies = jdbc.queryForList(sql + live(), parameters.toArray(), (rs, row) -> rs.getString(1));
            List<StorageDocument> result = new ArrayList<>();
            for (String body : bodies) {
                result.add(RegistryJson.read(body, StorageDocument.class));
            }
            return result;
        }
    }

    @Override
    protected StorageDocument writeDocument(StorageDocument input) throws Exception {
        this.renew();

        return this.transaction(jdbc -> {
            StorageDocument previous = this.read(jdbc, input.getKey());
            StorageDocument saved = this.prepareWrite(previous, input);
            if (previous == null) {
                // 子实体按所属对象索引，关系按两端索引。
                String sourceRef = null;
                String targetRef = null;
                if (saved.getEntity() != null && saved.getEntity().getRef().parent() != null) {
                    sourceRef = StorageKeyUtils.hash(StorageKeyUtils.entity(saved.getEntity().getRef().parent()));
                }
                RelationRecord relation = saved.getRelation();
                if (relation != null) {
                    sourceRef = StorageKeyUtils.hash(StorageKeyUtils.entity(relation.getSource()));
                    targetRef = StorageKeyUtils.hash(StorageKeyUtils.entity(relation.getTarget()));
                }
                String sql = "INSERT INTO meteor_records(scope,record_key,kind,record_type," + "source_ref,target_ref,owner_id,revision,body) VALUES (?,?,?,?,?,?,?,?,?)";
                String recordKey = StorageKeyUtils.hash(saved.getKey());
                String body = RegistryJson.write(saved);
                Object[] parameters = { this.scope, recordKey, saved.getKind(), saved.getType(), sourceRef, targetRef, saved.getSessionId(), saved.getRevision(), body };

                // 首次创建的竞争交给上层按 CAS 冲突处理，其他 SQL 错误保留原始异常。
                try {
                    jdbc.executeUpdate(sql, parameters);
                } catch (SQLException failure) {
                    if (isDuplicateKey(failure)) {
                        throw new ConcurrentModificationException("Record already exists", failure);
                    }
                    throw failure;
                }
            } else {
                String sql = "UPDATE meteor_records SET revision=?,body=?" + " WHERE scope=? AND record_key=? AND revision=?";
                String recordKey = StorageKeyUtils.hash(saved.getKey());
                String body = RegistryJson.write(saved);
                Object[] parameters = { saved.getRevision(), body, this.scope, recordKey, previous.getRevision() };
                int count = jdbc.executeUpdate(sql, parameters);
                if (count != 1) {
                    throw new ConcurrentModificationException("Record changed during update");
                }
            }

            return saved;
        });
    }

    private static boolean isDuplicateKey(SQLException failure) {
        // dbVisitor 会包装 SQLException，驱动还可能通过 nextException 传递具体错误。
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> pending = new ArrayDeque<>();
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }

            if (current instanceof SQLException sql) {
                // H2、PostgreSQL 使用唯一键 SQLState；其他驱动结合具体错误码判断。
                String state = sql.getSQLState();
                if (StringUtils.equals("23505", state)) {
                    return true;
                }
                boolean duplicate = switch (sql.getErrorCode()) {
                    case 1062, 1 -> StringUtils.equals("23000", state); // MySQL、Oracle
                    // SQL Server 的 X/Open 模式使用 42000，默认模式使用 23000。
                    case 2601, 2627 -> StringUtils.equals("23000", state) || StringUtils.equals("42000", state);
                    default -> false;
                };
                if (duplicate) {
                    return true;
                }
                if (sql.getNextException() != null) {
                    pending.add(sql.getNextException());
                }
            }
            if (current.getCause() != null) {
                pending.add(current.getCause());
            }
        }
        return false;
    }

    @Override
    protected boolean removeDocument(StorageDocument input) throws Exception {
        return this.transaction(jdbc -> {
            StorageDocument previous = this.read(jdbc, input.getKey());
            if (previous == null) {
                return false;
            }

            this.checkMutation(previous, input);
            String sql = "DELETE FROM meteor_records WHERE scope=? AND record_key=? AND revision=?";
            Object[] parameters = { this.scope, StorageKeyUtils.hash(input.getKey()), previous.getRevision() };
            int count = jdbc.executeUpdate(sql, parameters);
            if (count != 1) {
                throw new ConcurrentModificationException("Record changed during deletion");
            } else {
                return true;
            }
        });
    }

    @Override
    protected void closeStorage() throws Exception {
        if (this.watches != null) {
            this.watches.shutdownNow();
        }

        this.heartbeat.shutdownNow();
        this.transaction(jdbc -> {
            jdbc.executeUpdate("DELETE FROM meteor_records WHERE scope=? AND owner_id=?", new Object[] { this.scope, this.tempSessionId });
            jdbc.executeUpdate("DELETE FROM meteor_sessions WHERE scope=? AND owner_id=?", new Object[] { this.scope, this.tempSessionId });
            return null;
        });
    }

    private <T> T transaction(Transaction<T> action) throws Exception {
        try (Connection connection = this.dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = action.execute(new JdbcTemplate(connection));
                connection.commit();
                return result;
            } catch (Exception | Error failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            }
        }
    }

    @FunctionalInterface
    private interface Transaction<T> {
        T execute(JdbcTemplate jdbc) throws Exception;
    }
}

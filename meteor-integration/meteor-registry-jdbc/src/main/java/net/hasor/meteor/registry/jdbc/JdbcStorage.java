/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.jdbc;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.sql.DataSource;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.function.EFunction;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;
import net.hasor.dbvisitor.jdbc.PreparedStatementSetter;
import net.hasor.dbvisitor.jdbc.core.JdbcTemplate;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.shared.storage.DirectoryFilter;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import net.hasor.meteor.registry.shared.utils.RegistryJson;

/** 业务表存储；实例保存身份与运行字段，实例服务关系支持两个方向的查询。 */
final class JdbcStorage implements StorageAccess {
    private static final Logger                   LOGGER              = LoggerFactory.getLogger(JdbcStorage.class);
    private static final Map<String, String>      CATALOG_COLUMNS     = Map.of( //
            "id", "r.search_text", "instance", "r.search_text");
    private static final Map<String, String>      INSTANCE_COLUMNS    = Map.of( //
            "id", "r.search_text", "instance", "r.search_text", "unit", "r.unit_name");
    private static final Map<String, String>      SERVICE_COLUMNS     = Map.of( //
            "id", "r.search_text", "instance", "r.search_text", "role", "r.search_role", //
            "protocol", "r.search_protocol", "address", "r.search_address");
    private static final Map<String, String>      MEMBER_COLUMNS      = Map.of( //
            "id", "i.search_text", "instance", "i.search_text", "unit", "i.unit_name", //
            "application", "a.search_text", "role", "r.search_role", //
            "protocol", "r.search_protocol", "address", "r.search_address");
    private final        DataSource               dataSource;
    private final        long                     leaseMillis;
    private final        String                   sessionId           = UUID.randomUUID().toString();
    private final        Set<String>              registeredInstances = ConcurrentHashMap.newKeySet();
    private final        ScheduledExecutorService leaseScheduler;
    private final        ScheduledExecutorService watchScheduler;
    private final        ReentrantReadWriteLock   lifecycle           = new ReentrantReadWriteLock();
    private volatile     boolean                  closed;

    JdbcStorage(DataSource dataSource, long leaseMillis) throws Exception {
        this.dataSource = dataSource;
        this.leaseMillis = leaseMillis;
        // 打开存储时检查数据库可用性，尚未登记实例时不写入任何租约。
        this.transaction(jdbc -> {
            jdbc.queryForList("SELECT instance_id FROM meteor_instances WHERE 1=0", String.class);
            return null;
        });

        this.leaseScheduler = newScheduler("Meteor-jdbc-lease");
        this.watchScheduler = newScheduler("Meteor-jdbc-watch");
        long heartbeatInterval = leaseMillis / 3;
        this.leaseScheduler.scheduleWithFixedDelay( //
                this::heartbeat, heartbeatInterval, heartbeatInterval, TimeUnit.MILLISECONDS);
    }

    private static ScheduledExecutorService newScheduler(String threadName) {
        return Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    private void heartbeat() {
        for (String instanceId : this.registeredInstances) {
            try {
                this.transaction(jdbc -> {
                    // 先取得当前实例行锁，再判断有效期，避免锁等待跨过到期时间后重新激活实例。
                    String lock = "UPDATE meteor_instances SET expires_at=expires_at" //
                            + " WHERE instance_id=? AND session_id=? AND runtime_body IS NOT NULL";
                    if (jdbc.executeUpdate(lock, new Object[] { instanceId, this.sessionId }) == 0) {
                        return null;
                    }

                    long now = System.currentTimeMillis();
                    String sql = "UPDATE meteor_instances SET expires_at=? WHERE instance_id=? AND expires_at>?";
                    Object[] args = { now + this.leaseMillis, instanceId, now };
                    jdbc.executeUpdate(sql, args);
                    return null;
                });
            } catch (Exception failure) {
                if (this.closed) {
                    return;
                }
                LOGGER.warn("Cannot renew JDBC registry instance leases", failure);
            }
        }
    }

    private String lockOwner(JdbcTemplate jdbc, String instanceId, EntInstanceRuntimeData instance) throws Exception {
        // 最近登记会话保存在实例行中；运行字段清空后仍可识别迟到的旧会话。
        Connection connection = jdbc.getConnection();
        for (int attempt = 0; attempt < 8; attempt++) {
            String lock = "UPDATE meteor_instances SET revision=revision WHERE instance_id=?";
            int locked = jdbc.executeUpdate(lock, new Object[] { instanceId });
            if (locked == 1) {
                String sql = "SELECT session_id FROM meteor_instances WHERE instance_id=?";
                return jdbc.queryForList(sql, new Object[] { instanceId }, String.class).get(0);
            }

            // 首次登记同时建立档案；唯一键冲突后重新锁定已创建的实例行。
            Savepoint point = connection.setSavepoint();
            try {
                EntInstanceCatalogData archive = RegistryJson.copy(instance, EntInstanceCatalogData.class);
                String search = instance.getInstanceId() + " " + instance.getIpAddress() + " " + instance.getProcessId();
                String sql = "INSERT INTO meteor_instances" //
                        + "(instance_id,application_id,unit_name,search_text,revision,body,session_id)" //
                        + " VALUES (?,?,?,?,?,?,?)";
                Object[] args = { instanceId, key(instance.getApplicationName()), instance.getUnitName(), search, //
                        UUID.randomUUID().toString(), RegistryJson.write(archive), this.sessionId };
                jdbc.executeUpdate(sql, args);
                return this.sessionId;
            } catch (SQLException failure) {
                connection.rollback(point);
                if (!isDuplicateKey(failure) || attempt == 7) {
                    throw failure;
                }
            }
        }
        throw new ConcurrentModificationException("Instance ownership changed repeatedly");
    }

    private static String key(String id) {
        if (StringUtils.isBlank(id)) {
            throw new IllegalArgumentException("Registry identity must not be blank");
        }
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String scopeKey(String id) {
        return id == null ? "global" : key(id);
    }

    private static String memberFrom() {
        return " FROM meteor_instance_services r JOIN meteor_instances i" //
                + " ON i.instance_id=r.instance_id AND i.session_id=r.session_id";
    }

    private static String liveMember() {
        return "i.runtime_body IS NOT NULL AND i.expires_at>?";
    }

    private static <T> T body(JdbcTemplate jdbc, String sql, Object[] args, Class<T> type) throws Exception {
        List<String> rows = jdbc.queryForList(sql, args, String.class);
        return rows.isEmpty() ? null : RegistryJson.read(rows.get(0), type);
    }

    @Override
    public String[] applicationUnits(String applicationName) throws Exception {
        return this.transaction(jdbc -> jdbc.queryForList("SELECT DISTINCT unit_name FROM meteor_instances WHERE application_id=? AND unit_name IS NOT NULL ORDER BY unit_name", new Object[] { key(applicationName) }, String.class).toArray(String[]::new));
    }

    @Override
    public String[] serviceUnits(String serviceId) throws Exception {
        return this.transaction(jdbc -> {
            String sql = "SELECT DISTINCT i.unit_name" + memberFrom() //
                    + " WHERE r.service_id=? AND i.unit_name IS NOT NULL AND " + liveMember() //
                    + " ORDER BY i.unit_name";
            Object[] args = { key(serviceId), System.currentTimeMillis() };
            return jdbc.queryForList(sql, args, String.class).toArray(String[]::new);
        });
    }

    @Override
    public EntApplicationCatalogData getApplication(String applicationName) throws Exception {
        return this.transaction(jdbc -> body(jdbc, "SELECT body FROM meteor_applications WHERE application_id=?", new Object[] { key(applicationName) }, EntApplicationCatalogData.class));
    }

    @Override
    public void saveApplication(EntApplicationCatalogData application) throws Exception {
        this.transaction(jdbc -> {
            this.put(jdbc, "meteor_applications", "application_id", new Object[] { key(application.getApplicationName()) }, "search_text", new Object[] { application.getApplicationName() }, application);
            return null;
        });
    }

    @Override
    public RecordPage<EntApplicationCatalogData> applications(DirectoryQuery query) throws Exception {
        return this.transaction(jdbc -> {
            String sql = "SELECT r.application_id,r.body FROM meteor_applications r WHERE 1=1";
            return this.page(jdbc, sql, new ArrayList<>(), "r.application_id", query, //
                    EntApplicationCatalogData.class, CATALOG_COLUMNS);
        });
    }

    @Override
    public EntServiceCatalogData getService(String serviceId) throws Exception {
        return this.transaction(jdbc -> body(jdbc, "SELECT body FROM meteor_services WHERE service_id=?", new Object[] { key(serviceId) }, EntServiceCatalogData.class));
    }

    @Override
    public void saveService(EntServiceCatalogData service) throws Exception {
        this.transaction(jdbc -> {
            this.saveDeclaration(jdbc, service);
            return null;
        });
    }

    private void saveDeclaration(JdbcTemplate jdbc, EntServiceCatalogData service) throws Exception {
        String id = key(service.getServiceId());
        this.lockOrCreate(jdbc, "meteor_services", "service_id", id, "search_text", service.getServiceId(), RegistryJson.copy(service, EntServiceCatalogData.class));
        EntServiceCatalogData merged = body(jdbc, "SELECT body FROM meteor_services WHERE service_id=?", new Object[] { id }, EntServiceCatalogData.class);
        if (service.getName() != null) {
            merged.setName(service.getName());
        }
        if (service.getType() != null) {
            merged.setType(service.getType());
        }
        if (service.getGroup() != null) {
            merged.setGroup(service.getGroup());
        }
        if (service.getVersion() != null) {
            merged.setVersion(service.getVersion());
        }
        this.put(jdbc, "meteor_services", "service_id", new Object[] { id }, "search_text", new Object[] { service.getServiceId() }, merged);
    }

    private void lockOrCreate(JdbcTemplate jdbc, String table, String column, String id, String extraColumn, String extraValue, Object body) throws Exception {
        for (int attempt = 0; attempt < 8; attempt++) {
            int locked = jdbc.executeUpdate("UPDATE " + table + " SET revision=revision WHERE " + column + "=?", new Object[] { id });
            if (locked == 1) {
                return;
            }
            Connection connection = jdbc.getConnection();
            Savepoint point = connection.setSavepoint();
            try {
                String sql = "INSERT INTO " + table + "(" + column + "," + extraColumn + ",revision,body) VALUES (?,?,?,?)";
                jdbc.executeUpdate(sql, new Object[] { id, extraValue, UUID.randomUUID().toString(), RegistryJson.write(body) });
                return;
            } catch (SQLException failure) {
                connection.rollback(point);
                if (!isDuplicateKey(failure) || attempt == 7) {
                    throw failure;
                }
            }
        }
    }

    @Override
    public RecordPage<EntServiceCatalogData> services(DirectoryQuery query) throws Exception {
        return this.transaction(jdbc -> {
            String sql = "SELECT r.service_id,r.body FROM meteor_services r WHERE 1=1";
            return this.page(jdbc, sql, new ArrayList<>(), "r.service_id", query, //
                    EntServiceCatalogData.class, CATALOG_COLUMNS);
        });
    }

    @Override
    public InstanceStatus getInstance(String instanceId) throws Exception {
        return this.transaction(jdbc -> {
            String sql = "SELECT body,runtime_body,expires_at FROM meteor_instances WHERE instance_id=?";
            long now = System.currentTimeMillis();
            List<InstanceStatus> result = jdbc.queryForList(sql, new Object[] { key(instanceId) }, //
                    (rs, row) -> instanceStatus(rs, 1, now));
            return result.isEmpty() ? null : result.get(0);
        });
    }

    private static InstanceStatus instanceStatus(ResultSet rs, int column, long now) throws SQLException {
        try {
            InstanceStatus status = new InstanceStatus();
            status.setInstance(RegistryJson.read(rs.getString(column), EntInstanceCatalogData.class));
            String runtime = rs.getString(column + 1);
            if (runtime != null && rs.getLong(column + 2) > now) {
                status.setRuntime(RegistryJson.read(runtime, EntInstanceRuntimeData.class));
            }
            return status;
        } catch (Exception failure) {
            throw new SQLException("Invalid instance registration body", failure);
        }
    }

    @Override
    public RecordPage<InstanceStatus> instances(String applicationName, DirectoryQuery query) throws Exception {
        return this.transaction(jdbc -> {
            List<Object> args = new ArrayList<>();
            String sql = "SELECT r.instance_id,r.body,r.runtime_body,r.expires_at FROM meteor_instances r WHERE 1=1";
            if (applicationName != null) {
                sql += " AND r.application_id=?";
                args.add(key(applicationName));
            }
            sql = selectPage(sql, args, "r.instance_id", query, INSTANCE_COLUMNS);
            List<String> keys = new ArrayList<>();
            long now = System.currentTimeMillis();
            List<InstanceStatus> result = jdbc.queryForList(sql, limited(args, query.getLimit()), (rs, row) -> {
                keys.add(rs.getString(1));
                return instanceStatus(rs, 2, now);
            });
            return resultPage(result, keys, query.getLimit());
        });
    }

    @Override
    public EntServiceRuntimeData getInstanceService(String instanceId, String serviceId) throws Exception {
        return this.transaction(jdbc -> {
            String sql = "SELECT r.body" + memberFrom() //
                    + " WHERE r.instance_id=? AND r.service_id=? AND " + liveMember();
            Object[] args = { key(instanceId), key(serviceId), System.currentTimeMillis() };
            return body(jdbc, sql, args, EntServiceRuntimeData.class);
        });
    }

    @Override
    public RecordPage<EntServiceRuntimeData> instanceServices(String instanceId, DirectoryQuery query) throws Exception {
        return this.transaction(jdbc -> {
            List<Object> args = new ArrayList<>(List.of(key(instanceId), System.currentTimeMillis()));
            String sql = "SELECT r.service_id,r.body" + memberFrom() //
                    + " WHERE r.instance_id=? AND " + liveMember();
            return this.page(jdbc, sql, args, "r.service_id", query, EntServiceRuntimeData.class, SERVICE_COLUMNS);
        });
    }

    @Override
    public RefServiceRelationData getMember(String serviceId, MetServiceType role, String instanceId) throws Exception {
        return this.transaction(jdbc -> {
            String sql = "SELECT i.runtime_body,r.body" + memberFrom() //
                    + " WHERE r.service_id=? AND r.member_role=? AND r.instance_id=? AND " + liveMember();
            Object[] args = { key(serviceId), Objects.requireNonNull(role).name(), key(instanceId), //
                    System.currentTimeMillis() };
            List<RefServiceRelationData> members = jdbc.queryForList(sql, args, (rs, row) -> member(rs, 1));
            return members.isEmpty() ? null : members.get(0);
        });
    }

    @Override
    public RecordPage<RefServiceRelationData> members(String serviceId, MetServiceType role, DirectoryQuery query) throws Exception {
        return this.transaction(jdbc -> {
            String memberRole = Objects.requireNonNull(role).name();
            List<Object> args = new ArrayList<>(List.of(key(serviceId), memberRole, System.currentTimeMillis()));
            String sql = "SELECT r.instance_id,i.runtime_body,r.body" + memberFrom() //
                    + " JOIN meteor_applications a ON a.application_id=i.application_id" //
                    + " WHERE r.service_id=? AND r.member_role=? AND " + liveMember();
            sql = selectPage(sql, args, "r.instance_id", query, MEMBER_COLUMNS);
            List<String> keys = new ArrayList<>();
            List<RefServiceRelationData> values = jdbc.queryForList(sql, limited(args, query.getLimit()), (rs, row) -> {
                keys.add(rs.getString(1));
                return member(rs, 2);
            });
            return resultPage(values, keys, query.getLimit());
        });
    }

    private static RefServiceRelationData member(ResultSet rs, int column) throws SQLException {
        try {
            RefServiceRelationData member = new RefServiceRelationData();
            member.setInstance(RegistryJson.read(rs.getString(column), EntInstanceRuntimeData.class));
            member.setService(RegistryJson.read(rs.getString(column + 1), EntServiceRuntimeData.class));
            return member;
        } catch (Exception failure) {
            throw new SQLException("Invalid service registration body", failure);
        }
    }

    @Override
    public Map<String, ServiceSummary> serviceSummaries(Collection<String> serviceIds) throws Exception {
        if (serviceIds.isEmpty()) {
            return new HashMap<>();
        }
        return this.transaction(jdbc -> {
            Map<String, String> ids = new HashMap<>();
            Map<String, ServiceSummary> result = new HashMap<>();
            for (String id : serviceIds) {
                ids.put(key(id), id);
                result.put(id, new ServiceSummary());
            }
            String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
            List<Object> args = new ArrayList<>(ids.keySet());
            args.add(System.currentTimeMillis());
            String counts = "SELECT r.service_id,r.member_role,COUNT(*)" + memberFrom() //
                    + " WHERE r.service_id IN (" + placeholders + ") AND " + liveMember() //
                    + " GROUP BY r.service_id,r.member_role";
            jdbc.queryForList(counts, args.toArray(), (rs, row) -> {
                ServiceSummary summary = result.get(ids.get(rs.getString(1)));
                if (StringUtils.equals(rs.getString(2), MetServiceType.Provider.name())) {
                    summary.setProviderCount(rs.getInt(3));
                } else {
                    summary.setConsumerCount(rs.getInt(3));
                }
                return summary;
            });
            Map<String, Set<String>> protocols = new HashMap<>();
            String protocolSql = "SELECT DISTINCT r.service_id,p.protocol_name" + memberFrom() //
                    + " JOIN meteor_member_protocols p ON p.instance_id=r.instance_id" //
                    + " AND p.service_id=r.service_id AND p.session_id=r.session_id" //
                    + " WHERE r.service_id IN (" + placeholders + ") AND " + liveMember();
            jdbc.queryForList(protocolSql, args.toArray(), (rs, row) -> {
                protocols.computeIfAbsent(ids.get(rs.getString(1)), id -> new TreeSet<>()).add(rs.getString(2));
                return null;
            });
            for (Map.Entry<String, Set<String>> entry : protocols.entrySet()) {
                result.get(entry.getKey()).setProtocols(entry.getValue().toArray(String[]::new));
            }
            return result;
        });
    }

    @Override
    public void registerInstance(EntInstanceRuntimeData instance) throws Exception {
        String id = key(instance.getInstanceId());
        String app = key(instance.getApplicationName());
        // 提交数据库登记并记入本地集合后才允许关闭，避免首次登记被遗漏清理。
        this.lifecycle.readLock().lock();
        try {
            this.requireOpen();
            this.inTransaction(jdbc -> {
                String owner = this.lockOwner(jdbc, id, instance);
                if (this.registeredInstances.contains(id) && !StringUtils.equals(owner, this.sessionId)) {
                    throw new IllegalStateException("Instance was replaced by another registration session");
                }

                // 更新应用和实例档案，运行字段独立记录版本，供成员订阅识别变更。
                EntApplicationCatalogData application = new EntApplicationCatalogData();
                application.setApplicationName(instance.getApplicationName());
                Object[] applicationValues = { instance.getApplicationName() };
                this.put(jdbc, "meteor_applications", "application_id", new Object[] { app }, //
                        "search_text", applicationValues, application);
                String searchable = instance.getInstanceId() + " " + instance.getIpAddress() + " " + instance.getProcessId();
                Object[] instanceValues = { app, instance.getUnitName(), searchable };
                EntInstanceCatalogData archive = RegistryJson.copy(instance, EntInstanceCatalogData.class);
                this.put(jdbc, "meteor_instances", "instance_id", new Object[] { id }, //
                        "application_id,unit_name,search_text", instanceValues, archive);

                this.saveRuntime(jdbc, id, owner, instance);
                return null;
            });
            this.registeredInstances.add(id);
        } finally {
            this.lifecycle.readLock().unlock();
        }
    }

    private void saveRuntime(JdbcTemplate jdbc, String instanceId, String owner, EntInstanceRuntimeData instance) throws Exception {
        // 实例行已经锁定；过期或换主只清理当前实例，其他实例的租约独立生效。
        long now = System.currentTimeMillis();
        String select = "SELECT runtime_body FROM meteor_instances WHERE instance_id=? AND expires_at>?";
        List<String> rows = jdbc.queryForList(select, new Object[] { instanceId, now }, String.class);
        String previous = rows.isEmpty() ? null : rows.get(0);
        boolean sameSession = StringUtils.equals(owner, this.sessionId);
        if (!sameSession || previous == null) {
            this.deleteCurrent(jdbc, instanceId, owner);
        }

        // 相同内容只续租，不更改运行版本，避免无变化的上报触发成员通知。
        String content = RegistryJson.write(instance);
        long expiresAt = now + this.leaseMillis;
        if (sameSession && StringUtils.equals(previous, content)) {
            String update = "UPDATE meteor_instances SET expires_at=? WHERE instance_id=?";
            jdbc.executeUpdate(update, new Object[] { expiresAt, instanceId });
            return;
        }
        String update = "UPDATE meteor_instances SET session_id=?,runtime_body=?,runtime_revision=?,expires_at=?" //
                + " WHERE instance_id=?";
        Object[] args = { this.sessionId, content, UUID.randomUUID().toString(), expiresAt, instanceId };
        jdbc.executeUpdate(update, args);
    }

    private void lockInstance(JdbcTemplate jdbc, String instanceId) throws Exception {
        String id = key(instanceId);
        String sql = "UPDATE meteor_instances SET revision=revision" //
                + " WHERE instance_id=? AND session_id=? AND runtime_body IS NOT NULL";
        int locked = jdbc.executeUpdate(sql, new Object[] { id, this.sessionId });
        if (locked != 1) {
            throw new IllegalStateException("Instance registration is missing or replaced: " + instanceId);
        }

        // 锁等待期间也可能到期，服务登记只能使用取锁后仍有效的实例。
        String select = "SELECT instance_id FROM meteor_instances WHERE instance_id=? AND expires_at>?";
        Object[] args = { id, System.currentTimeMillis() };
        if (jdbc.queryForList(select, args, String.class).isEmpty()) {
            throw new IllegalStateException("Instance registration has expired: " + instanceId);
        }
    }

    @Override
    public void registerService(String instanceId, EntServiceRuntimeData service) throws Exception {
        Objects.requireNonNull(service.getRole(), "Service role is required");
        this.transaction(jdbc -> {
            this.lockInstance(jdbc, instanceId);
            this.saveDeclaration(jdbc, service);
            String columns = "session_id,member_role,search_text,search_role,search_protocol,search_address";
            Object[] keys = { key(instanceId), key(service.getServiceId()) };
            Object[] values = { this.sessionId, service.getRole().name(), service.getServiceId(), //
                    DirectoryFilter.text(service, "role", ""), " " + DirectoryFilter.protocols(service) + " ", //
                    DirectoryFilter.addresses(service) };
            this.put(jdbc, "meteor_instance_services", "instance_id,service_id", keys, columns, values, service);
            this.saveProtocols(jdbc, keys, service);
            return null;
        });
    }

    private void saveProtocols(JdbcTemplate jdbc, Object[] keys, EntServiceRuntimeData service) throws Exception {
        jdbc.executeUpdate("DELETE FROM meteor_member_protocols WHERE instance_id=? AND service_id=?", keys);
        Set<String> protocols = DirectoryFilter.protocolNames(service);
        for (String protocol : protocols) {
            String sql = "INSERT INTO meteor_member_protocols" //
                    + "(instance_id,service_id,protocol_name,session_id) VALUES (?,?,?,?)";
            jdbc.executeUpdate(sql, new Object[] { keys[0], keys[1], protocol, this.sessionId });
        }
    }

    @Override
    public void unregisterService(String instanceId, String serviceId) throws Exception {
        this.transaction(jdbc -> {
            this.lockInstance(jdbc, instanceId);
            Object[] ids = { key(instanceId), key(serviceId), this.sessionId };
            for (String table : List.of("meteor_member_protocols", "meteor_instance_services", "meteor_governance")) {
                String sql = "DELETE FROM " + table + " WHERE instance_id=? AND service_id=? AND session_id=?";
                jdbc.executeUpdate(sql, ids);
            }
            return null;
        });
    }

    private void deleteCurrent(JdbcTemplate jdbc, String instanceId, String owner) throws Exception {
        String sql = "UPDATE meteor_instances" //
                + " SET runtime_body=NULL,runtime_revision=NULL,rule_controls=NULL,expires_at=NULL" //
                + " WHERE instance_id=? AND session_id=?";
        Object[] args = { instanceId, owner };
        jdbc.executeUpdate(sql, args);
        for (String table : List.of("meteor_member_protocols", "meteor_instance_services", "meteor_governance")) {
            jdbc.executeUpdate("DELETE FROM " + table + " WHERE instance_id=? AND session_id=?", args);
        }
    }

    private void clearSession(JdbcTemplate jdbc) throws Exception {
        // 先锁实例，再清关系；保留档案与最近会话，防止迟到的旧进程重新取得登记权。
        String sql = "UPDATE meteor_instances" //
                + " SET runtime_body=NULL,runtime_revision=NULL,rule_controls=NULL,expires_at=NULL" //
                + " WHERE session_id=?";
        jdbc.executeUpdate(sql, new Object[] { this.sessionId });
        for (String table : List.of("meteor_member_protocols", "meteor_instance_services", "meteor_governance")) {
            jdbc.executeUpdate("DELETE FROM " + table + " WHERE session_id=?", new Object[] { this.sessionId });
        }
    }

    private <T> EntityRecord<T> configuration(String instanceId, String serviceId, String item, Class<T> type) throws Exception {
        return this.transaction(jdbc -> {
            String instance = scopeKey(instanceId);
            String service = scopeKey(serviceId);
            List<Object> ids = new ArrayList<>(List.of(instance, service, item));
            String sql = "SELECT revision,body FROM meteor_governance WHERE instance_id=? AND service_id=? AND item_name=?";
            if (instanceId != null) {
                String session = new JdbcRuleStore(instance, service).session(jdbc);
                if (session == null) {
                    return null;
                }
                sql += " AND session_id=?";
                ids.add(session);
            }
            List<String[]> rows = jdbc.queryForList(sql, ids.toArray(), //
                    (rs, row) -> new String[] { rs.getString(1), rs.getString(2) });
            if (rows.isEmpty() || rows.get(0)[1] == null) {
                return null;
            }
            EntityRecord<T> result = new EntityRecord<>();
            result.setRevision(rows.get(0)[0]);
            result.setData(RegistryJson.read(rows.get(0)[1], type));
            return result;
        });
    }

    private <T> void saveConfigurationItem(String instanceId, String serviceId, String item, EntityRecord<T> value) throws Exception {
        String body = RegistryJson.write(Objects.requireNonNull(value.getData(), "Configuration data is required"));
        this.transaction(jdbc -> {
            String instance = scopeKey(instanceId);
            String service = scopeKey(serviceId);
            String session = null;
            if (instanceId != null) {
                session = new JdbcRuleStore(instance, service).lockSession(jdbc);
            }
            String revision = UUID.randomUUID().toString();
            if (value.getRevision() == null) {
                if (instanceId == null && serviceId == null && StringUtils.equals(item, "state")) {
                    String sql = "UPDATE meteor_governance SET revision=?,body=?" //
                            + " WHERE instance_id='global' AND service_id='global' AND item_name='state' AND body IS NULL";
                    int changed = jdbc.executeUpdate(sql, new Object[] { revision, body });
                    if (changed != 1) {
                        throw new ConcurrentModificationException("Configuration already exists");
                    }
                    return null;
                }
                try {
                    String sql = "INSERT INTO meteor_governance(instance_id,service_id,item_name,revision,body,session_id)" //
                            + " VALUES (?,?,?,?,?,?)";
                    jdbc.executeUpdate(sql, new Object[] { instance, service, item, revision, body, session });
                } catch (SQLException failure) {
                    if (isDuplicateKey(failure)) {
                        throw new ConcurrentModificationException("Configuration already exists", failure);
                    }
                    throw failure;
                }
            } else {
                String sql = "UPDATE meteor_governance SET revision=?,body=? WHERE instance_id=? AND service_id=? AND item_name=? AND revision=?";
                int changed = jdbc.executeUpdate(sql, new Object[] { revision, body, instance, service, item, value.getRevision() });
                if (changed != 1) {
                    throw new ConcurrentModificationException("Configuration has changed");
                }
            }
            return null;
        });
    }

    private boolean deleteConfigurationItem(String instanceId, String serviceId, String item, String revision) throws Exception {
        return this.transaction(jdbc -> {
            String instance = scopeKey(instanceId);
            String service = scopeKey(serviceId);
            if (instanceId != null) {
                JdbcRuleStore scope = new JdbcRuleStore(instance, service);
                if (scope.session(jdbc) == null) {
                    return false;
                }
                scope.lockSession(jdbc);
            }
            Object[] args = { instance, service, item, revision };
            // 删除全局状态正文仍保留同一详情行上的规则开关。
            String sql = "DELETE FROM meteor_governance";
            List<Object> writeArgs = new ArrayList<>();
            if (instanceId == null && serviceId == null && StringUtils.equals(item, "state")) {
                sql = "UPDATE meteor_governance SET body=NULL,revision=?";
                writeArgs.add(UUID.randomUUID().toString());
            }
            writeArgs.addAll(Arrays.asList(args));
            sql += " WHERE instance_id=? AND service_id=? AND item_name=? AND revision=? AND body IS NOT NULL";
            int removed = jdbc.executeUpdate(sql, writeArgs.toArray());
            if (removed == 0) {
                List<String> remaining = jdbc.queryForList("SELECT revision FROM meteor_governance WHERE instance_id=? AND service_id=? AND item_name=? AND body IS NOT NULL", new Object[] { args[0], args[1], args[2] }, String.class);
                if (!remaining.isEmpty()) {
                    throw new ConcurrentModificationException("Configuration changed during deletion");
                }
            }
            return removed == 1;
        });
    }

    @Override
    public EntityRecord<EntConfigurationData> getConfiguration(String instanceId, String serviceId) throws Exception {
        return this.configuration(instanceId, serviceId, "state", EntConfigurationData.class);
    }

    @Override
    public void saveConfiguration(String instanceId, String serviceId, EntityRecord<EntConfigurationData> value) throws Exception {
        this.saveConfigurationItem(instanceId, serviceId, "state", value);
    }

    @Override
    public EntityRecord<EntRuleConfigurationData> getRule(String instanceId, String serviceId, RuleKind kind) throws Exception {
        JdbcRuleStore rules = new JdbcRuleStore(scopeKey(instanceId), scopeKey(serviceId));
        return this.transaction(jdbc -> rules.get(jdbc, Objects.requireNonNull(kind)));
    }

    @Override
    public void saveRule(String instanceId, String serviceId, RuleKind kind, EntityRecord<EntRuleConfigurationData> value) throws Exception {
        JdbcRuleStore rules = new JdbcRuleStore(scopeKey(instanceId), scopeKey(serviceId));
        this.transaction(jdbc -> {
            // 服务档案可由管理端先声明；实例规则只能写入仍在线的应用登记。
            if (instanceId == null && serviceId != null) {
                EntServiceCatalogData declaration = new EntServiceCatalogData();
                declaration.setServiceId(serviceId);
                this.saveDeclaration(jdbc, declaration);
            }
            rules.save(jdbc, Objects.requireNonNull(kind), value, false);
            return null;
        });
    }

    @Override
    public EntityRecord<EntMetadataData> getMetadata(String instanceId, String serviceId) throws Exception {
        return this.configuration(instanceId, serviceId, "metadata", EntMetadataData.class);
    }

    @Override
    public void saveMetadata(String instanceId, String serviceId, EntityRecord<EntMetadataData> value) throws Exception {
        this.saveConfigurationItem(instanceId, serviceId, "metadata", value);
    }

    @Override
    public boolean deleteConfiguration(String instanceId, String serviceId, String revision) throws Exception {
        return this.deleteConfigurationItem(instanceId, serviceId, "state", revision);
    }

    @Override
    public boolean deleteRule(String instanceId, String serviceId, RuleKind kind, String revision) throws Exception {
        JdbcRuleStore rules = new JdbcRuleStore(scopeKey(instanceId), scopeKey(serviceId));
        EntityRecord<EntRuleConfigurationData> previous = new EntityRecord<>();
        previous.setRevision(revision);
        return this.transaction(jdbc -> rules.save(jdbc, Objects.requireNonNull(kind), previous, true));
    }

    @Override
    public boolean deleteMetadata(String instanceId, String serviceId, String revision) throws Exception {
        return this.deleteConfigurationItem(instanceId, serviceId, "metadata", revision);
    }

    @Override
    public Cancellable watchProviders(String serviceId, Runnable listener) {
        String id = key(serviceId);
        return this.watch(() -> this.transaction(jdbc -> {
            // 实例和服务关系任一变更都通知订阅方；不再复制实例正文到每条成员记录。
            String sql = "SELECT r.instance_id,r.revision,i.runtime_revision,r.session_id" + memberFrom() //
                    + " WHERE r.service_id=? AND r.member_role=? AND " + liveMember();
            Object[] args = { id, MetServiceType.Provider.name(), System.currentTimeMillis() };
            Map<String, String> snapshot = new HashMap<>();
            jdbc.queryForList(sql, args, (rs, row) -> {
                String revision = rs.getString(2) + ":" + rs.getString(3) + ":" + rs.getString(4);
                snapshot.put(rs.getString(1), revision);
                return null;
            });
            return snapshot;
        }), listener);
    }

    @Override
    public Cancellable watchConfiguration(String instanceId, String serviceId, Runnable listener) {
        String instance = scopeKey(instanceId);
        String service = scopeKey(serviceId);
        JdbcRuleStore rules = new JdbcRuleStore(instance, service);
        return this.watch(() -> this.transaction(jdbc -> {
            String sql = "SELECT item_name,revision FROM meteor_governance WHERE instance_id=? AND service_id=?" //
                    + " AND item_name NOT LIKE 'rule/%' AND body IS NOT NULL";
            List<Object> args = new ArrayList<>(List.of(instance, service));
            if (instanceId != null) {
                String session = rules.session(jdbc);
                if (session == null) {
                    return new HashMap<>();
                }
                sql += " AND session_id=?";
                args.add(session);
            }
            Map<String, String> snapshot = versions(jdbc, sql, args.toArray());
            // 规则正文和开关拥有同一个版本；详情撤销或租约失效也会移除这些版本。
            snapshot.putAll(rules.versions(jdbc));
            return snapshot;
        }), listener);
    }

    private static Map<String, String> versions(JdbcTemplate jdbc, String sql, Object[] args) throws Exception {
        Map<String, String> result = new HashMap<>();
        jdbc.queryForList(sql, args, (rs, row) -> {
            result.put(rs.getString(1), rs.getString(2));
            return null;
        });
        return result;
    }

    private Cancellable watch(Callable<Map<String, String>> snapshot, Runnable listener) {
        this.lifecycle.readLock().lock();
        try {
            this.requireOpen();
            JdbcStorageWatch watch = new JdbcStorageWatch(snapshot, Objects.requireNonNull(listener));
            watch.scheduled(this.watchScheduler.scheduleWithFixedDelay(watch, 0, 200, TimeUnit.MILLISECONDS));
            return watch;
        } finally {
            this.lifecycle.readLock().unlock();
        }
    }

    private <T> RecordPage<T> page(JdbcTemplate jdbc, String sql, List<Object> args, String order, DirectoryQuery query, Class<T> type, Map<String, String> columns) throws Exception {
        String statement = selectPage(sql, args, order, query, columns);
        List<String> keys = new ArrayList<>();
        List<String> bodies = jdbc.queryForList(statement, limited(args, query.getLimit()), (rs, row) -> {
            keys.add(rs.getString(1));
            return rs.getString(2);
        });
        List<T> values = new ArrayList<>();
        for (String body : bodies) {
            values.add(RegistryJson.read(body, type));
        }
        return resultPage(values, keys, query.getLimit());
    }

    private static String selectPage(String sql, List<Object> args, String order, DirectoryQuery query, Map<String, String> columns) {
        if (query.getLimit() < 1 || query.getLimit() > 1000) {
            throw new IllegalArgumentException("Page limit must be between 1 and 1000");
        }
        if (query.getCursor() != null) {
            sql += " AND " + order + ">?";
            args.add(query.getCursor());
        }
        if (StringUtils.isNotBlank(query.getSearch())) {
            String field = query.getField();
            if (StringUtils.isBlank(field)) {
                field = "id";
            }
            String column = columns.get(field);
            if (column == null) {
                throw new IllegalArgumentException("Unsupported directory search field: " + field);
            }
            sql += " AND LOWER(" + column + ") LIKE ? ESCAPE '!'";
            String search = query.getSearch().toLowerCase(Locale.ROOT);
            String escaped = search.replace("!", "!!").replace("%", "!%").replace("_", "!_");
            args.add("%" + escaped + "%");
        }
        if (StringUtils.isNotBlank(query.getProtocol())) {
            String column = columns.get("protocol");
            if (column == null) {
                throw new IllegalArgumentException("Protocol filtering requires a service registration directory");
            }
            sql += " AND " + column + " LIKE ? ESCAPE '!'";
            String escaped = query.getProtocol().replace("!", "!!").replace("%", "!%").replace("_", "!_");
            args.add("% " + escaped + " %");
        }
        if (columns.containsKey("unit") && StringUtils.isNotBlank(query.getUnitName())) {
            sql += " AND " + columns.get("unit") + "=?";
            args.add(query.getUnitName());
        }
        return sql + " ORDER BY " + order;
    }

    private static PreparedStatementSetter limited(List<Object> args, int limit) {
        return statement -> {
            statement.setMaxRows(limit + 1);
            for (int i = 0; i < args.size(); i++) {
                statement.setObject(i + 1, args.get(i));
            }
        };
    }

    private static <T> RecordPage<T> resultPage(List<T> values, List<String> keys, int limit) {
        RecordPage<T> result = new RecordPage<>();
        if (values.size() > limit) {
            result.setNextCursor(keys.get(limit - 1));
            values.remove(values.size() - 1);
        }
        result.setItems(values);
        return result;
    }

    private void put(JdbcTemplate jdbc, String table, String keyColumns, Object[] keys, String columns, Object[] values, Object content) throws Exception {
        String[] ids = keyColumns.split(",");
        String[] extra = columns.split(",");
        List<String> terms = new ArrayList<>();
        for (String id : ids) {
            terms.add(id + "=?");
        }
        String where = String.join(" AND ", terms);
        String body = RegistryJson.write(content);
        List<String[]> previous = jdbc.queryForList("SELECT body," + columns + " FROM " + table + " WHERE " + where, keys, (rs, row) -> {
            String[] result = new String[extra.length + 1];
            for (int i = 0; i < result.length; i++) {
                result[i] = rs.getString(i + 1);
            }
            return result;
        });
        if (!previous.isEmpty()) {
            boolean same = StringUtils.equals(previous.get(0)[0], body);
            for (int i = 0; i < values.length; i++) {
                String value = values[i] == null ? null : values[i].toString();
                same &= StringUtils.equals(previous.get(0)[i + 1], value);
            }
            if (same) {
                return;
            }
        }
        String revision = UUID.randomUUID().toString();
        List<Object> updateArgs = new ArrayList<>(Arrays.asList(values));
        updateArgs.add(revision);
        updateArgs.add(body);
        updateArgs.addAll(Arrays.asList(keys));
        List<String> assignments = new ArrayList<>();
        for (String column : extra) {
            assignments.add(column + "=?");
        }
        String update = "UPDATE " + table + " SET " + String.join(",", assignments) + ",revision=?,body=? WHERE " + where;
        int changed = jdbc.executeUpdate(update, updateArgs.toArray());
        if (changed == 1) {
            return;
        }

        // 首次插入冲突在保存点回滚后重试 UPDATE；PG 的失败事务不能直接继续执行 SQL。
        List<Object> insertArgs = new ArrayList<>(Arrays.asList(keys));
        insertArgs.addAll(Arrays.asList(values));
        insertArgs.add(revision);
        insertArgs.add(body);
        String placeholders = String.join(",", Collections.nCopies(insertArgs.size(), "?"));
        Connection connection = jdbc.getConnection();
        Savepoint savepoint = connection.setSavepoint();
        try {
            jdbc.executeUpdate("INSERT INTO " + table + "(" + keyColumns + "," + columns + ",revision,body) VALUES (" + placeholders + ")", insertArgs.toArray());
        } catch (SQLException failure) {
            connection.rollback(savepoint);
            if (!isDuplicateKey(failure)) {
                throw failure;
            }
            if (jdbc.executeUpdate(update, updateArgs.toArray()) != 1) {
                throw new ConcurrentModificationException("Registration changed during first insert", failure);
            }
        }
    }

    private void requireOpen() {
        if (this.closed) {
            throw new IllegalStateException("Registry storage is closed");
        }
    }

    @Override
    public void close() throws Exception {
        this.lifecycle.writeLock().lock();
        try {
            if (this.closed) {
                return;
            }
            this.closed = true;
            this.watchScheduler.shutdownNow();
            this.leaseScheduler.shutdownNow();
            if (!this.registeredInstances.isEmpty()) {
                this.inTransaction(jdbc -> {
                    this.clearSession(jdbc);
                    return null;
                });
            }
        } finally {
            this.lifecycle.writeLock().unlock();
        }
    }

    private <T> T transaction(EFunction<JdbcTemplate, T, Exception> action) throws Exception {
        this.lifecycle.readLock().lock();
        try {
            this.requireOpen();
            return this.inTransaction(action);
        } finally {
            this.lifecycle.readLock().unlock();
        }
    }

    private <T> T inTransaction(EFunction<JdbcTemplate, T, Exception> action) throws Exception {
        try (Connection connection = this.dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = action.eApply(new JdbcTemplate(connection));
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

}

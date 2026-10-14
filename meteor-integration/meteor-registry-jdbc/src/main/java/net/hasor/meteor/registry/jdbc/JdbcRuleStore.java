/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.jdbc;
import java.util.*;
import net.hasor.cobble.StringUtils;
import net.hasor.dbvisitor.jdbc.core.JdbcTemplate;
import net.hasor.meteor.registry.shared.storage.domain.EntRuleConfigurationData;
import net.hasor.meteor.registry.shared.storage.domain.EntityRecord;
import net.hasor.meteor.registry.shared.storage.domain.RuleKind;
import net.hasor.meteor.registry.shared.utils.RegistryJson;

/** 同一事务内更新 XML 正文及业务详情的开关，临时规则跟随应用登记会话。 */
final class JdbcRuleStore {
    private final String   instance;
    private final String   service;
    private final String   table;
    private final String   where;
    private final Object[] ids;
    private final boolean  temporary;

    JdbcRuleStore(String instance, String service) {
        this.instance = instance;
        this.service = service;
        this.temporary = !StringUtils.equals(instance, "global");
        if (this.temporary) {
            if (StringUtils.equals(service, "global")) {
                this.table = "meteor_instances";
                this.where = "instance_id=?";
                this.ids = new Object[] { instance };
            } else {
                this.table = "meteor_instance_services";
                this.where = "instance_id=? AND service_id=?";
                this.ids = new Object[] { instance, service };
            }
        } else if (StringUtils.equals(service, "global")) {
            this.table = "meteor_governance";
            this.where = "instance_id=? AND service_id=? AND item_name='state'";
            this.ids = new Object[] { instance, service };
        } else {
            this.table = "meteor_services";
            this.where = "service_id=?";
            this.ids = new Object[] { service };
        }
    }

    EntityRecord<EntRuleConfigurationData> get(JdbcTemplate jdbc, RuleKind kind) throws Exception {
        // 单条查询读取正文和详情，避免读到同一事务提交前后的两份状态。
        String sql = "SELECT d.rule_controls,g.body,g.revision FROM " + this.table + " d" //
                + " LEFT JOIN meteor_governance g ON g.instance_id=? AND g.service_id=? AND g.item_name=?";
        if (this.temporary) {
            sql += " AND g.session_id=d.session_id";
        }
        sql += " WHERE " + this.qualifiedWhere();
        List<Object> args = new ArrayList<>(List.of(this.instance, this.service, "rule/" + kind.name()));
        args.addAll(Arrays.asList(this.ids));
        sql = this.onlyLive(sql, args);
        List<String[]> rows = jdbc.queryForList(sql, args.toArray(), //
                (rs, row) -> new String[] { rs.getString(1), rs.getString(2), rs.getString(3) });
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> detail = decode(rows.get(0)[0]);
        String revision = rows.get(0)[2];
        if (revision == null) {
            return null;
        }

        EntRuleConfigurationData data = new EntRuleConfigurationData();
        String body = rows.get(0)[1];
        if (body == null && cleared(detail).contains(kind.name())) {
            body = "";
        }
        data.setRule(body);
        data.setEnabled(!Boolean.FALSE.equals(switches(detail).get(kind.name())));
        EntityRecord<EntRuleConfigurationData> value = new EntityRecord<>();
        value.setData(data);
        value.setRevision(revision);
        return value;
    }

    boolean save(JdbcTemplate jdbc, RuleKind kind, EntityRecord<EntRuleConfigurationData> value, boolean delete) throws Exception {
        // 锁住唯一的范围详情；注册和角色更新也修改该行，不需要额外分布式锁。
        if (!this.lock(jdbc)) {
            if (delete) {
                return false;
            }
            throw new IllegalStateException("Rule target is not registered");
        }
        Map<String, String> row = this.detail(jdbc);
        if (row == null) {
            if (delete) {
                return false;
            }
            throw new IllegalStateException("Rule target registration has expired");
        }
        EntityRecord<EntRuleConfigurationData> previous = this.get(jdbc, kind);
        if (delete && previous == null) {
            return false;
        }
        String revision = previous == null ? null : previous.getRevision();
        if (!StringUtils.equals(revision, value.getRevision())) {
            throw new ConcurrentModificationException("Rule has changed: " + kind);
        }

        // 先写正文，再更新详情，任何 SQL 失败均由外层事务一起回滚。
        String nextRevision = UUID.randomUUID().toString();
        EntRuleConfigurationData updated = delete ? null : value.getData();
        if (!delete && updated == null) {
            throw new IllegalArgumentException("Rule data is required");
        }
        String body = updated == null ? null : updated.getRule();
        Object[] ruleIds = { this.instance, this.service, "rule/" + kind.name() };
        String deleteSql = "DELETE FROM meteor_governance WHERE instance_id=? AND service_id=? AND item_name=?";
        jdbc.executeUpdate(deleteSql, ruleIds);
        if (!delete) {
            String sql = "INSERT INTO meteor_governance" //
                    + "(instance_id,service_id,item_name,revision,body,session_id) VALUES (?,?,?,?,?,?)";
            String content = StringUtils.isEmpty(body) ? null : body;
            Object[] args = { this.instance, this.service, ruleIds[2], nextRevision, content, row.get("session") };
            jdbc.executeUpdate(sql, args);
        }
        Map<String, Object> detail = decode(row.get("controls"));
        update(detail, kind, updated);
        List<Object> args = new ArrayList<>();
        args.add(detail.isEmpty() ? null : RegistryJson.write(detail));
        args.addAll(Arrays.asList(this.ids));
        String sql = "UPDATE " + this.table + " SET rule_controls=? WHERE " + this.where;
        jdbc.executeUpdate(sql, args.toArray());
        return true;
    }

    String session(JdbcTemplate jdbc) throws Exception {
        Map<String, String> row = this.detail(jdbc);
        return row == null ? null : row.get("session");
    }

    String lockSession(JdbcTemplate jdbc) throws Exception {
        // 管理员只锁定已有登记并沿用其 session，不创建或取得应用登记所有权。
        if (!this.lock(jdbc)) {
            throw new IllegalStateException("Configuration target is not registered");
        }
        String session = this.session(jdbc);
        if (session == null) {
            throw new IllegalStateException("Configuration target registration has expired");
        }
        return session;
    }

    Map<String, String> versions(JdbcTemplate jdbc) throws Exception {
        Map<String, String> row = this.detail(jdbc);
        Map<String, String> result = new LinkedHashMap<>();
        if (row == null) {
            return result;
        }
        // 直接记录详情开关，外部 SQL 修改也能触发配置订阅。
        if (row.get("controls") != null) {
            result.put("ruleControls", row.get("controls"));
        }
        String sql = "SELECT item_name,revision FROM meteor_governance" //
                + " WHERE instance_id=? AND service_id=? AND item_name LIKE 'rule/%'";
        List<Object> args = new ArrayList<>(List.of(this.instance, this.service));
        if (this.temporary) {
            sql += " AND session_id=?";
            args.add(row.get("session"));
        }
        jdbc.queryForList(sql, args.toArray(), (rs, index) -> {
            result.put(rs.getString(1), rs.getString(2));
            return null;
        });
        return result;
    }

    private Map<String, String> detail(JdbcTemplate jdbc) throws Exception {
        String session = this.temporary ? "d.session_id" : "NULL";
        String sql = "SELECT d.rule_controls," + session + " FROM " + this.table + " d WHERE " + this.qualifiedWhere();
        List<Object> args = new ArrayList<>(Arrays.asList(this.ids));
        sql = this.onlyLive(sql, args);
        List<Map<String, String>> rows = jdbc.queryForList(sql, args.toArray(), (rs, row) -> {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("controls", rs.getString(1));
            values.put("session", rs.getString(2));
            return values;
        });
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String qualifiedWhere() {
        return "d." + this.where.replace(" AND ", " AND d.");
    }

    private boolean lock(JdbcTemplate jdbc) throws Exception {
        if (this.temporary && !StringUtils.equals(this.service, "global")) {
            // 与登记和接管保持相同锁顺序，先固定实例所属会话，再锁实例服务详情。
            String sql = "UPDATE meteor_instances SET revision=revision WHERE instance_id=?";
            if (jdbc.executeUpdate(sql, new Object[] { this.instance }) == 0) {
                return false;
            }
        }
        String sql = "UPDATE " + this.table + " SET revision=revision WHERE " + this.where;
        return jdbc.executeUpdate(sql, this.ids) != 0;
    }

    private String onlyLive(String sql, List<Object> args) {
        if (this.temporary) {
            if (StringUtils.equals(this.service, "global")) {
                sql += " AND d.runtime_body IS NOT NULL";
            } else {
                sql += " AND EXISTS (SELECT 1 FROM meteor_instances current_instance" //
                        + " WHERE current_instance.instance_id=d.instance_id" //
                        + " AND current_instance.session_id=d.session_id" //
                        + " AND current_instance.runtime_body IS NOT NULL)";
            }
            sql += " AND EXISTS (SELECT 1 FROM meteor_sessions lease" //
                    + " WHERE lease.session_id=d.session_id AND lease.expires_at>?)";
            args.add(System.currentTimeMillis());
        }
        return sql;
    }

    private static Map<String, Object> decode(String json) throws Exception {
        if (json == null) {
            return new LinkedHashMap<>();
        }
        return RegistryJson.read(json, Map.class);
    }

    private static Map<String, Boolean> switches(Map<String, Object> detail) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        if (detail.get("ruleEnabled") instanceof Map<?, ?> values) {
            for (Map.Entry<?, ?> entry : values.entrySet()) {
                result.put((String) entry.getKey(), (Boolean) entry.getValue());
            }
        }
        return result;
    }

    private static Set<String> cleared(Map<String, Object> detail) {
        Set<String> result = new LinkedHashSet<>();
        if (detail.get("ruleCleared") instanceof Collection<?> values) {
            for (Object value : values) {
                result.add((String) value);
            }
        }
        return result;
    }

    private static void update(Map<String, Object> detail, RuleKind kind, EntRuleConfigurationData data) {
        Map<String, Boolean> switches = switches(detail);
        Set<String> cleared = cleared(detail);
        String name = kind.name();
        if (data == null) {
            switches.remove(name);
            cleared.remove(name);
        } else {
            switches.put(name, data.isEnabled());
            if (data.getRule() != null && StringUtils.isEmpty(data.getRule())) {
                cleared.add(name);
            } else {
                cleared.remove(name);
            }
        }
        detail.clear();
        if (!switches.isEmpty()) {
            detail.put("ruleEnabled", switches);
        }
        if (!cleared.isEmpty()) {
            detail.put("ruleCleared", cleared);
        }
    }
}

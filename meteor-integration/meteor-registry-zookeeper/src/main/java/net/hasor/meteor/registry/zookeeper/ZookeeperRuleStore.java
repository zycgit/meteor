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
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.registry.shared.storage.domain.EntRuleConfigurationData;
import net.hasor.meteor.registry.shared.storage.domain.EntityRecord;
import net.hasor.meteor.registry.shared.storage.domain.RuleKind;
import net.hasor.meteor.registry.shared.utils.RegistryJson;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.transaction.CuratorOp;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.data.Stat;

/** 规则节点只存 XML；正文和详情中的控制字段通过原生 multi 原子更新。 */
final class ZookeeperRuleStore {
    private final CuratorFramework client;

    ZookeeperRuleStore(CuratorFramework client) {
        this.client = client;
    }

    EntityRecord<EntRuleConfigurationData> get(String detailPath, String rulePath, RuleKind kind) throws Exception {
        for (int attempt = 0; attempt < 8; attempt++) {
            Node detail = this.read(detailPath);
            Node rule = this.read(rulePath);
            Node observed = this.read(detailPath);
            // 两次详情版本一致，正文与开关来自同一次已完成的 multi。
            if (sameVersion(detail, observed)) {
                return record(detail, rule, kind);
            }
        }
        throw new ConcurrentModificationException("Rule is changing: " + rulePath);
    }

    boolean save(String detailPath, String rulePath, String ownerPath, String serviceId, RuleKind kind, EntityRecord<EntRuleConfigurationData> value, boolean delete) throws Exception {
        boolean temporary = ownerPath != null;
        if (!temporary) {
            this.parent(detailPath);
            this.parent(rulePath);
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            // 先记住应用登记代际；注销、角色变更或会话接管都会使本次事务失败。
            Node owner = temporary ? this.read(ownerPath) : null;
            Node detail = this.read(detailPath);
            Node rule = this.read(rulePath);
            if (temporary && (owner == null || detail == null || rule == null)) {
                if (delete) {
                    return false;
                }
                throw new IllegalStateException("Rule target is not registered: " + detailPath);
            }
            EntityRecord<EntRuleConfigurationData> previous = record(detail, rule, kind);
            if (delete && previous == null) {
                return false;
            }
            String revision = previous == null ? null : previous.getRevision();
            if (!StringUtils.equals(revision, value.getRevision())) {
                throw new ConcurrentModificationException("Rule changed: " + rulePath);
            }

            Map<String, Object> fields = fields(detail);
            if (!temporary && serviceId != null) {
                fields.put("serviceId", serviceId);
            }
            EntRuleConfigurationData update = delete ? null : value.getData();
            updateControl(fields, kind, update);
            String content = update == null ? null : update.getRule();
            byte[] xml = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);

            // 全量详情基于刚读取的版本合并，只修改当前规则，不覆盖登记字段或其他规则。
            List<CuratorOp> operations = new ArrayList<>();
            if (temporary) {
                operations.add(this.client.transactionOp().check() //
                        .withVersion(owner.stat.getVersion()).forPath(ownerPath));
            }
            byte[] json = RegistryJson.write(fields).getBytes(StandardCharsets.UTF_8);
            this.put(operations, detailPath, detail, json);
            this.put(operations, rulePath, rule, xml);
            try {
                this.client.transaction().forOperations(operations);
                return true;
            } catch (KeeperException.BadVersionException | KeeperException.NodeExistsException | KeeperException.NoNodeException conflict) {
                // 无关规则或登记变化可重读合并；当前规则变化在下一轮按独立版本拒绝。
                if (attempt == 7) {
                    throw new ConcurrentModificationException("Rule changed: " + rulePath, conflict);
                }
            }
        }
        throw new ConcurrentModificationException("Rule changed: " + rulePath);
    }

    private static EntityRecord<EntRuleConfigurationData> record(Node detail, Node rule, RuleKind kind) throws Exception {
        if (detail == null && rule != null && rule.stat.getEphemeralOwner() != 0) {
            return null;
        }
        Map<String, Object> fields = fields(detail);
        Map<String, Boolean> switches = switches(fields);
        Boolean enabled = switches.get(kind.name());
        boolean cleared = cleared(fields).contains(kind.name());
        String content = rule == null ? null : new String(rule.body, StandardCharsets.UTF_8);
        if (StringUtils.isEmpty(content)) {
            content = cleared ? "" : null;
        }
        if (content == null && enabled == null) {
            return null;
        }

        EntRuleConfigurationData data = new EntRuleConfigurationData();
        data.setRule(content);
        data.setEnabled(enabled == null || enabled);
        EntityRecord<EntRuleConfigurationData> record = new EntityRecord<>();
        record.setData(data);
        // 当前规则节点的版本不受其他规则影响；创建 zxid 防止删除重建复用旧版本。
        String bodyVersion = rule == null ? "absent" : rule.stat.getCzxid() + ":" + rule.stat.getVersion();
        record.setRevision(bodyVersion + ":" + enabled + ":" + cleared);
        return record;
    }

    private static Map<String, Object> fields(Node node) throws Exception {
        if (node == null || node.body.length == 0) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> values = RegistryJson.read(new String(node.body, StandardCharsets.UTF_8), Map.class);
        return values == null ? new LinkedHashMap<>() : values;
    }

    private static Map<String, Boolean> switches(Map<?, ?> fields) {
        Map<String, Boolean> switches = new LinkedHashMap<>();
        if (fields.get("ruleEnabled") instanceof Map<?, ?> values) {
            for (Map.Entry<?, ?> value : values.entrySet()) {
                switches.put((String) value.getKey(), (Boolean) value.getValue());
            }
        }
        return switches;
    }

    private static Set<String> cleared(Map<?, ?> fields) {
        Set<String> cleared = new LinkedHashSet<>();
        if (fields.get("ruleCleared") instanceof Collection<?> values) {
            for (Object value : values) {
                cleared.add((String) value);
            }
        }
        return cleared;
    }

    private static void updateControl(Map<String, Object> fields, RuleKind kind, EntRuleConfigurationData update) {
        Map<String, Boolean> switches = switches(fields);
        Set<String> cleared = cleared(fields);
        if (update == null) {
            switches.remove(kind.name());
            cleared.remove(kind.name());
        } else {
            switches.put(kind.name(), update.isEnabled());
            if (StringUtils.equals(update.getRule(), "")) {
                cleared.add(kind.name());
            } else {
                cleared.remove(kind.name());
            }
        }
        if (switches.isEmpty()) {
            fields.remove("ruleEnabled");
        } else {
            fields.put("ruleEnabled", switches);
        }
        if (cleared.isEmpty()) {
            fields.remove("ruleCleared");
        } else {
            fields.put("ruleCleared", cleared);
        }
    }

    private Node read(String path) throws Exception {
        Stat stat = new Stat();
        try {
            return new Node(this.client.getData().storingStatIn(stat).forPath(path), stat);
        } catch (KeeperException.NoNodeException absent) {
            return null;
        }
    }

    private static boolean sameVersion(Node left, Node right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.stat.getCzxid() == right.stat.getCzxid() && left.stat.getVersion() == right.stat.getVersion();
    }

    private void put(List<CuratorOp> operations, String path, Node previous, byte[] body) throws Exception {
        if (previous == null) {
            operations.add(this.client.transactionOp().create().forPath(path, body));
        } else {
            operations.add(this.client.transactionOp().setData() //
                    .withVersion(previous.stat.getVersion()).forPath(path, body));
        }
    }

    private void parent(String path) throws Exception {
        String directory = path.substring(0, path.lastIndexOf('/'));
        try {
            this.client.create().creatingParentsIfNeeded().forPath(directory);
        } catch (KeeperException.NodeExistsException ignored) {
            // 业务目录可由并发服务发布或治理操作共同创建。
        }
    }

    private static final class Node {
        private final byte[] body;
        private final Stat   stat;

        private Node(byte[] body, Stat stat) {
            this.body = body;
            this.stat = stat;
        }
    }
}

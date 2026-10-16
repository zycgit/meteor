/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.zookeeper;
import java.util.ConcurrentModificationException;
import java.util.Map;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.EntMetadataData;
import net.hasor.meteor.registry.shared.storage.domain.EntRuleConfigurationData;
import net.hasor.meteor.registry.shared.storage.domain.EntityRecord;
import net.hasor.meteor.registry.shared.storage.domain.RuleKind;
import org.apache.curator.test.TestingServer;
import org.junit.Test;
import static org.junit.Assert.*;

/** 治理正文的 CAS、作用范围和读取隔离。 */
public class ZookeeperGovernanceTest {
    @Test
    public void metadataCreationRejectsStaleRevision() throws Exception {
        // 同一份元信息正文只能以首次插入的空版本提交一次。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess storage = configuration.openStorage()) {
                EntityRecord<EntMetadataData> initial = metadata();
                storage.saveMetadata(null, "A", initial);

                assertThrows(ConcurrentModificationException.class, () -> storage.saveMetadata(null, "A", initial));
                Map<String, Object> persisted = storage.getMetadata(null, "A").getData().getMetadata();
                assertEquals(Map.of("a", 1, "b", 2), persisted);
            }
        }
    }

    @Test
    public void savingRuleDoesNotChangeMetadataRevisionOrContent() throws Exception {
        // 元信息与路由规则属于同一服务，但存储和版本相互独立。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess storage = configuration.openStorage()) {
                storage.saveMetadata(null, "A", metadata());
                EntityRecord<EntMetadataData> previous = storage.getMetadata(null, "A");

                EntRuleConfigurationData body = new EntRuleConfigurationData();
                body.setRule("<route/>");
                EntityRecord<EntRuleConfigurationData> rule = new EntityRecord<>();
                rule.setData(body);
                storage.saveRule(null, "A", RuleKind.SERVICE_ROUTE, rule);

                EntityRecord<EntMetadataData> unchanged = storage.getMetadata(null, "A");
                assertEquals(previous.getRevision(), unchanged.getRevision());
                assertEquals(Map.of("a", 1, "b", 2), unchanged.getData().getMetadata());
            }
        }
    }

    @Test
    public void metadataQueryReturnsDetachedMap() throws Exception {
        // 获取元信息后在本地修改，不能绕过 CAS 写回存储。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess storage = configuration.openStorage()) {
                storage.saveMetadata(null, "A", metadata());
                EntityRecord<EntMetadataData> read = storage.getMetadata(null, "A");
                read.getData().getMetadata().clear();

                Map<String, Object> persisted = storage.getMetadata(null, "A").getData().getMetadata();
                assertEquals(Map.of("a", 1, "b", 2), persisted);
            }
        }
    }

    @Test
    public void serviceMetadataDoesNotCreateInstanceOverride() throws Exception {
        // 服务范围与实例加服务范围采用各自的记录。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess storage = configuration.openStorage()) {
                storage.saveMetadata(null, "A", metadata());

                assertNotNull(storage.getMetadata(null, "A"));
                assertNull(storage.getMetadata("one", "A"));
            }
        }
    }

    @Test
    public void deletedAndRecreatedMetadataRejectsOldVersion() throws Exception {
        // 保留删除前的版本，随后在相同位置重新建立元信息。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess storage = configuration.openStorage()) {
                EntityRecord<EntMetadataData> initial = metadata();
                storage.saveMetadata(null, "echo", initial);
                EntityRecord<EntMetadataData> previous = storage.getMetadata(null, "echo");
                storage.deleteMetadata(null, "echo", previous.getRevision());
                storage.saveMetadata(null, "echo", initial);

                // 删除重建不能复用旧版本，否则过期写入会覆盖新记录。
                assertThrows(ConcurrentModificationException.class, () -> {
                    storage.saveMetadata(null, "echo", previous);
                });
            }
        }
    }

    private static EntityRecord<EntMetadataData> metadata() {
        EntMetadataData body = new EntMetadataData();
        body.getMetadata().putAll(Map.of("a", 1, "b", 2));
        EntityRecord<EntMetadataData> record = new EntityRecord<>();
        record.setData(body);
        return record;
    }

}

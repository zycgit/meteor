/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.nacos.unit;
import java.util.ConcurrentModificationException;
import java.util.Map;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.EntMetadataData;
import net.hasor.meteor.registry.shared.storage.domain.EntRuleConfigurationData;
import net.hasor.meteor.registry.shared.storage.domain.EntityRecord;
import net.hasor.meteor.registry.shared.storage.domain.RuleKind;
import org.junit.Test;
import static net.hasor.meteor.registry.nacos.unit.NacosTestData.instance;
import static net.hasor.meteor.registry.nacos.unit.NacosTestData.service;
import static org.junit.Assert.*;

public class NacosRuntimeGovernanceTest {
    @Test
    public void administratorDoesNotOwnTheApplicationRegistration() throws Exception {
        // 应用创建临时节点，另一个存储连接更新其治理配置。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess application = sdk.configuration().openStorage()) {
            application.registerInstance(instance("one"));
            application.registerService("one", service("echo"));
            try (StorageAccess admin = sdk.configuration().openStorage()) {
                admin.saveMetadata("one", "echo", metadata("blue"));
            }

            // 管理端关闭不撤回节点，也不使下发配置丢失。
            assertEquals("blue", application.getMetadata("one", "echo").getData().getMetadata().get("team"));
            assertNotNull(application.getMember("echo", MetServiceType.Provider, "one"));
            String runtimeGovernancePrefix = "governance.services.echo.instances.";
            assertFalse(sdk.documents.keySet().stream().anyMatch(key -> key.startsWith(runtimeGovernancePrefix)));
        }
    }

    @Test
    public void publisherRefreshDoesNotOverwriteRuntimeGovernance() throws Exception {
        // 治理元信息由 Naming 管理接口维护。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess application = sdk.configuration().openStorage(); //
             StorageAccess admin = sdk.configuration().openStorage()) {
            application.registerInstance(instance("one"));
            application.registerService("one", service("echo"));
            admin.saveMetadata("one", "echo", metadata("blue"));

            // 应用重复上报登记不能把治理字段恢复为空。
            application.registerInstance(instance("one"));
            application.registerService("one", service("echo"));
            assertEquals("blue", admin.getMetadata("one", "echo").getData().getMetadata().get("team"));
        }
    }

    @Test
    public void crossScopeGovernanceOnlyChangesOneInstanceAndService() throws Exception {
        // 两个实例提供同一服务，其中一个实例还提供另一项服务。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.registerInstance(instance("one"));
            storage.registerInstance(instance("two"));
            storage.registerService("one", service("echo"));
            storage.registerService("one", service("other"));
            storage.registerService("two", service("echo"));

            // 实例 + 服务覆盖与实例级、服务级、其他交叉范围相互独立。
            storage.saveMetadata("one", "echo", metadata("blue"));
            assertNotNull(storage.getMetadata("one", "echo"));
            assertNull(storage.getMetadata("two", "echo"));
            assertNull(storage.getMetadata("one", "other"));
            assertNull(storage.getMetadata("one", null));
            assertNull(storage.getMetadata(null, "echo"));
        }
    }

    @Test
    public void staleRuntimeRevisionCannotOverwriteNewerValue() throws Exception {
        // 两个管理读取之间发生另一次写入。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.registerInstance(instance("one"));
            storage.saveMetadata("one", null, metadata("blue"));
            EntityRecord<EntMetadataData> stale = storage.getMetadata("one", null);
            EntityRecord<EntMetadataData> update = storage.getMetadata("one", null);
            update.getData().getMetadata().put("team", "red");
            storage.saveMetadata("one", null, update);

            // Naming 管理接口读取当前版本，拒绝旧版本回写。
            assertThrows(ConcurrentModificationException.class, () -> storage.saveMetadata("one", null, stale));
            assertEquals("red", storage.getMetadata("one", null).getData().getMetadata().get("team"));
        }
    }

    @Test
    public void runtimeRuleUpdatePreservesOtherMetadataFields() throws Exception {
        // 同一实例治理节点上已有元信息配置。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.registerInstance(instance("one"));
            storage.saveMetadata("one", null, metadata("blue"));
            String revision = storage.getMetadata("one", null).getRevision();
            EntityRecord<EntRuleConfigurationData> rule = new EntityRecord<>();
            rule.setData(new EntRuleConfigurationData());
            rule.getData().setRule("<route/>");

            // 规则只更新自己的字段，不覆盖元信息和节点身份。
            storage.saveRule("one", null, RuleKind.SERVICE_ROUTE, rule);
            assertEquals(revision, storage.getMetadata("one", null).getRevision());
            assertNotNull(storage.getRule("one", null, RuleKind.SERVICE_ROUTE));
            assertEquals("one", sdk.nodes.get("governance.instances.one").get("one").getMetadata().get("instanceId"));
        }
    }

    @Test
    public void offlineInstanceCannotLeavePersistentGovernance() throws Exception {
        // 目标实例没有在线登记。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess admin = sdk.configuration().openStorage()) {
            EntityRecord<EntMetadataData> value = metadata("blue");

            // 写入明确失败，不能偷偷创建持久治理档案。
            assertThrows(IllegalStateException.class, () -> admin.saveMetadata("offline", null, value));
            assertTrue(sdk.documents.isEmpty());
            assertTrue(sdk.nodes.isEmpty());
        }
    }

    @Test
    public void withdrawalRemovesOnlyTheTemporaryGovernance() throws Exception {
        // 同一个服务存在全局、服务级及实例交叉覆盖。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.registerInstance(instance("one"));
            storage.registerService("one", service("echo"));
            storage.saveMetadata(null, null, metadata("global"));
            storage.saveMetadata(null, "echo", metadata("service"));
            storage.saveMetadata("one", "echo", metadata("instance"));

            // 撤回登记后交叉治理消失，持久默认配置保留。
            storage.unregisterService("one", "echo");
            assertNull(storage.getMetadata("one", "echo"));
            assertNotNull(storage.getMetadata(null, null));
            assertNotNull(storage.getMetadata(null, "echo"));
            assertTrue(sdk.documents.containsKey("governance.global.metadata"));
            assertTrue(sdk.documents.containsKey("governance.services.echo.metadata"));
        }
    }

    @Test
    public void runtimeWriteWaitsForItsRevisionToBecomeReadable() throws Exception {
        // Naming 已接受更新，但管理查询快照延迟两次读取才可见。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.registerInstance(instance("one"));
            sdk.adminVisibilityDelay = 2;

            // 返回前必须读到本次版本，不能仅凭写入接口成功就释放锁。
            storage.saveMetadata("one", null, metadata("blue"));
            assertEquals(4, sdk.adminReads.get());
            assertEquals("blue", storage.getMetadata("one", null).getData().getMetadata().get("team"));
        }
    }

    private static EntityRecord<EntMetadataData> metadata(String team) {
        EntityRecord<EntMetadataData> record = new EntityRecord<>();
        EntMetadataData body = new EntMetadataData();
        body.setMetadata(Map.of("team", team));
        record.setData(body);
        return record;
    }
}

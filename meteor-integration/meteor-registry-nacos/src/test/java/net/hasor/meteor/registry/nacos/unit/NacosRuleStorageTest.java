/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.nacos.unit;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import net.hasor.meteor.registry.shared.utils.RegistryJson;
import org.junit.Test;
import static net.hasor.meteor.registry.nacos.unit.NacosTestData.instance;
import static net.hasor.meteor.registry.nacos.unit.NacosTestData.service;
import static org.junit.Assert.*;

public class NacosRuleStorageTest {
    @Test
    public void serviceRuleStoresOnlyXmlAndKeepsSwitchInServiceDetail() throws Exception {
        // 服务声明与规则开关共用详情，XML 单独保存。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.saveService(service("echo"));
            String xml = "<?xml version=\"1.0\"?>\n<route/>\n";
            storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, rule(xml, false));

            // 正文逐字保留，开关没有产生独立 Key。
            assertEquals(xml, sdk.documents.get("governance.services.echo.rule.SERVICE_ROUTE"));
            Map<?, ?> detail = RegistryJson.read(sdk.documents.get("services.echo"), Map.class);
            assertEquals(Map.of("SERVICE_ROUTE", false), detail.get("ruleEnabled"));
            assertFalse(sdk.documents.keySet().stream().anyMatch(key -> key.endsWith(".enabled")));
        }
    }

    @Test
    public void serviceRefreshPreservesAdministrativeSwitches() throws Exception {
        // 管理端先禁用规则，再由应用更新服务声明。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, rule("<route/>", false));
            EntServiceRuntimeData declaration = service("echo");
            declaration.setName("UpdatedEcho");
            storage.saveService(declaration);

            // 声明更新成功，同时保留规则正文和开关。
            assertEquals("UpdatedEcho", storage.getService("echo").getName());
            assertFalse(storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE).getData().isEnabled());
            assertEquals("<route/>", sdk.documents.get("governance.services.echo.rule.SERVICE_ROUTE"));
        }
    }

    @Test
    public void globalStateUpdatePreservesRuleSwitches() throws Exception {
        // 全局规则开关归入已有 governance.global.state。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.saveRule(null, null, RuleKind.SERVICE_ROUTE, rule("<route/>", false));
            EntityRecord<EntConfigurationData> state = new EntityRecord<>();
            state.setData(new EntConfigurationData());
            state.getData().setBlock(true);
            storage.saveConfiguration(null, null, state);

            // 状态写入只更新自身字段，规则开关仍然禁用。
            assertTrue(storage.getConfiguration(null, null).getData().getBlock());
            assertFalse(storage.getRule(null, null, RuleKind.SERVICE_ROUTE).getData().isEnabled());
        }
    }

    @Test
    public void globalRuleUpdatePreservesStateRevision() throws Exception {
        // 已有全局状态具备独立版本。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            EntityRecord<EntConfigurationData> state = new EntityRecord<>();
            state.setData(new EntConfigurationData());
            state.getData().setBlock(true);
            storage.saveConfiguration(null, null, state);
            String revision = storage.getConfiguration(null, null).getRevision();

            // 新增规则开关不改动状态的内容或版本。
            storage.saveRule(null, null, RuleKind.FLOW_CONTROL, rule("<flow/>", false));
            assertEquals(revision, storage.getConfiguration(null, null).getRevision());
            assertTrue(storage.getConfiguration(null, null).getData().getBlock());
            assertEquals("<flow/>", sdk.documents.get("governance.global.rule.FLOW_CONTROL"));
        }
    }

    @Test
    public void instanceRuleSwitchSurvivesRegistrationRefresh() throws Exception {
        // 实例规则正文属于治理节点，开关属于实例主记录。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.registerInstance(instance("one"));
            storage.saveRule("one", null, RuleKind.SERVICE_ROUTE, rule("<route/>", false));
            EntInstanceRuntimeData update = instance("one");
            update.setUnitName("west");
            storage.registerInstance(update);

            // 管理字段没有混入应用快照，重新上报后仍有效。
            Map<String, String> detail = sdk.nodes.get("instances.one").get("one").getMetadata();
            assertEquals("{\"SERVICE_ROUTE\":false}", detail.get("ruleEnabled"));
            Map<String, String> governance = sdk.nodes.get("governance.instances.one").get("one").getMetadata();
            assertEquals("<route/>", governance.get("rule.SERVICE_ROUTE"));
            assertEquals("west", storage.getInstance("one").getRuntime().getUnitName());
            assertFalse(storage.getRule("one", null, RuleKind.SERVICE_ROUTE).getData().isEnabled());
        }
    }

    @Test
    public void crossScopeSwitchLivesOnlyOnItsMembership() throws Exception {
        // 两个实例发布同一服务，治理只针对其中一个关系。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.registerInstance(instance("one"));
            storage.registerInstance(instance("two"));
            storage.registerService("one", service("echo"));
            storage.registerService("two", service("echo"));
            storage.saveRule("one", "echo", RuleKind.SERVICE_ROUTE, rule("<route/>", false));
            storage.registerService("one", service("echo"));

            // 只有目标成员携带开关，反向引用和相邻实例不复制它。
            Map<String, String> member = sdk.nodes.get("services.echo.providers").get("one").getMetadata();
            assertEquals("{\"SERVICE_ROUTE\":false}", member.get("ruleEnabled"));
            assertNull(sdk.nodes.get("instances.one").get("one").getMetadata().get("ruleEnabled"));
            assertNull(storage.getRule("two", "echo", RuleKind.SERVICE_ROUTE));
            assertNull(storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE));
        }
    }

    @Test
    public void roleChangeMovesCrossScopeSwitchToNewMembership() throws Exception {
        // 同一实例服务从提供者切换成消费者。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.registerInstance(instance("one"));
            storage.registerService("one", service("echo"));
            storage.saveRule("one", "echo", RuleKind.SERVICE_ROUTE, rule("<route/>", false));
            EntServiceRuntimeData consumer = service("echo");
            consumer.setRole(MetServiceType.Consumer);
            storage.registerService("one", consumer);

            // 开关随关系迁移，旧角色退出后不丢失覆盖。
            assertNull(storage.getMember("echo", MetServiceType.Provider, "one"));
            Map<String, String> consumerMetadata = sdk.nodes.get("services.echo.consumers").get("one").getMetadata();
            assertEquals("{\"SERVICE_ROUTE\":false}", consumerMetadata.get("ruleEnabled"));
            assertFalse(storage.getRule("one", "echo", RuleKind.SERVICE_ROUTE).getData().isEnabled());
        }
    }

    @Test
    public void disabledRuleWithoutBodyRemainsAnExplicitOverride() throws Exception {
        // 不设置 XML 也可以先禁用继承规则。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, rule(null, false));

            // 显式开关使规则记录存在，但不生成 JSON 或虚假 XML 正文。
            EntityRecord<EntRuleConfigurationData> result = storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE);
            assertNotNull(result);
            assertNull(result.getData().getRule());
            assertFalse(result.getData().isEnabled());
            assertNull(sdk.documents.get("governance.services.echo.rule.SERVICE_ROUTE"));
        }
    }

    @Test
    public void plainXmlWithoutSwitchDefaultsToEnabled() throws Exception {
        // 运维直接在 Nacos 编辑纯 XML，不需要了解包装格式。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            sdk.documents.put("governance.services.echo.rule.SERVICE_ROUTE", "<route/>");

            // 没有显式开关时沿用默认启用语义。
            EntityRecord<EntRuleConfigurationData> result = storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE);
            assertEquals("<route/>", result.getData().getRule());
            assertTrue(result.getData().isEnabled());
        }
    }

    @Test
    public void staleRuleRevisionCannotOverwriteAChangedSwitch() throws Exception {
        // 先读取规则，再通过另一份快照禁用它。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, rule("<route/>", true));
            EntityRecord<EntRuleConfigurationData> stale = storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE);
            EntityRecord<EntRuleConfigurationData> current = storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE);
            current.getData().setEnabled(false);
            storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, current);

            // 开关也是内容版本的一部分，旧快照无法恢复它。
            assertThrows(ConcurrentModificationException.class, //
                    () -> storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, stale));
            assertFalse(storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE).getData().isEnabled());
        }
    }

    @Test
    public void deleteRuleClearsOnlyItsBodyAndSwitch() throws Exception {
        // 一个范围有两条独立规则。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, rule("<route/>", false));
            storage.saveRule(null, "echo", RuleKind.FLOW_CONTROL, rule("<flow/>", false));
            String revision = storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE).getRevision();
            assertTrue(storage.deleteRule(null, "echo", RuleKind.SERVICE_ROUTE, revision));

            // 删除恢复未配置状态，其他规则和服务详情继续存在。
            assertNull(storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE));
            assertNull(sdk.documents.get("governance.services.echo.rule.SERVICE_ROUTE"));
            assertFalse(storage.getRule(null, "echo", RuleKind.FLOW_CONTROL).getData().isEnabled());
            assertNotNull(storage.getService("echo"));
        }
    }

    @Test
    public void runtimeRuleDeletionPreservesOtherRules() throws Exception {
        // 同一实例有路由和流控两种覆盖。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.registerInstance(instance("one"));
            storage.saveRule("one", null, RuleKind.SERVICE_ROUTE, rule("<route/>", false));
            storage.saveRule("one", null, RuleKind.FLOW_CONTROL, rule("<flow/>", true));
            String revision = storage.getRule("one", null, RuleKind.SERVICE_ROUTE).getRevision();
            assertTrue(storage.deleteRule("one", null, RuleKind.SERVICE_ROUTE, revision));

            // 清空正文并移除对应开关，不改写相邻治理字段。
            assertNull(storage.getRule("one", null, RuleKind.SERVICE_ROUTE));
            assertEquals("<flow/>", storage.getRule("one", null, RuleKind.FLOW_CONTROL).getData().getRule());
        }
    }

    @Test
    public void serviceSwitchNotificationRefreshesRuleWithoutBodyChange() throws Exception {
        // 订阅服务范围，正文保持不变，随后仅编辑详情内的开关。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, rule("<route/>", true));
            CountDownLatch initial = new CountDownLatch(1);
            CountDownLatch disabled = new CountDownLatch(1);
            storage.watchConfiguration(null, "echo", () -> {
                try {
                    initial.countDown();
                    if (!storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE).getData().isEnabled()) {
                        disabled.countDown();
                    }
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            assertTrue(initial.await(3, TimeUnit.SECONDS));

            // 通过 Config 原生通知验证新订阅来源。
            sdk.documents.put("services.echo", "{\"serviceId\":\"echo\",\"ruleEnabled\":{\"SERVICE_ROUTE\":false}}");
            sdk.configChanged("services.echo");
            assertTrue(disabled.await(3, TimeUnit.SECONDS));
            assertEquals("<route/>", sdk.documents.get("governance.services.echo.rule.SERVICE_ROUTE"));
        }
    }

    @Test
    public void runtimeSwitchNotificationRefreshesRuleWithoutBodyChange() throws Exception {
        // 实例服务规则订阅必须包含关系主节点。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.registerInstance(instance("one"));
            storage.registerService("one", service("echo"));
            storage.saveRule("one", "echo", RuleKind.SERVICE_ROUTE, rule("<route/>", true));
            CountDownLatch initial = new CountDownLatch(1);
            CountDownLatch disabled = new CountDownLatch(1);
            storage.watchConfiguration("one", "echo", () -> {
                try {
                    initial.countDown();
                    if (!storage.getRule("one", "echo", RuleKind.SERVICE_ROUTE).getData().isEnabled()) {
                        disabled.countDown();
                    }
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            });
            assertTrue(initial.await(3, TimeUnit.SECONDS));

            // 模拟 Naming 关系详情变化，治理正文完全不动。
            sdk.nodes.get("services.echo.providers").get("one").getMetadata() //
                    .put("ruleEnabled", "{\"SERVICE_ROUTE\":false}");
            sdk.namingChanged("services.echo.providers");
            assertTrue(disabled.await(3, TimeUnit.SECONDS));
        }
    }

    @Test
    public void emptyBodyPreservesExplicitClearSemantics() throws Exception {
        // 空字符串与 null 的含义不同，空正文必须停止上层继承。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, rule("", true));

            // 不伪造 XML 正文，在已有详情中保留清空标记。
            assertEquals("", storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE).getData().getRule());
            assertNull(sdk.documents.get("governance.services.echo.rule.SERVICE_ROUTE"));
            Map<?, ?> detail = RegistryJson.read(sdk.documents.get("services.echo"), Map.class);
            assertEquals(List.of("SERVICE_ROUTE"), detail.get("ruleCleared"));
        }
    }

    @Test
    public void resettingEmptyRuntimeRuleRestoresInheritance() throws Exception {
        // 实例上的空正文覆盖已经生效。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.registerInstance(instance("one"));
            storage.saveRule("one", null, RuleKind.SERVICE_ROUTE, rule("", true));
            EntityRecord<EntRuleConfigurationData> value = storage.getRule("one", null, RuleKind.SERVICE_ROUTE);
            assertEquals("", value.getData().getRule());

            // reset 将正文设为 null，应移除清空标记且保留开关。
            value.getData().setRule(null);
            storage.saveRule("one", null, RuleKind.SERVICE_ROUTE, value);
            EntityRecord<EntRuleConfigurationData> result = storage.getRule("one", null, RuleKind.SERVICE_ROUTE);
            assertNull(result.getData().getRule());
            assertTrue(result.getData().isEnabled());
        }
    }

    @Test
    public void bodyConflictDoesNotChangeTheRuleSwitch() throws Exception {
        // 现有 XML 已禁用，随后在写入新正文时注入 CAS 冲突。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, rule("<route/>", false));
            EntityRecord<EntRuleConfigurationData> value = storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE);
            value.getData().setRule("<route><return value='addresses'/></route>");
            value.getData().setEnabled(true);
            sdk.conflicts = 1;
            sdk.conflictPrefix = "governance.services.echo.rule.";

            // 正文失败时不继续更新详情内的开关。
            assertThrows(ConcurrentModificationException.class, //
                    () -> storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, value));
            assertEquals("<route/>", sdk.documents.get("governance.services.echo.rule.SERVICE_ROUTE"));
            assertFalse(storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE).getData().isEnabled());
        }
    }

    @Test
    public void detailConflictRetriesWithoutDiscardingServiceDeclaration() throws Exception {
        // 管理写与服务声明共用一个 Config，允许短暂 CAS 竞争。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            EntServiceRuntimeData declaration = service("echo");
            declaration.setName("EchoService");
            storage.saveService(declaration);
            sdk.conflicts = 1;
            sdk.conflictPrefix = "services.echo";
            storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, rule("<route/>", false));

            // 合并重试后两类字段都保留。
            assertEquals("EchoService", storage.getService("echo").getName());
            assertFalse(storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE).getData().isEnabled());
        }
    }

    @Test
    public void withdrawingMembershipRemovesItsRuleAndSwitch() throws Exception {
        // 单个关系携带规则正文和开关，不创建持久交叉治理。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess storage = sdk.configuration().openStorage()) {
            storage.registerInstance(instance("one"));
            storage.registerService("one", service("echo"));
            storage.saveRule("one", "echo", RuleKind.SERVICE_ROUTE, rule("<route/>", false));
            storage.unregisterService("one", "echo");

            // 正文及开关随关系退出，服务定义仍存在。
            assertNull(storage.getRule("one", "echo", RuleKind.SERVICE_ROUTE));
            assertNotNull(storage.getService("echo"));
            String runtimeGovernancePrefix = "governance.services.echo.instances.";
            assertFalse(sdk.documents.keySet().stream().anyMatch(key -> key.startsWith(runtimeGovernancePrefix)));
        }
    }

    private static EntityRecord<EntRuleConfigurationData> rule(String body, boolean enabled) {
        EntityRecord<EntRuleConfigurationData> result = new EntityRecord<>();
        EntRuleConfigurationData data = new EntRuleConfigurationData();
        data.setRule(body);
        data.setEnabled(enabled);
        result.setData(data);
        return result;
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.zookeeper;
import java.nio.charset.StandardCharsets;
import java.util.ConcurrentModificationException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import net.hasor.meteor.registry.shared.utils.RegistryJson;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingServer;
import org.junit.Test;
import static net.hasor.meteor.registry.zookeeper.ZookeeperRecords.instance;
import static net.hasor.meteor.registry.zookeeper.ZookeeperRecords.service;
import static org.junit.Assert.*;

/** 原始 XML、详情开关与应用会话生命周期的存储契约。 */
public class ZookeeperRuleStorageTest {
    @Test
    public void ruleNodeContainsOnlyOriginalXml() throws Exception {
        // 创建独立 ZooKeeper 服务，通过正式配置打开存储会话。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess storage = configuration.openStorage()) {
                String xml = "<route><return value='addresses'/></route>";
                storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, rule(xml, false));

                // 直接读取物理节点：XML 无包装，开关只出现在服务详情中。
                ExponentialBackoffRetry retry = new ExponentialBackoffRetry(50, 3);
                String address = server.getConnectString();
                try (CuratorFramework client = CuratorFrameworkFactory.newClient(address, retry)) {
                    client.start();
                    assertTrue(client.blockUntilConnected(5, TimeUnit.SECONDS));
                    String root = "/meteor/services/ZWNobw";
                    byte[] bytes = client.getData().forPath(root + "/governance/SERVICE_ROUTE");
                    assertEquals(xml, new String(bytes, StandardCharsets.UTF_8));
                    String json = new String(client.getData().forPath(root + "/info"), StandardCharsets.UTF_8);
                    Map<?, ?> detail = RegistryJson.read(json, Map.class);
                    assertEquals(Map.of("SERVICE_ROUTE", false), detail.get("ruleEnabled"));
                    assertNull(client.checkExists().forPath(root + "/governance/SERVICE_ROUTE.enabled"));
                    Set<String> directories = Set.copyOf(client.getChildren().forPath("/meteor"));
                    assertEquals(Set.of("applications", "instances", "services"), directories);
                }
            }
        }
    }

    @Test
    public void serviceDeclarationUpdatePreservesRuleControl() throws Exception {
        // 创建独立 ZooKeeper 服务，通过正式配置打开存储会话。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess storage = configuration.openStorage()) {
                storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, rule("<route/>", false));

                // 应用更新声明后，治理开关及 XML 仍保持原值。
                EntServiceCatalogData declaration = new EntServiceCatalogData();
                declaration.setServiceId("echo");
                declaration.setName("EchoService");
                storage.saveService(declaration);
                assertEquals("EchoService", storage.getService("echo").getName());
                assertFalse(storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE).getData().isEnabled());
            }
        }
    }

    @Test
    public void instanceRegistrationUpdatePreservesRuleControl() throws Exception {
        // 创建独立 ZooKeeper 服务，通过正式配置打开存储会话。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess storage = configuration.openStorage()) {
                EntInstanceRuntimeData instance = instance("one", "orders", "east");
                storage.registerInstance(instance);
                storage.saveRule("one", null, RuleKind.SERVICE_ROUTE, rule("<route/>", false));

                // 实例身份变化只更新登记字段，不抹掉管理端规则。
                instance.setUnitName("west");
                storage.registerInstance(instance);
                assertEquals("west", storage.getInstance("one").getRuntime().getUnitName());
                assertFalse(storage.getRule("one", null, RuleKind.SERVICE_ROUTE).getData().isEnabled());
            }
        }
    }

    @Test
    public void roleChangeKeepsInstanceServiceRules() throws Exception {
        // 创建独立 ZooKeeper 服务，通过正式配置打开存储会话。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess storage = configuration.openStorage()) {
                storage.registerInstance(instance("one", "orders", "east"));
                storage.registerService("one", service("echo", MetServiceType.Provider));
                storage.saveRule("one", "echo", RuleKind.SERVICE_ROUTE, rule("<route/>", false));

                // 角色切换更新反向引用；规则仍归稳定的实例服务详情。
                storage.registerService("one", service("echo", MetServiceType.Consumer));
                assertNull(storage.getMember("echo", MetServiceType.Provider, "one"));
                assertNotNull(storage.getMember("echo", MetServiceType.Consumer, "one"));
                assertFalse(storage.getRule("one", "echo", RuleKind.SERVICE_ROUTE).getData().isEnabled());
            }
        }
    }

    @Test
    public void managementSessionCloseDoesNotRemoveApplicationRules() throws Exception {
        // 创建独立 ZooKeeper 服务，通过正式配置打开存储会话。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess application = configuration.openStorage()) {
                application.registerInstance(instance("one", "orders", "east"));
                try (StorageAccess manager = configuration.openStorage()) {
                    manager.saveRule("one", null, RuleKind.SERVICE_ROUTE, rule("<route/>", false));
                }

                // 管理会话退出不拥有任何应用临时节点，规则保持可见。
                assertFalse(application.getRule("one", null, RuleKind.SERVICE_ROUTE).getData().isEnabled());
            }
        }
    }

    @Test
    public void applicationSessionCloseRemovesRuntimeRules() throws Exception {
        // 创建独立 ZooKeeper 服务，通过正式配置打开存储会话。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess manager = configuration.openStorage()) {
                try (StorageAccess application = configuration.openStorage()) {
                    application.registerInstance(instance("one", "orders", "east"));
                    application.registerService("one", service("echo", MetServiceType.Provider));
                    manager.saveRule("one", null, RuleKind.SERVICE_ROUTE, rule("<route/>", false));
                    manager.saveRule("one", "echo", RuleKind.SERVICE_ROUTE, rule("<route/>", false));
                }

                // 原生会话删除临时正文和控制；实例持久档案仍可用于历史查询。
                assertNull(manager.getRule("one", null, RuleKind.SERVICE_ROUTE));
                assertNull(manager.getRule("one", "echo", RuleKind.SERVICE_ROUTE));
                assertNotNull(manager.getInstance("one").getInstance());
            }
        }
    }

    @Test
    public void serviceWithdrawalDoesNotRestorePreviousOverride() throws Exception {
        // 创建独立 ZooKeeper 服务，通过正式配置打开存储会话。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess storage = configuration.openStorage()) {
                storage.registerInstance(instance("one", "orders", "east"));
                storage.registerService("one", service("echo", MetServiceType.Provider));
                storage.saveRule("one", "echo", RuleKind.SERVICE_ROUTE, rule("<route/>", false));

                // 撤销后重新登记同名服务，新的临时详情不能恢复旧覆盖。
                storage.unregisterService("one", "echo");
                storage.registerService("one", service("echo", MetServiceType.Provider));
                assertNull(storage.getRule("one", "echo", RuleKind.SERVICE_ROUTE));
            }
        }
    }

    @Test
    public void changingSwitchRejectsPreviousRuleVersion() throws Exception {
        // 创建独立 ZooKeeper 服务，通过正式配置打开存储会话。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess storage = configuration.openStorage()) {
                storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, rule("<route/>", true));
                EntityRecord<EntRuleConfigurationData> previous = storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE);
                EntityRecord<EntRuleConfigurationData> disabled = storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE);
                disabled.getData().setEnabled(false);
                storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, disabled);

                // 正文相同但开关已变，持有旧版本的写入必须失败。
                assertThrows(ConcurrentModificationException.class, () -> {
                    storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, previous);
                });
                assertFalse(storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE).getData().isEnabled());
            }
        }
    }

    @Test
    public void otherRuleUpdateDoesNotInvalidateSelectedRuleVersion() throws Exception {
        // 创建独立 ZooKeeper 服务，通过正式配置打开存储会话。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess storage = configuration.openStorage()) {
                storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, rule("<route/>", true));
                EntityRecord<EntRuleConfigurationData> selected = storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE);
                storage.saveRule(null, "echo", RuleKind.METHOD_ROUTE, rule("<route/>", false));

                // 不同规则共享详情，但修改方法路由不能使服务路由的版本失效。
                selected.getData().setEnabled(false);
                storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, selected);
                assertFalse(storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE).getData().isEnabled());
                assertFalse(storage.getRule(null, "echo", RuleKind.METHOD_ROUTE).getData().isEnabled());
            }
        }
    }

    @Test
    public void deleteAndRecreateRuleRejectsPreviousVersion() throws Exception {
        // 创建独立 ZooKeeper 服务，通过正式配置打开存储会话。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess storage = configuration.openStorage()) {
                storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, rule("<route/>", true));
                EntityRecord<EntRuleConfigurationData> previous = storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE);
                storage.deleteRule(null, "echo", RuleKind.SERVICE_ROUTE, previous.getRevision());
                storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, rule("<route/>", true));

                // 删除重建即使正文一致，原版本也不能再次覆盖。
                assertThrows(ConcurrentModificationException.class, () -> {
                    storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, previous);
                });
            }
        }
    }

    @Test
    public void disabledWithoutBodyRemainsAnExplicitOverride() throws Exception {
        // 创建独立 ZooKeeper 服务，通过正式配置打开存储会话。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess storage = configuration.openStorage()) {
                storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, rule(null, false));

                // 没有本层 XML 仍可显式禁用继承规则，不能误判为没有配置。
                EntityRecord<EntRuleConfigurationData> result = storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE);
                assertNotNull(result);
                assertNull(result.getData().getRule());
                assertFalse(result.getData().isEnabled());
            }
        }
    }

    @Test
    public void emptyBodyIsDistinctFromInheritedBody() throws Exception {
        // 创建独立 ZooKeeper 服务，通过正式配置打开存储会话。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess storage = configuration.openStorage()) {
                storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, rule("", true));

                // 空正文显式清空上层规则，与 null 恢复继承不同。
                EntityRecord<EntRuleConfigurationData> cleared = storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE);
                assertEquals("", cleared.getData().getRule());
                cleared.getData().setRule(null);
                storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, cleared);
                assertNull(storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE).getData().getRule());
            }
        }
    }

    @Test
    public void deletingGlobalStatePreservesRuleSwitches() throws Exception {
        // 创建独立 ZooKeeper 服务，通过正式配置打开存储会话。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess storage = configuration.openStorage()) {
                storage.saveRule(null, null, RuleKind.SERVICE_ROUTE, rule("<route/>", false));
                assertNull(storage.getConfiguration(null, null));
                EntityRecord<EntConfigurationData> state = new EntityRecord<>();
                EntConfigurationData data = new EntConfigurationData();
                data.setEnabled(false);
                state.setData(data);
                storage.saveConfiguration(null, null, state);

                // 状态和规则共用全局详情，删除状态不能连带删除规则控制。
                String revision = storage.getConfiguration(null, null).getRevision();
                storage.deleteConfiguration(null, null, revision);
                assertNull(storage.getConfiguration(null, null));
                assertFalse(storage.getRule(null, null, RuleKind.SERVICE_ROUTE).getData().isEnabled());
            }
        }
    }

    @Test
    public void missingRuntimeRejectsRuleCreation() throws Exception {
        // 创建独立 ZooKeeper 服务，通过正式配置打开存储会话。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess storage = configuration.openStorage()) {
                // 管理端不代替离线应用创建临时节点，也不留下持久覆盖。
                assertThrows(IllegalStateException.class, () -> {
                    storage.saveRule("offline", "echo", RuleKind.SERVICE_ROUTE, rule("<route/>", false));
                });
                assertNull(storage.getRule("offline", "echo", RuleKind.SERVICE_ROUTE));
            }
        }
    }

    @Test
    public void concurrentDifferentRulesMergeDetailControls() throws Exception {
        // 创建独立 ZooKeeper 服务，通过正式配置打开存储会话。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess storage = configuration.openStorage()) {
                ExecutorService executor = Executors.newFixedThreadPool(2);
                CountDownLatch start = new CountDownLatch(1);
                try {
                    // 两个独立规则竞争同一个服务详情，multi 冲突后重读合并。
                    Future<?> serviceRule = executor.submit(() -> {
                        start.await();
                        storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, rule("<route/>", false));
                        return null;
                    });
                    Future<?> methodRule = executor.submit(() -> {
                        start.await();
                        storage.saveRule(null, "echo", RuleKind.METHOD_ROUTE, rule("<route/>", false));
                        return null;
                    });
                    start.countDown();
                    serviceRule.get(10, TimeUnit.SECONDS);
                    methodRule.get(10, TimeUnit.SECONDS);

                    // 任一提交都不能覆盖另一个规则的开关。
                    assertFalse(storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE).getData().isEnabled());
                    assertFalse(storage.getRule(null, "echo", RuleKind.METHOD_ROUTE).getData().isEnabled());
                } finally {
                    executor.shutdownNow();
                }
            }
        }
    }

    private static EntityRecord<EntRuleConfigurationData> rule(String xml, boolean enabled) {
        EntRuleConfigurationData data = new EntRuleConfigurationData();
        data.setRule(xml);
        data.setEnabled(enabled);
        EntityRecord<EntRuleConfigurationData> result = new EntityRecord<>();
        result.setData(data);
        return result;
    }
}

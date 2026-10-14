/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.jdbc.unit;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import net.hasor.dbvisitor.jdbc.core.JdbcTemplate;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.jdbc.JdbcRegistryConfiguration;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.EntConfigurationData;
import net.hasor.meteor.registry.shared.storage.domain.EntRuleConfigurationData;
import net.hasor.meteor.registry.shared.storage.domain.EntityRecord;
import net.hasor.meteor.registry.shared.storage.domain.RuleKind;
import net.hasor.meteor.registry.shared.utils.RegistryJson;
import org.junit.Test;
import static net.hasor.meteor.registry.jdbc.scenario.example.JdbcTestSupport.configuration;
import static net.hasor.meteor.registry.jdbc.scenario.example.JdbcTestSupport.createDatabase;
import static net.hasor.meteor.registry.jdbc.unit.JdbcStorageData.instance;
import static net.hasor.meteor.registry.jdbc.unit.JdbcStorageData.service;
import static org.junit.Assert.*;

/** H2 实测 JDBC 的规则正文、业务详情和事务，不代表其他数据库已经验收。 */
public class JdbcRuleStorageTest {
    @Test
    public void ruleBodyContainsOnlyOriginalXml() throws Exception {
        // 准备包含声明与换行的规则，检查实际数据库正文。
        String url = createDatabase();
        JdbcRegistryConfiguration configuration = configuration(url);
        String xml = "<?xml version=\"1.0\"?>\n<route>\n</route>";
        try (StorageAccess storage = configuration.openStorage(); //
             Connection connection = DriverManager.getConnection(url, "", "")) {
            storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, rule(xml, false));

            // 正文不含 JSON 开关或版本包装，开关位于已有服务详情。
            JdbcTemplate jdbc = new JdbcTemplate(connection);
            String sql = "SELECT body FROM meteor_governance WHERE item_name='rule/SERVICE_ROUTE'";
            String body = jdbc.queryForList(sql, String.class).get(0);
            String controls = jdbc.queryForList("SELECT rule_controls FROM meteor_services", String.class).get(0);
            assertEquals(xml, body);
            assertEquals(Map.of("ruleEnabled", Map.of("SERVICE_ROUTE", false)), RegistryJson.read(controls, Map.class));
        }
    }

    @Test
    public void whitespaceBodyIsNotChangedIntoInheritedOrClearedRule() throws Exception {
        // 存储层原样保留正文；校验 XML 由管理 API 负责。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess storage = configuration.openStorage()) {
            String text = " \t\n ";
            storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, rule(text, true));
            assertEquals(text, storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE).getData().getRule());
        }
    }

    @Test
    public void switchOnlyRuleDoesNotCreateXmlWrapper() throws Exception {
        // 禁用未写入正文的规则，仍需保留显式覆盖和版本。
        String url = createDatabase();
        JdbcRegistryConfiguration configuration = configuration(url);
        try (StorageAccess storage = configuration.openStorage(); //
             Connection connection = DriverManager.getConnection(url, "", "")) {
            storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, rule(null, false));

            // 原表的版本列负责并发控制，正文使用 SQL NULL。
            EntityRecord<EntRuleConfigurationData> saved = storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE);
            assertFalse(saved.getData().isEnabled());
            assertNull(saved.getData().getRule());
            assertNotNull(saved.getRevision());
            JdbcTemplate jdbc = new JdbcTemplate(connection);
            String sql = "SELECT body FROM meteor_governance WHERE item_name='rule/SERVICE_ROUTE'";
            List<String> bodies = jdbc.queryForList(sql, String.class);
            assertEquals(1, bodies.size());
            assertNull(bodies.get(0));
        }
    }

    @Test
    public void blankRuleRemainsAnExplicitClear() throws Exception {
        // SQL NULL 统一兼容 Oracle，详情标识负责区别显式清空。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess storage = configuration.openStorage()) {
            storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, rule("", true));
            EntRuleConfigurationData saved = storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE).getData();
            assertEquals("", saved.getRule());
            assertTrue(saved.isEnabled());
        }
    }

    @Test
    public void nullRuleRestoresInheritedBody() throws Exception {
        // 先显式清空，再把正文改为 null，清空标识应移除。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess storage = configuration.openStorage()) {
            storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, rule("", true));
            EntityRecord<EntRuleConfigurationData> update = storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE);
            update.getData().setRule(null);
            storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, update);
            assertNull(storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE).getData().getRule());
        }
    }

    @Test
    public void deletingRuleRemovesBodyAndDetailControl() throws Exception {
        // 同一服务包含两条规则，删除只针对其中一种。
        String url = createDatabase();
        JdbcRegistryConfiguration configuration = configuration(url);
        try (StorageAccess storage = configuration.openStorage(); //
             Connection connection = DriverManager.getConnection(url, "", "")) {
            storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, rule("<route/>", false));
            storage.saveRule(null, "echo", RuleKind.METHOD_ROUTE, rule("<method/>", true));
            String revision = storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE).getRevision();

            // 删除正文和控制后，另一条规则及服务档案保持原样。
            assertTrue(storage.deleteRule(null, "echo", RuleKind.SERVICE_ROUTE, revision));
            assertNull(storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE));
            assertEquals("<method/>", storage.getRule(null, "echo", RuleKind.METHOD_ROUTE).getData().getRule());
            JdbcTemplate jdbc = new JdbcTemplate(connection);
            String controls = jdbc.queryForList("SELECT rule_controls FROM meteor_services", String.class).get(0);
            assertEquals(Map.of("ruleEnabled", Map.of("METHOD_ROUTE", true)), RegistryJson.read(controls, Map.class));
        }
    }

    @Test
    public void globalRulesDoNotCreateAConfigurationOverride() throws Exception {
        // 全局状态详情可只承载开关，不伪造状态配置对象。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess storage = configuration.openStorage()) {
            storage.saveRule(null, null, RuleKind.SERVICE_ROUTE, rule("<route/>", false));
            assertNull(storage.getConfiguration(null, null));
            assertFalse(storage.getRule(null, null, RuleKind.SERVICE_ROUTE).getData().isEnabled());
        }
    }

    @Test
    public void deletingGlobalStateKeepsRuleControls() throws Exception {
        // 状态和规则开关共享详情，但配置删除不能删除规则。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess storage = configuration.openStorage()) {
            storage.saveRule(null, null, RuleKind.SERVICE_ROUTE, rule("<route/>", false));
            EntityRecord<EntConfigurationData> state = new EntityRecord<>();
            state.setData(new EntConfigurationData());
            storage.saveConfiguration(null, null, state);
            String revision = storage.getConfiguration(null, null).getRevision();

            // 配置正文删除后仍能独立读取规则及禁用状态。
            assertTrue(storage.deleteConfiguration(null, null, revision));
            assertNull(storage.getConfiguration(null, null));
            EntRuleConfigurationData rule = storage.getRule(null, null, RuleKind.SERVICE_ROUTE).getData();
            assertEquals("<route/>", rule.getRule());
            assertFalse(rule.isEnabled());
        }
    }

    @Test
    public void staleGlobalStateCannotRestoreDeletedConfiguration() throws Exception {
        // 规则和状态共用详情，删除状态时仍需失效旧的状态版本。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess storage = configuration.openStorage()) {
            storage.saveRule(null, null, RuleKind.SERVICE_ROUTE, rule("<route/>", false));
            EntityRecord<EntConfigurationData> initial = new EntityRecord<>();
            initial.setData(new EntConfigurationData());
            storage.saveConfiguration(null, null, initial);
            EntityRecord<EntConfigurationData> stale = storage.getConfiguration(null, null);
            storage.deleteConfiguration(null, null, stale.getRevision());

            // 旧写入不能复活已删配置，独立规则仍保留。
            assertThrows(ConcurrentModificationException.class, () -> storage.saveConfiguration(null, null, stale));
            assertNull(storage.getConfiguration(null, null));
            assertFalse(storage.getRule(null, null, RuleKind.SERVICE_ROUTE).getData().isEnabled());
        }
    }

    @Test
    public void instanceRegistrationRefreshKeepsManagedRuleSwitches() throws Exception {
        // 应用登记和管理员规则更新来自两个不同会话。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess application = configuration.openStorage(); //
             StorageAccess manager = configuration.openStorage()) {
            application.registerInstance(instance());
            manager.saveRule("one", null, RuleKind.SERVICE_ROUTE, rule("<route/>", false));

            // 同一应用刷新运行详情不能覆盖管理员控制。
            application.registerInstance(instance());
            EntRuleConfigurationData rule = manager.getRule("one", null, RuleKind.SERVICE_ROUTE).getData();
            assertEquals("<route/>", rule.getRule());
            assertFalse(rule.isEnabled());
        }
    }

    @Test
    public void serviceRegistrationRefreshKeepsManagedRuleSwitches() throws Exception {
        // 服务档案正文刷新与管理控制更新互相独立。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess application = configuration.openStorage(); //
             StorageAccess manager = configuration.openStorage()) {
            application.registerInstance(instance());
            application.registerService("one", service());
            manager.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, rule("<route/>", false));

            // 重新登记服务不会把服务范围的开关恢复为默认值。
            application.registerService("one", service());
            assertFalse(manager.getRule(null, "echo", RuleKind.SERVICE_ROUTE).getData().isEnabled());
        }
    }

    @Test
    public void changingMembershipRoleKeepsInstanceServiceControl() throws Exception {
        // 交叉范围控制放在实例服务主记录，不复制到角色索引。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess application = configuration.openStorage(); //
             StorageAccess manager = configuration.openStorage()) {
            application.registerInstance(instance());
            application.registerService("one", service());
            manager.saveRule("one", "echo", RuleKind.SERVICE_ROUTE, rule("<route/>", false));

            // 提供者变为消费者仍属于同一实例服务范围。
            application.registerService("one", service("echo", MetServiceType.Consumer));
            assertFalse(manager.getRule("one", "echo", RuleKind.SERVICE_ROUTE).getData().isEnabled());
            assertNull(manager.getMember("echo", MetServiceType.Provider, "one"));
            assertNotNull(manager.getMember("echo", MetServiceType.Consumer, "one"));
        }
    }

    @Test
    public void withdrawingMembershipRemovesItsTemporaryRules() throws Exception {
        // 临时交叉治理不影响同一服务的持久治理。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess application = configuration.openStorage(); //
             StorageAccess manager = configuration.openStorage()) {
            application.registerInstance(instance());
            application.registerService("one", service());
            manager.saveRule("one", "echo", RuleKind.SERVICE_ROUTE, rule("<temporary/>", false));
            manager.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, rule("<persistent/>", true));

            // 撤销再登记不复活旧实例服务规则。
            application.unregisterService("one", "echo");
            application.registerService("one", service());
            assertNull(manager.getRule("one", "echo", RuleKind.SERVICE_ROUTE));
            assertEquals("<persistent/>", manager.getRule(null, "echo", RuleKind.SERVICE_ROUTE).getData().getRule());
        }
    }

    @Test
    public void managerCloseDoesNotOwnApplicationRules() throws Exception {
        // 管理会话不能把目标规则的临时生命周期转为自己的会话。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess application = configuration.openStorage()) {
            application.registerInstance(instance());
            try (StorageAccess manager = configuration.openStorage()) {
                manager.saveRule("one", null, RuleKind.SERVICE_ROUTE, rule("<route/>", false));
            }

            // 管理员退出，应用登记及其临时规则继续存在。
            assertNotNull(application.getInstance("one").getRuntime());
            assertFalse(application.getRule("one", null, RuleKind.SERVICE_ROUTE).getData().isEnabled());
        }
    }

    @Test
    public void applicationCloseDeletesTemporaryRuleRows() throws Exception {
        // 管理端保留连接，用于确认应用退出后的物理数据。
        String url = createDatabase();
        JdbcRegistryConfiguration configuration = configuration(url);
        try (StorageAccess manager = configuration.openStorage(); //
             Connection connection = DriverManager.getConnection(url, "", "")) {
            JdbcTemplate jdbc = new JdbcTemplate(connection);
            String owner;
            try (StorageAccess application = configuration.openStorage()) {
                application.registerInstance(instance());
                manager.saveRule("one", null, RuleKind.SERVICE_ROUTE, rule("<route/>", false));
                String activeSql = "SELECT session_id FROM meteor_instances WHERE rule_controls IS NOT NULL";
                List<String> owners = jdbc.queryForList(activeSql, String.class);
                assertEquals(1, owners.size());
                owner = owners.get(0);
            }

            // 应用关闭清除临时规则正文及控制字段，档案行与最近归属继续保留。
            assertNull(manager.getRule("one", null, RuleKind.SERVICE_ROUTE));
            String sql = "SELECT body FROM meteor_governance WHERE session_id IS NOT NULL";
            List<String> rows = jdbc.queryForList(sql, String.class);
            assertTrue(rows.isEmpty());
            String offlineSql = "SELECT session_id FROM meteor_instances WHERE body IS NOT NULL" //
                    + " AND runtime_body IS NULL AND runtime_revision IS NULL" //
                    + " AND rule_controls IS NULL AND expires_at IS NULL";
            assertEquals(List.of(owner), jdbc.queryForList(offlineSql, String.class));
        }
    }

    @Test
    public void replacementInstanceDoesNotInheritOldProcessRules() throws Exception {
        // 新注册会话接管同一实例 ID，旧实例规则属于旧生命周期。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess old = configuration.openStorage(); //
             StorageAccess manager = configuration.openStorage(); //
             StorageAccess replacement = configuration.openStorage()) {
            old.registerInstance(instance());
            manager.saveRule("one", null, RuleKind.SERVICE_ROUTE, rule("<old/>", false));
            replacement.registerInstance(instance());

            // 旧控制不遗留，新规则不会被旧会话的关闭清除。
            assertNull(manager.getRule("one", null, RuleKind.SERVICE_ROUTE));
            manager.saveRule("one", null, RuleKind.SERVICE_ROUTE, rule("<new/>", true));
            old.close();
            assertEquals("<new/>", manager.getRule("one", null, RuleKind.SERVICE_ROUTE).getData().getRule());
        }
    }

    @Test
    public void expiredApplicationLeaseHidesTemporaryRules() throws Exception {
        // 模拟失联仅过期应用实例租约，管理端仍可操作。
        String url = createDatabase();
        JdbcRegistryConfiguration configuration = configuration(url);
        try (StorageAccess application = configuration.openStorage(); //
             StorageAccess manager = configuration.openStorage(); //
             Connection connection = DriverManager.getConnection(url, "", "")) {
            application.registerInstance(instance());
            manager.saveRule("one", null, RuleKind.SERVICE_ROUTE, rule("<route/>", false));
            String sql = "UPDATE meteor_instances SET expires_at=0 WHERE runtime_body IS NOT NULL";
            new JdbcTemplate(connection).executeUpdate(sql);

            // 租约过期即停止读取临时治理，不等待物理清理。
            assertNull(manager.getRule("one", null, RuleKind.SERVICE_ROUTE));
            assertThrows(IllegalStateException.class, //
                    () -> manager.saveRule("one", null, RuleKind.SERVICE_ROUTE, rule(null, false)));
        }
    }

    @Test
    public void expiringOneInstanceDoesNotHideOtherInstanceRulesInTheSameSession() throws Exception {
        // 同一存储会话登记两个实例，各自持有实例和实例服务规则。
        String url = createDatabase();
        JdbcRegistryConfiguration configuration = configuration(url);
        try (StorageAccess application = configuration.openStorage(); //
             StorageAccess manager = configuration.openStorage(); //
             Connection connection = DriverManager.getConnection(url, "", "")) {
            application.registerInstance(instance("one", "orders", "east"));
            application.registerService("one", service());
            application.registerInstance(instance("two", "orders", "west"));
            application.registerService("two", service());
            manager.saveRule("one", null, RuleKind.SERVICE_ROUTE, rule("<first/>", false));
            manager.saveRule("one", "echo", RuleKind.SERVICE_ROUTE, rule("<first-service/>", false));
            manager.saveRule("two", null, RuleKind.SERVICE_ROUTE, rule("<second/>", false));
            manager.saveRule("two", "echo", RuleKind.SERVICE_ROUTE, rule("<second-service/>", false));

            // 只让第一个实例失联，同会话的第二个实例租约保持有效。
            String sql = "UPDATE meteor_instances SET expires_at=0 WHERE unit_name='east'";
            new JdbcTemplate(connection).executeUpdate(sql);
            assertNull(manager.getRule("one", null, RuleKind.SERVICE_ROUTE));
            assertNull(manager.getRule("one", "echo", RuleKind.SERVICE_ROUTE));
            assertThrows(IllegalStateException.class, //
                    () -> manager.saveRule("one", "echo", RuleKind.SERVICE_ROUTE, rule(null, true)));

            // 第二个实例的规则仍可读取和更新，不受第一个实例的租约影响。
            EntRuleConfigurationData instanceRule = manager.getRule("two", null, RuleKind.SERVICE_ROUTE).getData();
            assertEquals("<second/>", instanceRule.getRule());
            EntityRecord<EntRuleConfigurationData> memberRule = manager.getRule("two", "echo", RuleKind.SERVICE_ROUTE);
            assertEquals("<second-service/>", memberRule.getData().getRule());
            memberRule.getData().setEnabled(true);
            manager.saveRule("two", "echo", RuleKind.SERVICE_ROUTE, memberRule);
            assertTrue(manager.getRule("two", "echo", RuleKind.SERVICE_ROUTE).getData().isEnabled());
        }
    }

    @Test
    public void offlineTargetCannotAcquireTemporaryRules() throws Exception {
        // 管理端没有权限代替应用创建运行登记。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess storage = configuration.openStorage()) {
            assertThrows(IllegalStateException.class, //
                    () -> storage.saveRule("offline", null, RuleKind.SERVICE_ROUTE, rule(null, false)));
            assertNull(storage.getInstance("offline"));
        }
    }

    @Test
    public void staleVersionCannotOverwriteNewBodyOrSwitch() throws Exception {
        // 两个读取结果拥有相同初始版本。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess storage = configuration.openStorage()) {
            storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, rule("<old/>", true));
            EntityRecord<EntRuleConfigurationData> current = storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE);
            EntityRecord<EntRuleConfigurationData> stale = storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE);
            current.getData().setEnabled(false);
            storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, current);

            // 过期正文不能覆盖已提交的禁用状态。
            stale.getData().setRule("<stale/>");
            assertThrows(ConcurrentModificationException.class, //
                    () -> storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, stale));
            EntRuleConfigurationData saved = storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE).getData();
            assertEquals("<old/>", saved.getRule());
            assertFalse(saved.isEnabled());
        }
    }

    @Test
    public void concurrentDifferentRulesMergeSharedDetailControls() throws Exception {
        // 独立 JDBC 会话同时写入同一服务上的不同规则。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (StorageAccess first = configuration.openStorage(); //
             StorageAccess second = configuration.openStorage()) {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<?> route = workers.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                first.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, rule("<route/>", false));
                return null;
            });
            Future<?> method = workers.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                second.saveRule(null, "echo", RuleKind.METHOD_ROUTE, rule("<method/>", false));
                return null;
            });

            // 范围详情锁合并两份开关，各自正文都完整提交。
            route.get(10, TimeUnit.SECONDS);
            method.get(10, TimeUnit.SECONDS);
            assertFalse(first.getRule(null, "echo", RuleKind.SERVICE_ROUTE).getData().isEnabled());
            assertFalse(first.getRule(null, "echo", RuleKind.METHOD_ROUTE).getData().isEnabled());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    public void detailWriteFailureRollsBackXmlAndSwitchTogether() throws Exception {
        // 在真实 JDBC 事务的最后一步注入权限错误。
        try (JdbcDriverProbe probe = new JdbcDriverProbe(createDatabase())) {
            JdbcRegistryConfiguration configuration = configuration(probe.url());
            try (StorageAccess storage = configuration.openStorage()) {
                storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, rule("<old/>", true));
                EntityRecord<EntRuleConfigurationData> changed = storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE);
                String originalRevision = changed.getRevision();
                changed.getData().setRule("<new/>");
                changed.getData().setEnabled(false);
                probe.beforeExecute(sql -> {
                    if (sql.startsWith("UPDATE meteor_services SET rule_controls=")) {
                        throw new SQLException("detail write denied", "42000");
                    }
                });

                // XML 已执行写入，但最终失败必须撤销正文、版本和开关。
                assertThrows(SQLException.class, //
                        () -> storage.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, changed));
                probe.beforeExecute(sql -> {
                });
                EntityRecord<EntRuleConfigurationData> saved = storage.getRule(null, "echo", RuleKind.SERVICE_ROUTE);
                assertEquals(originalRevision, saved.getRevision());
                assertEquals("<old/>", saved.getData().getRule());
                assertTrue(saved.getData().isEnabled());
            }
        }
    }

    @Test
    public void changingOnlyRuleSwitchNotifiesConfigurationSubscriber() throws Exception {
        // 初始正文保持相同，后续只修改启用开关。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess writer = configuration.openStorage(); //
             StorageAccess reader = configuration.openStorage()) {
            writer.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, rule("<route/>", true));
            AtomicInteger notifications = new AtomicInteger();
            reader.watchConfiguration(null, "echo", notifications::incrementAndGet);
            await(() -> notifications.get() == 1);

            // 开关变化仍是治理变化，必须触发订阅通知。
            EntityRecord<EntRuleConfigurationData> update = writer.getRule(null, "echo", RuleKind.SERVICE_ROUTE);
            update.getData().setEnabled(false);
            writer.saveRule(null, "echo", RuleKind.SERVICE_ROUTE, update);
            await(() -> notifications.get() == 2);
        }
    }

    @Test
    public void registrationRefreshDoesNotLoopConfigurationNotifications() throws Exception {
        // 订阅交叉范围规则，然后执行相同登记信息重报。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess application = configuration.openStorage(); //
             StorageAccess manager = configuration.openStorage()) {
            application.registerInstance(instance());
            application.registerService("one", service());
            manager.saveRule("one", "echo", RuleKind.SERVICE_ROUTE, rule("<route/>", false));
            AtomicInteger notifications = new AtomicInteger();
            manager.watchConfiguration("one", "echo", notifications::incrementAndGet);
            await(() -> notifications.get() == 1);

            // 登记正文没有变化，治理版本不能随登记刷新循环增长。
            application.registerInstance(instance());
            application.registerService("one", service());
            Thread.sleep(450);
            assertEquals(1, notifications.get());
            assertFalse(manager.getRule("one", "echo", RuleKind.SERVICE_ROUTE).getData().isEnabled());
        }
    }

    @Test
    public void applicationDepartureNotifiesRuleSubscriber() throws Exception {
        // 订阅已写入临时规则的应用实例。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess application = configuration.openStorage(); //
             StorageAccess manager = configuration.openStorage()) {
            application.registerInstance(instance());
            manager.saveRule("one", null, RuleKind.SERVICE_ROUTE, rule("<route/>", false));
            AtomicInteger notifications = new AtomicInteger();
            manager.watchConfiguration("one", null, notifications::incrementAndGet);
            await(() -> notifications.get() == 1);

            // 应用退出清除范围详情，订阅也应观察到规则消失。
            application.close();
            await(() -> notifications.get() == 2);
            assertNull(manager.getRule("one", null, RuleKind.SERVICE_ROUTE));
        }
    }

    private static EntityRecord<EntRuleConfigurationData> rule(String xml, boolean enabled) {
        EntRuleConfigurationData data = new EntRuleConfigurationData();
        data.setRule(xml);
        data.setEnabled(enabled);
        EntityRecord<EntRuleConfigurationData> record = new EntityRecord<>();
        record.setData(data);
        return record;
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue("Configuration notification did not arrive", condition.getAsBoolean());
    }
}

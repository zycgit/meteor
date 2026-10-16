/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.registry.jdbc;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import net.hasor.cobble.StringUtils;
import net.hasor.dbvisitor.jdbc.core.JdbcTemplate;
import net.hasor.example.registry.jdbc.Echo;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.admin.MetRuleType;
import net.hasor.meteor.registry.admin.MetServiceAdmin;
import net.hasor.meteor.registry.jdbc.JdbcRegistryConfiguration;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Test;
import static net.hasor.example.registry.jdbc.JdbcTestSupport.createDatabase;
import static org.junit.Assert.*;

public class JdbcGovernanceTest {
    @Test
    public void concurrentManagementMetadataMergesWithoutLosingKeys() throws Exception {
        // 两个管理应用连接同一 DataSource，各自通过扩展取得管理能力。
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(createDatabase());
        JdbcRegistryConfiguration registry = new JdbcRegistryConfiguration();
        registry.setDataSource(dataSource);
        registry.setManagementToken("secret");
        MetConfiguration configuration = new MetConfiguration().addExtension(registry);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (MetContext first = configuration.buildContext(); //
             MetContext second = configuration.buildContext()) {
            RegistryAdmin firstAdmin = first.getExtension(RegistryAdmin.class);
            RegistryAdmin secondAdmin = second.getExtension(RegistryAdmin.class);
            MetServiceAdmin firstService = firstAdmin.getServiceAdmin("echo");
            MetServiceAdmin secondService = secondAdmin.getServiceAdmin("echo");

            // 同时更新不同键，管理端负责 CAS 冲突后的合并。
            CyclicBarrier start = new CyclicBarrier(2);
            Future<?> one = workers.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                firstService.putMeta("first", 1);
                return null;
            });
            Future<?> two = workers.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                secondService.putMeta("second", 2);
                return null;
            });
            one.get(10, TimeUnit.SECONDS);
            two.get(10, TimeUnit.SECONDS);

            // 两个成功操作的键必须同时保留。
            assertEquals(Map.of("first", 1, "second", 2), firstService.fetchMeta());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    public void missingGovernanceTableRemainsVisibleToAdminCaller() throws Exception {
        // 从正式扩展取得管理接口，另开 H2 连接控制表结构故障。
        String url = createDatabase();
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(url);
        JdbcRegistryConfiguration registry = new JdbcRegistryConfiguration();
        registry.setDataSource(dataSource);
        registry.setManagementToken("secret");
        MetConfiguration configuration = new MetConfiguration().addExtension(registry);
        try (MetContext context = configuration.buildContext(); //
             Connection connection = DriverManager.getConnection(url, "", "")) {
            RegistryAdmin manager = context.getExtension(RegistryAdmin.class);
            MetServiceAdmin service = manager.getServiceAdmin("echo");
            service.fetchMeta();
            JdbcTemplate jdbc = new JdbcTemplate(connection);
            jdbc.executeUpdate("ALTER TABLE meteor_governance RENAME TO hidden_governance");

            // 管理调用保留原始 SQL 原因，不能被当作空数据或重试成功。
            try {
                IllegalStateException failure = assertThrows(IllegalStateException.class, () -> {
                    service.putMeta("key", "value");
                });
                assertTrue(failure.getCause() instanceof SQLException);
            } finally {
                jdbc.executeUpdate("ALTER TABLE hidden_governance RENAME TO meteor_governance");
            }

            // 修复数据库后，同一个管理接口仍可完成治理操作。
            service.putMeta("key", "recovered");
            assertEquals("recovered", service.fetchMeta().get("key"));
        }
    }

    @Test
    public void instanceServiceSwitchAutomaticallyDisablesInheritedRoute() throws Exception {
        // 通过扩展连接 H2，拉长周期核对间隔，避免把轮询补偿当成订阅生效。
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(createDatabase());
        JdbcRegistryConfiguration registry = new JdbcRegistryConfiguration();
        registry.setDataSource(dataSource);
        registry.setRefreshMillis(60000);
        registry.setManagementToken("secret");
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        configuration.addExtension(registry);

        try (MetContext server = configuration.buildContext()) {
            // 启动容器后发布服务，通过正式管理 API 写入继承的全局路由。
            server.start();
            MetBindInfo<Echo> published = server.publisher() //
                    .rsfService(Echo.class, text -> "h2:" + text) //
                    .register();
            String serviceId = published.getBindID();
            RegistryAdmin manager = server.getExtension(RegistryAdmin.class);
            String xml = "<route><return value='addresses'/></route>";
            manager.getClusterAdmin().updateRule(MetRuleType.SERVICE_ROUTE, xml);
            await(() -> StringUtils.equals(xml, server.getUpdater().serviceRoute(serviceId)));
            MetServiceAdmin selected = manager.getServiceAdmin(serviceId, server.getInstanceID());

            // 禁用实例服务范围，只观察自动订阅，不主动 pull 或调用内部应用方法。
            selected.disableRule(MetRuleType.SERVICE_ROUTE);
            await(() -> StringUtils.isEmpty(server.getUpdater().serviceRoute(serviceId)));
            assertFalse(selected.isRule(MetRuleType.SERVICE_ROUTE));
            assertNull(selected.fetchRule(MetRuleType.SERVICE_ROUTE));
            assertEquals(xml, manager.getClusterAdmin().fetchRule(MetRuleType.SERVICE_ROUTE));
        }
    }

    @Test
    public void instanceServiceSwitchAutomaticallyReenablesInheritedRoute() throws Exception {
        // 通过扩展连接 H2，拉长周期核对间隔，避免把轮询补偿当成订阅生效。
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(createDatabase());
        JdbcRegistryConfiguration registry = new JdbcRegistryConfiguration();
        registry.setDataSource(dataSource);
        registry.setRefreshMillis(60000);
        registry.setManagementToken("secret");
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        configuration.addExtension(registry);

        try (MetContext server = configuration.buildContext()) {
            // 启动容器后发布服务，通过正式管理 API 写入继承的全局路由。
            server.start();
            MetBindInfo<Echo> published = server.publisher() //
                    .rsfService(Echo.class, text -> "h2:" + text) //
                    .register();
            String serviceId = published.getBindID();
            RegistryAdmin manager = server.getExtension(RegistryAdmin.class);
            String xml = "<route><return value='addresses'/></route>";
            manager.getClusterAdmin().updateRule(MetRuleType.SERVICE_ROUTE, xml);
            await(() -> StringUtils.equals(xml, server.getUpdater().serviceRoute(serviceId)));
            MetServiceAdmin selected = manager.getServiceAdmin(serviceId, server.getInstanceID());

            // 先通过存储订阅停用，作为重新启用场景的前置条件。
            selected.disableRule(MetRuleType.SERVICE_ROUTE);
            await(() -> StringUtils.isEmpty(server.getUpdater().serviceRoute(serviceId)));

            // 恢复开关时只写详情，原有全局 XML 应通过订阅重新生效。
            selected.enableRule(MetRuleType.SERVICE_ROUTE);
            await(() -> StringUtils.equals(xml, server.getUpdater().serviceRoute(serviceId)));
            assertTrue(selected.isRule(MetRuleType.SERVICE_ROUTE));
            assertNull(selected.fetchRule(MetRuleType.SERVICE_ROUTE));
            assertEquals(xml, manager.getClusterAdmin().fetchRule(MetRuleType.SERVICE_ROUTE));
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue("JDBC governance subscription did not apply the rule", condition.getAsBoolean());
    }

}

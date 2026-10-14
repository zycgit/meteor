/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.jdbc.unit;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.*;
import net.hasor.dbvisitor.jdbc.core.JdbcTemplate;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.jdbc.JdbcRegistryConfiguration;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import org.junit.Test;
import static net.hasor.meteor.registry.jdbc.scenario.example.JdbcTestSupport.configuration;
import static net.hasor.meteor.registry.jdbc.scenario.example.JdbcTestSupport.createDatabase;
import static net.hasor.meteor.registry.jdbc.unit.JdbcStorageData.*;
import static org.junit.Assert.*;

/** H2 验证真实建表、事务、租约与 SQL 异常；不表示其他数据库已经实测。 */
public class JdbcDatabaseIntegrationTest {

    @Test
    public void expiredLeaseHidesBothRegistrationDirectionsAndKeepsArchives() throws Exception {
        // 用数据库租约模拟进程失联，不调用发布会话 close。
        String url = createDatabase();
        JdbcRegistryConfiguration configuration = configuration(url);
        try (StorageAccess writer = configuration.openStorage(); //
             StorageAccess reader = configuration.openStorage(); //
             Connection connection = DriverManager.getConnection(url, "", "")) {
            writer.registerInstance(instance());
            writer.registerService("one", service());
            JdbcTemplate jdbc = new JdbcTemplate(connection);
            List<Long> expiry = jdbc.queryForList("SELECT expires_at FROM meteor_sessions", Long.class);
            assertEquals(2, expiry.size());
            assertTrue(expiry.get(0) > System.currentTimeMillis());

            // 租约过期后两侧临时登记都不可见，三个持久档案保留。
            jdbc.executeUpdate("UPDATE meteor_sessions SET expires_at=0");
            InstanceStatus offline = reader.getInstance("one");
            ServiceSummary summary = reader.serviceSummaries(List.of("echo")).get("echo");
            assertNull(offline.getRuntime());
            assertNotNull(offline.getInstance());
            assertNull(reader.getInstanceService("one", "echo"));
            assertNull(reader.getMember("echo", MetServiceType.Provider, "one"));
            assertEquals(0, summary.getProviderCount());
            assertNotNull(reader.getApplication("orders"));
            assertNotNull(reader.getService("echo"));
            assertThrows(IllegalStateException.class, () -> writer.registerService("one", service()));
        }
    }

    @Test
    public void expiredSessionCloseKeepsReplacementRegistration() throws Exception {
        // 发布者失联后，新会话恢复同一个实例及服务。
        String url = createDatabase();
        JdbcRegistryConfiguration configuration = configuration(url);
        try (StorageAccess writer = configuration.openStorage(); //
             StorageAccess reader = configuration.openStorage(); //
             Connection connection = DriverManager.getConnection(url, "", "")) {
            writer.registerInstance(instance());
            writer.registerService("one", service());
            JdbcTemplate jdbc = new JdbcTemplate(connection);
            String oldOwner = jdbc.queryForList("SELECT session_id FROM meteor_instances", String.class).get(0);
            jdbc.executeUpdate("UPDATE meteor_sessions SET expires_at=0");
            try (StorageAccess replacement = configuration.openStorage()) {
                replacement.registerInstance(instance());
                replacement.registerService("one", service());
                String currentOwner = jdbc.queryForList("SELECT session_id FROM meteor_instances", String.class).get(0);
                assertNotEquals(oldOwner, currentOwner);

                // 旧会话迟到的关闭不能清理同一档案行上替代会话的运行时或服务。
                writer.close();
                assertNotNull(reader.getInstance("one").getRuntime());
                assertNotNull(reader.getMember("echo", MetServiceType.Provider, "one"));
                String runtimeSql = "SELECT session_id FROM meteor_instances" //
                        + " WHERE runtime_body IS NOT NULL AND runtime_revision IS NOT NULL";
                assertEquals(List.of(currentOwner), jdbc.queryForList(runtimeSql, String.class));
            }
        }
    }

    @Test
    public void protocolRegistrationFailureRollsBackRelationAndServiceArchive() throws Exception {
        // 在真实 H2 写入协议索引前注入 SQL 权限错误。
        try (JdbcDriverProbe probe = new JdbcDriverProbe(createDatabase())) {
            JdbcRegistryConfiguration configuration = configuration(probe.url());
            try (StorageAccess writer = configuration.openStorage(); //
                 StorageAccess reader = configuration.openStorage(); //
                 Connection connection = DriverManager.getConnection(probe.url(), "", "")) {
                writer.registerInstance(instance());
                EntServiceRuntimeData published = service();
                published.setEndpoints(new RefProtocolMountData[] { rsfEndpoint() });
                SQLException expected = new SQLException("write denied", "42000");
                probe.beforeExecute(sql -> {
                    if (sql.startsWith("INSERT INTO meteor_member_protocols")) {
                        throw expected;
                    }
                });

                // 前半段已执行的关系与服务档案必须回滚，两个查询方向都不能留下服务。
                SQLException failure = assertThrows(SQLException.class, () -> writer.registerService("one", published));
                // dbVisitor 包装 SQL 异常，原始原因仍由存储调用方获取。
                assertSame(expected, failure.getCause());
                probe.beforeExecute(sql -> {
                });
                assertNull(reader.getInstanceService("one", "echo"));
                assertNull(reader.getMember("echo", MetServiceType.Provider, "one"));
                assertNull(reader.getService("echo"));
                assertNotNull(reader.getInstance("one").getRuntime());
                JdbcTemplate jdbc = new JdbcTemplate(connection);
                String relationSql = "SELECT service_id FROM meteor_instance_services";
                assertTrue(jdbc.queryForList(relationSql, String.class).isEmpty());
                assertTrue(jdbc.queryForList("SELECT service_id FROM meteor_member_protocols", String.class).isEmpty());
            }
        }
    }

    @Test
    public void fullRegistrationRecoversExpiredLeaseWithoutRevivingOldServices() throws Exception {
        // 准备独立会话与 H2 连接，以 SQL 明确控制租约过期时机。
        String url = createDatabase();
        JdbcRegistryConfiguration configuration = configuration(url);
        try (StorageAccess writer = configuration.openStorage(); //
             StorageAccess reader = configuration.openStorage(); //
             Connection connection = DriverManager.getConnection(url, "", "")) {
            // 同一会话包含两个实例，过期后仅重报 one。
            EntServiceRuntimeData published = service();
            published.setEndpoints(new RefProtocolMountData[] { rsfEndpoint() });
            writer.registerInstance(instance());
            writer.registerService("one", published);
            EntInstanceRuntimeData second = instance();
            second.setInstanceId("two");
            writer.registerInstance(second);
            writer.registerService("two", published);
            JdbcTemplate jdbc = new JdbcTemplate(connection);
            String owner = jdbc.queryForList("SELECT DISTINCT session_id FROM meteor_instances", String.class).get(0);
            String protocolSql = "SELECT protocol_name FROM meteor_member_protocols";
            List<String> protocols = jdbc.queryForList(protocolSql, String.class);
            assertEquals(List.of("rsf", "rsf"), protocols);
            jdbc.executeUpdate("UPDATE meteor_sessions SET expires_at=0");

            // 续租必须先丢弃旧临时数据；未重报的实例和旧服务保持离线。
            writer.registerInstance(instance());
            assertNotNull(reader.getInstance("one").getRuntime());
            assertNull(reader.getInstance("two").getRuntime());
            assertNull(reader.getInstanceService("one", "echo"));
            assertNull(reader.getMember("echo", MetServiceType.Provider, "two"));
            assertNotNull(reader.getInstance("two").getInstance());
            String offlineSql = "SELECT body FROM meteor_instances WHERE session_id=?" //
                    + " AND runtime_body IS NULL AND runtime_revision IS NULL AND rule_controls IS NULL";
            List<String> offlineArchives = jdbc.queryForList(offlineSql, new Object[] { owner }, String.class);
            assertEquals(1, offlineArchives.size());
            assertNotNull(offlineArchives.get(0));
            List<String> owners = jdbc.queryForList("SELECT session_id FROM meteor_instances", String.class);
            assertEquals(List.of(owner, owner), owners);
            assertTrue(jdbc.queryForList("SELECT service_id FROM meteor_instance_services", String.class).isEmpty());
            assertTrue(jdbc.queryForList("SELECT service_id FROM meteor_member_protocols", String.class).isEmpty());
            writer.registerService("one", service());
            DirectoryQuery query = new DirectoryQuery();
            RecordPage<RefServiceRelationData> providers = reader.members("echo", MetServiceType.Provider, query);
            assertEquals(1, providers.getItems().size());
        }
    }

    @Test
    public void departedReplacementDoesNotAllowOldSessionToReclaimInstance() throws Exception {
        // 后来的进程接管实例后退出，原进程仍持有旧会话。
        String url = createDatabase();
        JdbcRegistryConfiguration configuration = configuration(url);
        try (StorageAccess old = configuration.openStorage(); //
             Connection connection = DriverManager.getConnection(url, "", "")) {
            old.registerInstance(instance());
            JdbcTemplate jdbc = new JdbcTemplate(connection);
            String oldOwner = jdbc.queryForList("SELECT session_id FROM meteor_instances", String.class).get(0);
            String currentOwner;
            try (StorageAccess replacement = configuration.openStorage()) {
                replacement.registerInstance(instance());
                currentOwner = jdbc.queryForList("SELECT session_id FROM meteor_instances", String.class).get(0);
                assertNotEquals(oldOwner, currentOwner);
            }

            // 新进程退出只清运行时，档案和新归属栅栏仍留在原行。
            String offlineSql = "SELECT session_id FROM meteor_instances WHERE body IS NOT NULL" //
                    + " AND runtime_body IS NULL AND runtime_revision IS NULL AND rule_controls IS NULL";
            assertEquals(List.of(currentOwner), jdbc.queryForList(offlineSql, String.class));
            assertThrows(IllegalStateException.class, () -> old.registerInstance(instance()));
            assertThrows(IllegalStateException.class, () -> old.registerService("one", service()));
            assertNull(old.getInstance("one").getRuntime());
            assertEquals(List.of(currentOwner), jdbc.queryForList(offlineSql, String.class));
        }
    }

    @Test
    public void concurrentInitialDeclarationsKeepPublishedServiceInformation() throws Exception {
        // 管理端只声明 ID，发布端携带完整服务信息，两者争抢首次创建。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (StorageAccess publisher = configuration.openStorage(); //
             StorageAccess manager = configuration.openStorage()) {
            CyclicBarrier start = new CyclicBarrier(2);
            EntServiceCatalogData minimal = new EntServiceCatalogData();
            minimal.setServiceId("echo");
            EntServiceCatalogData complete = new EntServiceCatalogData();
            complete.setServiceId("echo");
            complete.setName("EchoService");
            complete.setType("example.EchoService");

            // 屏障让两个独立数据库会话同时执行首次写入。
            Future<?> first = workers.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                publisher.saveService(complete);
                return null;
            });
            Future<?> second = workers.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                manager.saveService(minimal);
                return null;
            });
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);

            // 不论创建顺序如何，简略声明都不能覆盖完整服务信息。
            EntServiceCatalogData saved = manager.getService("echo");
            assertEquals("EchoService", saved.getName());
            assertEquals("example.EchoService", saved.getType());
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    public void longUnicodeServiceIdIsNotTruncatedByDatabaseKeys() throws Exception {
        // 服务 ID 同时包含长 Unicode 和 SQL 通配符，存储键不能截断正文。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess storage = configuration.openStorage()) {
            String id = "服务%_".repeat(700);
            EntServiceCatalogData declaration = new EntServiceCatalogData();
            declaration.setServiceId(id);
            declaration.setName("长名称");
            storage.saveService(declaration);

            // 点查与文本查询都应还原同一个完整 ID。
            DirectoryQuery query = new DirectoryQuery();
            query.setSearch("服务%_");
            EntServiceCatalogData exact = storage.getService(id);
            RecordPage<EntServiceCatalogData> matched = storage.services(query);
            assertEquals(id, exact.getServiceId());
            assertEquals(id, matched.getItems().get(0).getServiceId());
        }
    }

}

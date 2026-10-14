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
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
            List<Long> expiry = jdbc.queryForList("SELECT expires_at FROM meteor_instances", Long.class);
            assertEquals(1, expiry.size());
            assertTrue(expiry.get(0) > System.currentTimeMillis());

            // 租约过期后两侧临时登记都不可见，三个持久档案保留。
            jdbc.executeUpdate("UPDATE meteor_instances SET expires_at=0");
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
    public void heartbeatRenewsRegisteredInstanceLease() throws Exception {
        // 使用短租约，直接观察实例行上的到期时间是否被后台心跳推进。
        String url = createDatabase();
        JdbcRegistryConfiguration configuration = configuration(url);
        configuration.setLeaseMillis(3000);
        try (StorageAccess writer = configuration.openStorage(); //
             Connection connection = DriverManager.getConnection(url, "", "")) {
            writer.registerInstance(instance());
            JdbcTemplate jdbc = new JdbcTemplate(connection);
            String sql = "SELECT expires_at FROM meteor_instances";
            long initialExpiry = jdbc.queryForList(sql, Long.class).get(0);

            // 等到真实数据库更新完成，再检查实例仍提供有效的运行信息。
            await(() -> jdbc.queryForList(sql, Long.class).get(0) > initialExpiry);
            assertNotNull(writer.getInstance("one").getRuntime());
        }
    }

    @Test
    public void heartbeatDoesNotReviveExpiredInstanceBesideLiveInstance() throws Exception {
        // 同一存储对象登记两个实例，只有 one 的租约被人为置为到期。
        String url = createDatabase();
        JdbcRegistryConfiguration configuration = configuration(url);
        configuration.setLeaseMillis(3000);
        try (StorageAccess writer = configuration.openStorage(); //
             Connection connection = DriverManager.getConnection(url, "", "")) {
            writer.registerInstance(instance("one", "orders", "east"));
            writer.registerService("one", service());
            writer.registerInstance(instance("two", "orders", "west"));
            writer.registerService("two", service());
            JdbcTemplate jdbc = new JdbcTemplate(connection);
            String liveSql = "SELECT expires_at FROM meteor_instances WHERE unit_name='west'";
            long initialExpiry = jdbc.queryForList(liveSql, Long.class).get(0);
            jdbc.executeUpdate("UPDATE meteor_instances SET expires_at=0 WHERE unit_name='east'");

            // 等待同一轮后台心跳续租 two，one 必须保持到期且服务不可见。
            await(() -> jdbc.queryForList(liveSql, Long.class).get(0) > initialExpiry);
            String expiredSql = "SELECT expires_at FROM meteor_instances WHERE unit_name='east'";
            assertEquals(List.of(0L), jdbc.queryForList(expiredSql, Long.class));
            assertNull(writer.getInstance("one").getRuntime());
            assertNull(writer.getMember("echo", MetServiceType.Provider, "one"));
            assertNotNull(writer.getMember("echo", MetServiceType.Provider, "two"));
            assertThrows(IllegalStateException.class, () -> writer.registerService("one", service()));
        }
    }

    @Test
    public void heartbeatWaitingForRowLockDoesNotReviveExpiredInstance() throws Exception {
        // 用独立事务占住实例行，让已经开始的心跳等待到租约真正到期。
        String url = createDatabase();
        try (JdbcDriverProbe probe = new JdbcDriverProbe(url)) {
            JdbcRegistryConfiguration configuration = configuration(probe.url());
            configuration.setLeaseMillis(1500);
            try (StorageAccess writer = configuration.openStorage(); //
                 Connection connection = DriverManager.getConnection(url, "", "")) {
                writer.registerInstance(instance());
                connection.setAutoCommit(false);
                JdbcTemplate jdbc = new JdbcTemplate(connection);
                jdbc.executeUpdate("UPDATE meteor_instances SET expires_at=expires_at");
                long expiry = jdbc.queryForList("SELECT expires_at FROM meteor_instances", Long.class).get(0);
                CountDownLatch waiting = new CountDownLatch(1);
                CountDownLatch nextHeartbeat = new CountDownLatch(1);
                AtomicInteger attempts = new AtomicInteger();
                probe.beforeExecute(sql -> {
                    if (sql.startsWith("UPDATE meteor_instances SET expires_at=expires_at")) {
                        if (attempts.incrementAndGet() == 1) {
                            waiting.countDown();
                        } else {
                            nextHeartbeat.countDown();
                        }
                    }
                });

                try {
                    // 先确认心跳已发起锁请求，再等实际到期时间后释放行锁。
                    assertTrue("Heartbeat did not wait for the instance row", waiting.await(5, TimeUnit.SECONDS));
                    await(() -> System.currentTimeMillis() >= expiry);
                    connection.commit();

                    // 下一轮开始说明前一轮已经执行完毕，旧时间不能把到期实例重新续活。
                    boolean completed = nextHeartbeat.await(5, TimeUnit.SECONDS);
                    assertTrue("Heartbeat did not complete its first attempt", completed);
                    List<Long> currentExpiry = jdbc.queryForList("SELECT expires_at FROM meteor_instances", Long.class);
                    assertEquals(List.of(expiry), currentExpiry);
                    assertNull(writer.getInstance("one").getRuntime());
                } finally {
                    connection.rollback();
                    probe.beforeExecute(sql -> {
                    });
                }
            }
        }
    }

    @Test
    public void recoveringExpiredInstancePreservesOtherLiveRegistration() throws Exception {
        // 同一存储对象登记两个实例，分别保存实例服务关系与治理配置。
        String url = createDatabase();
        JdbcRegistryConfiguration configuration = configuration(url);
        try (StorageAccess writer = configuration.openStorage(); //
             StorageAccess reader = configuration.openStorage(); //
             Connection connection = DriverManager.getConnection(url, "", "")) {
            writer.registerInstance(instance("one", "orders", "east"));
            writer.registerInstance(instance("two", "orders", "west"));
            EntServiceRuntimeData published = service();
            published.setEndpoints(new RefProtocolMountData[] { rsfEndpoint() });
            writer.registerService("one", published);
            writer.registerService("two", published);
            writer.saveMetadata("one", null, metadata());
            writer.saveMetadata("one", "echo", metadata());
            writer.saveMetadata("two", "echo", metadata());
            String revision = reader.getMetadata("two", "echo").getRevision();

            // 仅使 one 过期并重新登记，不重新发布它的服务。
            JdbcTemplate jdbc = new JdbcTemplate(connection);
            jdbc.executeUpdate("UPDATE meteor_instances SET expires_at=0 WHERE unit_name='east'");
            writer.registerInstance(instance("one", "orders", "east"));

            // 目标实例旧运行配置被清除，另一个实例的服务、端点和治理版本继续有效。
            assertNotNull(reader.getInstance("one").getRuntime());
            assertNull(reader.getInstanceService("one", "echo"));
            assertNull(reader.getMetadata("one", null));
            assertNull(reader.getMetadata("one", "echo"));
            RefServiceRelationData survivor = reader.getMember("echo", MetServiceType.Provider, "two");
            assertEquals("two", survivor.getInstance().getInstanceId());
            assertEquals("rsf", survivor.getService().getEndpoints()[0].getProtocol());
            assertEquals(revision, reader.getMetadata("two", "echo").getRevision());
            ServiceSummary summary = reader.serviceSummaries(List.of("echo")).get("echo");
            assertEquals(1, summary.getProviderCount());
            assertArrayEquals(new String[] { "rsf" }, summary.getProtocols());
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
            jdbc.executeUpdate("UPDATE meteor_instances SET expires_at=0");
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
    public void instanceRegistrationRecoversOnlyTargetAfterBothLeasesExpire() throws Exception {
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
            jdbc.executeUpdate("UPDATE meteor_instances SET expires_at=0");

            // 重报仅清理 one 的旧服务；two 仍然到期，其旧内容不被本次操作改动。
            writer.registerInstance(instance());
            assertNotNull(reader.getInstance("one").getRuntime());
            assertNull(reader.getInstance("two").getRuntime());
            assertNull(reader.getInstanceService("one", "echo"));
            assertNull(reader.getMember("echo", MetServiceType.Provider, "two"));
            assertNotNull(reader.getInstance("two").getInstance());
            String expiredSql = "SELECT body FROM meteor_instances WHERE session_id=?" //
                    + " AND expires_at=0 AND runtime_body IS NOT NULL AND runtime_revision IS NOT NULL";
            List<String> expiredArchives = jdbc.queryForList(expiredSql, new Object[] { owner }, String.class);
            assertEquals(1, expiredArchives.size());
            assertNotNull(expiredArchives.get(0));
            List<String> owners = jdbc.queryForList("SELECT session_id FROM meteor_instances", String.class);
            assertEquals(List.of(owner, owner), owners);
            String relationSql = "SELECT r.instance_id FROM meteor_instance_services r" //
                    + " JOIN meteor_instances i ON i.instance_id=r.instance_id WHERE i.expires_at=0";
            List<String> retainedRelations = jdbc.queryForList(relationSql, String.class);
            assertEquals(1, retainedRelations.size());
            String retainedProtocolSql = "SELECT instance_id FROM meteor_member_protocols";
            assertEquals(retainedRelations, jdbc.queryForList(retainedProtocolSql, String.class));

            // 只有显式重新发布的 one 服务恢复发现，two 不会随同一会话复活。
            writer.registerService("one", service());
            DirectoryQuery query = new DirectoryQuery();
            RecordPage<RefServiceRelationData> providers = reader.members("echo", MetServiceType.Provider, query);
            assertEquals(1, providers.getItems().size());
            assertEquals("one", providers.getItems().get(0).getInstance().getInstanceId());
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
                    + " AND runtime_body IS NULL AND runtime_revision IS NULL" //
                    + " AND rule_controls IS NULL AND expires_at IS NULL";
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

    private static void await(Callable<Boolean> condition) throws Exception {
        // 每轮读取已提交的数据库状态，用有界等待观察真实心跳完成。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.call() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue("Instance heartbeat did not renew its lease", condition.call());
    }

}

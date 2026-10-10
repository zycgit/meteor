/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.jdbc;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.ObjectUtils;
import net.hasor.dbvisitor.jdbc.core.JdbcTemplate;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetService;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.admin.MetRuleType;
import net.hasor.meteor.registry.admin.MetServiceAdmin;
import net.hasor.meteor.registry.client.RegistryClient;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Test;
import static net.hasor.meteor.registry.jdbc.JdbcTestSupport.*;
import static org.junit.Assert.*;

public class JdbcRegistryIntegrationTest {
    @MetService(group = "h2-test")
    public interface Echo {

        String echo(String value);
    }

    @Test
    public void missingTableReachesStorageAdminAndClientAndRecoveryWorks() throws Exception {
        // 通过正式扩展构建管理端和客户端，使用同一数据库的独立存储会话。
        String url = createDatabase();
        JdbcRegistryConfiguration configuration = configuration(url);
        configuration.setManagementToken("secret");
        configuration.setRefreshMillis(60000);
        MetConfiguration application = new MetConfiguration().addExtension(configuration);
        application.connector("tcp").protocol("rsf");

        try (MetContext context = application.buildContext(); //
             StorageAccess storage = configuration.openStorage(); //
             Connection connection = DriverManager.getConnection(url, "", "")) {
            context.start();
            RegistryClient client = context.getExtension(RegistryClient.class);
            RegistryAdmin admin = context.getExtension(RegistryAdmin.class);
            MetServiceAdmin service = admin.getServiceAdmin("echo");
            service.putMeta("owner", "before");
            client.pushSync().get(5, TimeUnit.SECONDS);

            // 暂时移走记录表，读写、管理操作和主动推拉都必须反馈数据库错误。
            JdbcTemplate jdbc = new JdbcTemplate(connection);
            jdbc.executeUpdate("ALTER TABLE meteor_records RENAME TO hidden_records");
            try {
                EntityRef ref = new EntityRef(EntityType.SERVICE, "echo");
                EntityRecord input = new EntityRecord(ref);
                SQLException read = assertThrows(SQLException.class, () -> storage.getEntity(ref));
                SQLException write = assertThrows(SQLException.class, () -> storage.saveEntity(input));
                IllegalStateException manage = assertThrows(IllegalStateException.class, () -> service.putMeta("owner", "after"));
                ExecutionException push = assertThrows(ExecutionException.class, () -> client.pushSync().get(5, TimeUnit.SECONDS));
                ExecutionException pull = assertThrows(ExecutionException.class, () -> client.pullSync().get(5, TimeUnit.SECONDS));
                for (Exception failure : List.of(read, write, manage, push, pull)) {
                    Throwable cause = failure;
                    while (cause.getCause() != null) {
                        cause = cause.getCause();
                    }
                    assertTrue(cause instanceof SQLException);
                    assertTrue(cause.getMessage().toLowerCase(Locale.ROOT).contains("meteor_records"));
                }
            } finally {
                jdbc.executeUpdate("ALTER TABLE hidden_records RENAME TO meteor_records");
            }

            // 恢复表后，原来的管理端与客户端仍然可以继续使用。
            service.putMeta("owner", "recovered");
            client.pushSync().get(5, TimeUnit.SECONDS);
            client.pullSync().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void typedConfigurationAndStorageSubscriptionsApplyJdbcGovernance() throws Exception {
        // 应用共用注册库，各自通过扩展取得管理与客户端能力。
        String url = createDatabase();
        JdbcRegistryConfiguration configuration = configuration(url);
        MetConfiguration serverConfig = new MetConfiguration();
        serverConfig.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration clientConfig = new MetConfiguration();
        clientConfig.connector("tcp").protocol("rsf");
        configuration.setGroup("rpc-test");
        configuration.setRefreshMillis(60000);
        configuration.setManagementToken("secret");
        serverConfig.addExtension(configuration);
        clientConfig.addExtension(configuration);
        try (MetContext server = serverConfig.buildContext(); //
             MetContext client = clientConfig.buildContext()) {
            String service = server.publisher().rsfService(Echo.class, text -> "h2:" + text).register().getBindID();
            client.publisher().rsfService(Echo.class).register();
            server.start();
            client.start();
            RegistryAdmin manager = server.getExtension(RegistryAdmin.class);
            RegistryClient registry = server.getExtension(RegistryClient.class);
            RegistryClient consumer = client.getExtension(RegistryClient.class);
            registry.pushSync().get(10, TimeUnit.SECONDS);
            consumer.pullSync().get(10, TimeUnit.SECONDS);
            assertEquals("h2:中文", client.getRsfClient().wrapper(Echo.class).echo("中文"));
            String registeredInstance = manager.getInstanceAdmin(server.getInstanceID()).instance().getInstanceId();
            assertEquals(server.getInstanceID(), registeredInstance);
            MetServiceAdmin admin = manager.getServiceAdmin(service);

            // 通过管理扩展下发元信息，等待存储订阅自动应用。
            admin.putMeta("store", "h2");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                Object appliedValue = server.getServiceInfo(service).getMetaData("store");
                if (ObjectUtils.equals("h2", appliedValue)) {
                    break;
                }
                Thread.sleep(20);
            }
            assertEquals("h2", server.getServiceInfo(service).getMetaData("store"));
            assertEquals(1, admin.providers(null).length);

        }
    }

    @Test
    public void concurrentAdminCreatorsMergeMetadata() throws Exception {
        // 两个正式管理扩展同时修改同一个尚未创建的服务。
        try (JdbcDriverProbe probe = new JdbcDriverProbe(createDatabase())) {
            JdbcRegistryConfiguration registry = configuration(probe.url());
            registry.setManagementToken("secret");
            MetConfiguration application = new MetConfiguration().addExtension(registry);

            try (MetContext first = application.buildContext(); //
                 MetContext second = application.buildContext(); //
                 StorageAccess observer = registry.openStorage()) {
                MetServiceAdmin one = first.getExtension(RegistryAdmin.class).getServiceAdmin("concurrent");
                MetServiceAdmin two = second.getExtension(RegistryAdmin.class).getServiceAdmin("concurrent");
                CyclicBarrier barrier = new CyclicBarrier(2);
                probe.beforeExecute(sql -> {
                    if (sql.startsWith("INSERT INTO meteor_records")) {
                        barrier.await(5, TimeUnit.SECONDS);
                    }
                });

                ExecutorService workers = Executors.newFixedThreadPool(2);
                try {
                    Future<?> firstWrite = workers.submit(() -> one.putMeta("one", "first"));
                    Future<?> secondWrite = workers.submit(() -> two.putMeta("two", "second"));
                    firstWrite.get(10, TimeUnit.SECONDS);
                    secondWrite.get(10, TimeUnit.SECONDS);

                    // 管理层只对冲突重读并合并，两次写入的内容均应保留。
                    EntityRef service = new EntityRef(EntityType.SERVICE, "concurrent");
                    EntServiceCatalogData data = (EntServiceCatalogData) observer.getEntity(service).getData();
                    assertEquals(Map.of("one", "first", "two", "second"), data.getMetadata());
                } finally {
                    workers.shutdownNow();
                }
            }
        }
    }

    @Test
    public void otherSqlFailuresAreNotCreationConflictsOrRetried() throws Exception {
        // 同一错误码在不同 SQLState 下不能误判，非唯一键完整性错误也不能重试。
        List<SQLException> failures = new ArrayList<>();
        failures.add(new SQLException("not null", "23502"));
        failures.add(new SQLException("foreign key", "23000", 1452));
        failures.add(new SQLException("permission", "42501"));
        failures.add(new SQLException("other driver error", "HY000", 1));
        failures.add(new SQLException("connection", "08006"));
        try (JdbcDriverProbe probe = new JdbcDriverProbe(createDatabase())) {
            JdbcRegistryConfiguration registry = configuration(probe.url());
            registry.setManagementToken("secret");
            MetConfiguration application = new MetConfiguration().addExtension(registry);

            try (MetContext context = application.buildContext(); //
                 StorageAccess storage = registry.openStorage()) {
                MetServiceAdmin service = context.getExtension(RegistryAdmin.class).getServiceAdmin("failed");
                AtomicInteger attempts = new AtomicInteger();
                for (SQLException failure : failures) {
                    probe.beforeExecute(sql -> {
                        if (sql.startsWith("INSERT INTO meteor_records")) {
                            attempts.incrementAndGet();
                            throw failure;
                        }
                    });

                    // 存储保持 SQLException，管理接口仍包装为携带原始原因的 IllegalStateException。
                    EntityRecord input = new EntityRecord(EntityType.SERVICE, "failed");
                    SQLException stored = assertThrows(SQLException.class, () -> storage.saveEntity(input));
                    assertSame(failure, stored.getCause());
                    attempts.set(0);
                    IllegalStateException managed = assertThrows(IllegalStateException.class, () -> service.putMeta("owner", "value"));
                    assertSame(failure, managed.getCause().getCause());
                    assertEquals(1, attempts.get());
                    assertNull(storage.getEntity(input.getRef()));
                }
            }
        }
    }

    @Test
    public void bootstrapUsesApplicationDataSource() throws Exception {
        // 数据源由应用准备，数据库认证与管理 token 分开。
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(url);
        dataSource.setUser("registry-user");
        dataSource.setPassword("storage-secret");
        try (Connection seed = dataSource.getConnection()) {
            initialize(seed);
            JdbcRegistryConfiguration bootstrap = new JdbcRegistryConfiguration();
            bootstrap.setDataSource(dataSource);
            bootstrap.setManagementToken("api-secret");
            bootstrap.setGroup("credentials-test");
            bootstrap.setLeaseMillis(60000);
            try (MetContext management = new MetConfiguration().addExtension(bootstrap).buildContext()) {
                RegistryAdmin admin = management.getExtension(RegistryAdmin.class);
                assertEquals("jdbc", admin.getClusterAdmin().info().getProvider());
                assertTrue(admin.getClusterAdmin().status().isReachable());
                admin.getClusterAdmin().updateRule(MetRuleType.SERVICE_ROUTE, "");
            }
            try (StorageAccess storage = bootstrap.openStorage()) {
                EntityRef service = new EntityRef(EntityType.SERVICE, "typed-configuration");
                storage.saveEntity(new EntityRecord(service));
                assertNotNull(storage.getEntity(service));
            }
            try (MetContext management = new MetConfiguration().addExtension(bootstrap).buildContext()) {
                RegistryAdmin reopened = management.getExtension(RegistryAdmin.class);
                assertEquals("", reopened.getClusterAdmin().fetchRule(MetRuleType.SERVICE_ROUTE));
            }
        }
    }

    @Test
    public void contextCapturesJdbcConfigurationBeforeStarting() throws Exception {
        // 构建上下文固定连接配置，启动前不发布实例。
        JdbcRegistryConfiguration registry = configuration(createDatabase());
        registry.setGroup("original");
        MetConfiguration application = new MetConfiguration();
        application.connector("tcp").protocol("rsf");
        application.addExtension(registry);

        try (StorageAccess observer = registry.openStorage(); //
             MetContext context = application.buildContext()) {
            EntityQuery query = new EntityQuery();
            query.setType(EntityType.INSTANCE);
            query.setSection(EntitySection.RUNTIME);
            assertTrue(observer.findEntities(query).records().isEmpty());

            // 后续配置变化不能替换已构建客户端的数据源、分组和租约。
            registry.setDataSource(null);
            registry.setGroup("changed").setLeaseMillis(1);
            context.start();
            assertEquals(context.getInstanceID(), observer.findEntities(query).records().get(0).getId());
            context.close();
            assertTrue(observer.findEntities(query).records().isEmpty());
        }
    }
}

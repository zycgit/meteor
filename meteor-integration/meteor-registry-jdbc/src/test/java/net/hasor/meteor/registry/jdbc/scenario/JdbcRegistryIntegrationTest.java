/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.jdbc.scenario;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.ObjectUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.admin.MetDirectoryQuery;
import net.hasor.meteor.registry.admin.MetInstanceInfo;
import net.hasor.meteor.registry.admin.MetServiceAdmin;
import net.hasor.meteor.registry.jdbc.JdbcRegistryConfiguration;
import net.hasor.meteor.registry.jdbc.scenario.example.Echo;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Test;
import static net.hasor.meteor.registry.jdbc.scenario.example.JdbcTestSupport.createDatabase;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class JdbcRegistryIntegrationTest {
    @Test
    public void jdbcDiscoveryCallsRegisteredServiceOverRpc() throws Exception {
        // 应用提供 DataSource，两端安装同一个 JDBC 注册中心配置。
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(createDatabase());
        JdbcRegistryConfiguration registry = new JdbcRegistryConfiguration();
        registry.setDataSource(dataSource);
        registry.setManagementToken("secret");
        MetConfiguration incoming = new MetConfiguration();
        incoming.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        incoming.addExtension(registry);
        MetConfiguration outgoing = new MetConfiguration();
        outgoing.connector("tcp").protocol("rsf");
        outgoing.addExtension(registry);

        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 发布与订阅普通 Java 服务，启动后由扩展自动登记。
            MetBindInfo<Echo> published = server.publisher() //
                    .rsfService(Echo.class, text -> "h2:" + text) //
                    .register();
            client.publisher().rsfService(Echo.class).register();
            server.start();
            client.start();

            // 等待注册中心订阅更新地址本，不通过主动推拉代替自动发现。
            String serviceId = published.getBindID();
            long discoveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (client.getUpdater().queryAvailableAddresses(serviceId).isEmpty()) {
                assertTrue("JDBC did not publish the provider address", System.nanoTime() < discoveryDeadline);
                TimeUnit.MILLISECONDS.sleep(20);
            }

            // 消费者没有本地实现，调用必须走已发现的真实网络地址。
            Echo remote = client.getRsfClient().wrapper(Echo.class);
            assertEquals("h2:中文", remote.echo("中文"));
            RegistryAdmin manager = server.getExtension(RegistryAdmin.class);
            String instanceId = server.getInstanceID();
            MetInstanceInfo registered = manager.getInstanceAdmin(instanceId).instance();
            assertEquals(instanceId, registered.getInstanceId());
            MetServiceAdmin service = manager.getServiceAdmin(published.getBindID());
            MetDirectoryQuery query = new MetDirectoryQuery();
            assertEquals(1, service.providers(null, query).getItems().size());
        }
    }

    @Test
    public void jdbcSubscriptionAutomaticallyAppliesGovernanceMetadata() throws Exception {
        // 拉长周期核对间隔，确保本场景验证存储订阅而非周期拉取。
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
            // 通过扩展接入应用，并等待初次服务登记完成。
            MetBindInfo<Echo> published = server.publisher() //
                    .rsfService(Echo.class, text -> "h2:" + text) //
                    .register();
            server.start();
            String serviceId = published.getBindID();
            RegistryAdmin manager = server.getExtension(RegistryAdmin.class);
            MetServiceAdmin service = manager.getServiceAdmin(serviceId);
            MetDirectoryQuery query = new MetDirectoryQuery();
            long registrationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (service.providers(null, query).getItems().size() == 0) {
                assertTrue("JDBC did not register the provider", System.nanoTime() < registrationDeadline);
                TimeUnit.MILLISECONDS.sleep(20);
            }

            // 管理写入后只观察应用，不主动 pull，等待订阅自动应用配置。
            service.putMeta("store", "h2");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline) {
                Object applied = server.getServiceInfo(serviceId).getMetaData("store");
                if (ObjectUtils.equals("h2", applied)) {
                    break;
                }
                Thread.sleep(20);
            }
            Object applied = server.getServiceInfo(serviceId).getMetaData("store");
            assertEquals("h2", applied);
        }
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.bootstrap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.admin.MetClusterAdmin;
import net.hasor.meteor.registry.client.RegistryClient;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import net.hasor.meteor.registry.shared.DecoratedRegistryConfiguration;
import net.hasor.meteor.registry.shared.RegistryTestUtils.Echo;
import net.hasor.meteor.registry.shared.StorageProbe;
import org.junit.Test;
import static org.junit.Assert.*;

public class RegistryExtensionTest {
    @Test
    public void contextProvidesBothServicesAndOwnsTheirSessions() throws Exception {
        // 准备统一管理客户端与管理端生命周期的扩展。
        StorageProbe probe = new StorageProbe();
        MemoryRegistryConfiguration storageConfiguration = new MemoryRegistryConfiguration();
        RegistryConfiguration registry = new DecoratedRegistryConfiguration(storageConfiguration, probe::observe);
        registry.setManagementToken("secret");
        MetConfiguration configuration = new MetConfiguration().addExtension(registry);
        configuration.connector("tcp").protocol("rsf");

        try (MetContext context = configuration.buildContext()) {
            // 构建时只创建扩展服务；重复获取返回同一个对象。
            RegistryAdmin admin = context.getExtension(RegistryAdmin.class);
            RegistryClient client = context.getExtension(RegistryClient.class);
            assertNotNull(admin);
            assertNotNull(client);
            assertSame(admin, context.getExtension(RegistryAdmin.class));
            assertSame(client, context.getExtension(RegistryClient.class));
            assertEquals(0, probe.openedSessions());

            // 读取配置的凭据和查询服务对象不打开存储，实际管理操作才打开独立会话。
            MetClusterAdmin cluster = admin.getClusterAdmin();
            assertEquals(0, probe.openedSessions());
            assertEquals(0, cluster.services(null).length);
            assertEquals(1, probe.openedSessions());
            String serviceId = context.publisher().rsfService(Echo.class).register().getBindID();
            admin.getServiceAdmin(serviceId).putMeta("owner", "managed");

            // 启动客户端会话，并应用启动前写入的治理配置。
            context.start();
            assertEquals(2, probe.openedSessions());
            assertEquals("managed", context.getServiceInfo(Echo.class).getMetaData("owner"));
            client.pushSync().get(5, TimeUnit.SECONDS);
            assertEquals(1, cluster.instances(null).length);

            // 重复关闭上下文，服务和会话一起释放，旧引用不可再操作。
            context.close();
            context.close();

            assertEquals(2, probe.closedSessions());
            assertNull(context.getExtension(RegistryAdmin.class));
            assertNull(context.getExtension(RegistryClient.class));
            assertThrows(IllegalStateException.class, () -> admin.getClusterAdmin());
            assertThrows(IllegalStateException.class, () -> cluster.services(null));
            assertThrows(ExecutionException.class, () -> client.pushSync().get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void missingManagementTokenDoesNotPreventApplicationStartup() throws Exception {
        // 准备未配置管理凭据的在线扩展。
        StorageProbe probe = new StorageProbe();
        MemoryRegistryConfiguration storageConfiguration = new MemoryRegistryConfiguration();
        RegistryConfiguration registry = new DecoratedRegistryConfiguration(storageConfiguration, probe::observe);
        MetConfiguration configuration = new MetConfiguration().addExtension(registry);
        configuration.connector("tcp").protocol("rsf");

        try (MetContext context = configuration.buildContext()) {
            // 缺少凭据只阻止管理操作，不打开管理会话。
            RegistryAdmin admin = context.getExtension(RegistryAdmin.class);
            assertNotNull(admin);
            assertThrows(IllegalStateException.class, () -> admin.getClusterAdmin());
            assertEquals(0, probe.openedSessions());

            // 应用接入仍正常启动并完成推送。
            context.start();
            context.getExtension(RegistryClient.class).pushSync().get(5, TimeUnit.SECONDS);
            assertEquals(1, probe.openedSessions());
        }

        // 关闭上下文只需释放客户端会话。
        assertEquals(1, probe.closedSessions());
    }

    @Test
    public void startupFailureClosesPreviouslyUsedManagementSession() throws Exception {
        // 准备可模拟存储故障的在线扩展。
        StorageProbe probe = new StorageProbe();
        MemoryRegistryConfiguration storageConfiguration = new MemoryRegistryConfiguration();
        RegistryConfiguration registry = new DecoratedRegistryConfiguration(storageConfiguration, probe::observe);
        registry.setManagementToken("secret");
        MetConfiguration configuration = new MetConfiguration().addExtension(registry);
        configuration.connector("tcp").protocol("rsf");

        try (MetContext context = configuration.buildContext()) {
            // 启动前先使用管理入口，打开管理会话。
            RegistryAdmin admin = context.getExtension(RegistryAdmin.class);
            assertEquals(0, admin.getClusterAdmin().services(null).length);

            // 注入故障后启动，确认管理和客户端会话均被清理。
            probe.setUnavailable(true);
            assertThrows(IllegalStateException.class, context::start);
            assertEquals(2, probe.openedSessions());
            assertEquals(2, probe.closedSessions());
            assertNull(context.getExtension(RegistryAdmin.class));
            assertThrows(IllegalStateException.class, () -> admin.getClusterAdmin());
        }
    }

    @Test
    public void contextsCaptureIndependentManagementCredentials() throws Exception {
        // 准备共享存储、使用不同凭据来源的两个上下文。
        StorageProbe probe = new StorageProbe();
        MemoryRegistryConfiguration storageConfiguration = new MemoryRegistryConfiguration();
        RegistryConfiguration registry = new DecoratedRegistryConfiguration(storageConfiguration, probe::observe);
        AtomicReference<String> firstToken = new AtomicReference<>("first");
        AtomicReference<String> secondToken = new AtomicReference<>("second");
        registry.setTokenProvider(firstToken::get);
        MetConfiguration configuration = new MetConfiguration().addExtension(registry);
        configuration.connector("tcp").protocol("rsf");

        try (MetContext first = configuration.buildContext()) {
            // 第二个上下文捕获新的凭据来源。
            registry.setTokenProvider(secondToken::get);

            try (MetContext second = configuration.buildContext()) {
                RegistryAdmin firstAdmin = first.getExtension(RegistryAdmin.class);
                RegistryAdmin secondAdmin = second.getExtension(RegistryAdmin.class);
                assertNotSame(firstAdmin, secondAdmin);
                firstAdmin.getClusterAdmin();
                secondAdmin.getClusterAdmin();

                // 第一个凭据失效只影响第一个管理入口。
                firstToken.set(null);
                assertThrows(SecurityException.class, () -> firstAdmin.getClusterAdmin());
                secondAdmin.getClusterAdmin();

                // 恢复第一个并使第二个失效，验证隔离方向对称。
                firstToken.set("first-rotated");
                secondToken.set(null);
                assertThrows(SecurityException.class, () -> secondAdmin.getClusterAdmin());
                firstAdmin.getClusterAdmin();
            }
        }

        // 只查询服务并读取凭据，不会创建任何存储资源。
        assertEquals(0, probe.openedSessions());
        assertEquals(0, probe.closedSessions());
    }
}

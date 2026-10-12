/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.bootstrap;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.admin.MetClusterAdmin;
import net.hasor.meteor.registry.admin.MetServiceInfo;
import net.hasor.meteor.registry.client.RegistryClient;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import net.hasor.meteor.registry.shared.DecoratedRegistryConfiguration;
import net.hasor.meteor.registry.shared.RegistryTestUtils;
import net.hasor.meteor.registry.shared.RegistryTestUtils.Echo;
import net.hasor.meteor.registry.shared.StorageProbe;
import net.hasor.meteor.registry.shared.rpc.ManagementRPC;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.EntityRecord;
import net.hasor.meteor.registry.shared.storage.domain.EntityRef;
import net.hasor.meteor.registry.shared.storage.domain.EntityType;
import org.junit.Test;
import static org.junit.Assert.*;

public class RegistryAdminConfigurationTest {
    @Test
    public void validatesManagementConfigurationBeforeOpeningStorage() throws Exception {
        // 准备带存储观察器的注册中心配置。
        StorageProbe probe = new StorageProbe();
        RegistryConfiguration registry = new DecoratedRegistryConfiguration(new MemoryRegistryConfiguration(), probe::observe);

        // 固定凭据为空时直接拒绝配置。
        assertThrows(IllegalArgumentException.class, () -> registry.setManagementToken(null));
        assertThrows(IllegalArgumentException.class, () -> registry.setManagementToken(" "));

        // 移除凭据提供者后仍可创建扩展，但不能取得管理服务。
        registry.setManagementToken("secret");
        registry.setTokenProvider(null);
        MetConfiguration application = new MetConfiguration().addExtension(registry);

        try (MetContext context = application.buildContext()) {
            RegistryAdmin admin = context.getExtension(RegistryAdmin.class);
            assertThrows(IllegalStateException.class, admin::getClusterAdmin);
            assertEquals(0, probe.openedSessions());
        }
    }

    @Test
    public void buildsIndependentAdminConfigurationSnapshots() throws Exception {
        // 准备共享内存库，先按原凭据创建第一个上下文。
        MemoryRegistryConfiguration bootstrap = new MemoryRegistryConfiguration();
        bootstrap.setManagementToken("口令");
        MetConfiguration firstConfiguration = new MetConfiguration().addExtension(bootstrap);

        try (MetContext firstContext = firstConfiguration.buildContext()) {
            RegistryAdmin first = firstContext.getExtension(RegistryAdmin.class);
            assertEquals(0, first.getClusterAdmin().services(null).length);

            // 修改配置后创建第二个上下文，两个管理入口独立。
            bootstrap.setManagementToken("changed");
            MetConfiguration secondConfiguration = new MetConfiguration().addExtension(bootstrap);

            try (MetContext secondContext = secondConfiguration.buildContext()) {
                RegistryAdmin second = secondContext.getExtension(RegistryAdmin.class);
                assertEquals("memory", first.getClusterAdmin().info().getProvider());
                assertEquals("memory", second.getClusterAdmin().info().getProvider());
                assertNotSame(first, second);

                // 修改查询结果不能反向修改内部配置。
                first.getClusterAdmin().info().getScope().put("external", "changed");
                assertTrue(first.getClusterAdmin().info().getScope().isEmpty());
            }
        }
    }

    @Test
    public void closingAdminReleasesSessionOnceAndRejectsFurtherStorageAccess() throws Exception {
        // 准备管理扩展并记录存储会话的释放次数。
        MemoryRegistryConfiguration storageConfiguration = new MemoryRegistryConfiguration();
        StorageProbe probe = new StorageProbe();
        RegistryConfiguration bootstrap = new DecoratedRegistryConfiguration(storageConfiguration, probe::observe);
        bootstrap.setManagementToken("secret");
        MetConfiguration adminConfiguration = new MetConfiguration().addExtension(bootstrap);

        try (MetContext adminContext = adminConfiguration.buildContext()) {
            RegistryAdmin admin = adminContext.getExtension(RegistryAdmin.class);

            // 首次管理操作打开会话，确认当前可访问。
            MetClusterAdmin cluster = admin.getClusterAdmin();
            assertTrue(cluster.status().isReachable());

            // 重复关闭管理入口，只释放一次会话并拒绝后续操作。
            admin.close();
            admin.close();

            assertEquals(1, probe.closedSessions());
            assertThrows(IllegalStateException.class, () -> admin.getClusterAdmin());
            assertThrows(IllegalStateException.class, () -> cluster.services(null));
        }

        // 上下文退出时不会重复释放已关闭的管理会话。
        assertEquals(1, probe.openedSessions());
        assertEquals(1, probe.closedSessions());
    }

    @Test
    public void buildsClientWithExistingServicesRegisteredAndGovernanceApplied() throws Exception {
        // 关闭自动上线，分别配置应用传输层和注册中心扩展。
        MemoryRegistryConfiguration bootstrap = new MemoryRegistryConfiguration();
        bootstrap.setManagementToken("secret");
        MetConfiguration application = new MetConfiguration().setAutomaticOnline(false);
        application.connector("tcp").protocol("rsf");
        application.addExtension(bootstrap);

        try (MetContext context = application.buildContext()) {
            RegistryAdmin admin = context.getExtension(RegistryAdmin.class);

            // 启动前声明服务，并预先写入其治理配置。
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class).register();
            admin.getServiceAdmin(service.getBindID()).putMeta("owner", "managed");

            // 启动时注册服务并应用治理，应用仍保持离线。
            context.start();
            RegistryClient client = context.getExtension(RegistryClient.class);
            assertFalse(context.isOnline());
            assertEquals("managed", service.getMetaData("owner"));
            String instanceId = context.getInstanceID();
            MetServiceInfo[] services = admin.getInstanceAdmin(instanceId).getServiceList();
            assertEquals(1, services.length);
            assertEquals(service.getBindID(), services[0].getServiceId());
            assertTrue(context.getServiceInfo(ManagementRPC.class).isShadow());

            // 主动推送不影响管理 RPC 的注册。
            client.pushSync().get(5, TimeUnit.SECONDS);
            assertNotNull(context.getServiceInfo(ManagementRPC.class));
        }
    }

    @Test
    public void createsClientAndAdminWithIndependentLifecycles() throws Exception {
        // 应用和独立管理上下文使用同一注册中心配置。
        MemoryRegistryConfiguration bootstrap = new MemoryRegistryConfiguration();
        bootstrap.setManagementToken("secret");
        MetConfiguration application = new MetConfiguration().setApplicationName("订单");
        application.connector("tcp").protocol("rsf");
        application.addExtension(bootstrap);

        try (MetContext context = application.buildContext()) {
            context.start();

            // 通过另一上下文下发阻断治理，等待应用订阅生效。
            MetConfiguration adminConfiguration = new MetConfiguration().addExtension(bootstrap);

            try (MetContext adminContext = adminConfiguration.buildContext()) {
                RegistryAdmin admin = adminContext.getExtension(RegistryAdmin.class);
                assertEquals(1, admin.getClusterAdmin().instances("订单").length);
                admin.getInstanceAdmin(context.getInstanceID()).block();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!admin.getInstanceAdmin(context.getInstanceID()).instance().isBlock()) {
                    assertTrue("Client did not apply storage change", System.nanoTime() < deadline);
                    Thread.sleep(10);
                }
            }

            // 管理上下文关闭后，应用会话及注册信息仍然有效。
            assertTrue(context.isOnline());
            assertTrue(RegistryTestUtils.hasRegistrations(bootstrap));

            // 应用关闭后才撤销自己的临时注册信息。
            context.close();
            assertFalse(context.isOnline());
            assertFalse(RegistryTestUtils.hasRegistrations(bootstrap));
        }
    }

    @Test
    public void directStorageIsIndependentAndDoesNotRequireManagementToken() throws Exception {
        // 准备不带管理凭据的内存库。
        MemoryRegistryConfiguration configuration = new MemoryRegistryConfiguration();
        EntityRef service = new EntityRef(EntityType.SERVICE, "echo");

        try (StorageAccess first = configuration.openStorage(); //
             StorageAccess second = configuration.openStorage()) {
            // 两个会话共享记录，但拥有独立生命周期。
            assertNotSame(first, second);
            first.saveEntity(new EntityRecord(service));
            assertNotNull(second.getEntity(service));

            // 关闭写入会话不影响另一会话读取持久记录。
            first.close();
            assertNotNull(second.getEntity(service));
        }
    }

    @Test
    public void eachContextCapturesConfigurationAtBuildTime() throws Exception {
        // 准备可重复构建上下文的配置与会话观察器。
        MemoryRegistryConfiguration storageConfiguration = new MemoryRegistryConfiguration();
        StorageProbe probe = new StorageProbe();
        RegistryConfiguration registry = new DecoratedRegistryConfiguration(storageConfiguration, probe::observe);
        MetConfiguration application = new MetConfiguration().addExtension(registry);
        application.connector("tcp").protocol("rsf");

        try (MetContext first = application.buildContext()) {
            // 构建时只创建本地扩展，随后修改配置不能改变已创建客户端。
            assertEquals(0, probe.openedSessions());
            registry.setRefreshMillis(0);
            assertThrows(IllegalStateException.class, application::buildContext);
            first.start();
            assertEquals(1, probe.openedSessions());

            // 恢复有效配置后构建第二个上下文，关闭第一个不影响第二个。
            registry.setRefreshMillis(60000);

            try (MetContext second = application.buildContext()) {
                RegistryClient firstClient = first.getExtension(RegistryClient.class);
                RegistryClient secondClient = second.getExtension(RegistryClient.class);
                assertNotSame(firstClient, secondClient);
                second.start();
                first.close();
                secondClient.pushSync().get(5, TimeUnit.SECONDS);
                assertTrue(second.isOnline());
            }
        }

        // 两个上下文最终各自释放所属会话。
        assertEquals(2, probe.openedSessions());
        assertEquals(2, probe.closedSessions());
    }

    @Test
    public void clientOnlySettingsDoNotPreventIndependentStorageAccess() throws Exception {
        // 准备对客户端无效、对底层存储无影响的刷新参数。
        MemoryRegistryConfiguration registry = new MemoryRegistryConfiguration();
        registry.setManagementToken("secret");
        registry.setRefreshMillis(0);
        registry.setPublishHost("127.0.0.1");

        // 直接存储访问仍然可用。
        try (StorageAccess storage = registry.openStorage()) {
            assertNotNull(storage);
        }

        // 相同配置用于应用扩展时，在构建阶段报告参数错误。
        MetConfiguration application = new MetConfiguration().addExtension(registry);
        application.connector("tcp").protocol("rsf");
        IllegalStateException failure = assertThrows(IllegalStateException.class, application::buildContext);
        assertEquals("refreshMillis must be positive", failure.getCause().getMessage());
    }

}

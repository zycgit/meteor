/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.bootstrap;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.TargetAdmin;
import net.hasor.meteor.registry.admin.MetInstanceAdmin;
import net.hasor.meteor.registry.admin.RegistryInfo;
import net.hasor.meteor.registry.client.RegistryClient;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import net.hasor.meteor.registry.shared.DecoratedRegistryConfiguration;
import net.hasor.meteor.registry.shared.StorageProbe;
import net.hasor.meteor.registry.shared.rpc.ManagementRPC;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import org.junit.Test;
import static org.junit.Assert.*;

public class RegistryOnlineTest {
    @Test
    public void onlineModeAlsoProvidesDirectManagement() throws Exception {
        // 准备只提供直连管理的目标应用。
        MetConfiguration server = new MetConfiguration();
        server.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        server.addExtension(new RegistryConfiguration().setOnline(false));

        // 准备连接注册中心的调用方，观察其独立存储会话。
        StorageProbe probe = new StorageProbe();
        RegistryConfiguration registry = new DecoratedRegistryConfiguration(new MemoryRegistryConfiguration(), probe::observe);
        registry.setManagementToken("secret");
        assertTrue(registry.isOnline());
        MetConfiguration application = new MetConfiguration();
        application.connector("tcp").protocol("rsf");
        application.addExtension(registry);

        try (MetContext target = server.buildContext(); //
             MetContext caller = application.buildContext()) {
            // 构建扩展时同时提供两种管理入口，但尚未打开存储。
            TargetAdmin targets = caller.getExtension(TargetAdmin.class);
            assertNotNull(targets);
            assertNotNull(caller.getExtension(RegistryAdmin.class));
            assertNotNull(caller.getExtension(RegistryClient.class));
            assertEquals(0, probe.openedSessions());

            // 启动双方并等待调用方完成注册。
            target.start();
            caller.start();
            caller.getExtension(RegistryClient.class).pushSync().get(5, TimeUnit.SECONDS);

            // 本地也发布管理 RPC；直连入口仍然访问指定远端，并且不打开管理存储会话。
            MetInstanceAdmin instance = targets.getInstanceAdminByAddress(target.bindAddress("rsf"));
            assertEquals(target.getInstanceID(), instance.instance().getInstanceId());
            assertTrue(caller.getServiceInfo(ManagementRPC.class).isShadow());
            assertEquals(1, probe.openedSessions());

            // 同一个在线扩展同时提供存储管理和直连管理，两者各自完成真实操作。
            RegistryAdmin admin = caller.getExtension(RegistryAdmin.class);
            assertEquals(1, admin.getClusterAdmin().instances(null, null).getItems().size());
            admin.getServiceAdmin("managed-only").putMeta("owner", "registry");
            assertEquals(1, admin.getClusterAdmin().services("managed-only", null).getItems().size());
            assertEquals(target.getInstanceID(), instance.instance().getInstanceId());
            assertEquals(2, probe.openedSessions());
        }

        // 退出上下文时管理与客户端会话均被释放。
        assertEquals(2, probe.closedSessions());
    }

    @Test
    public void onlyOneRegistryConfigurationCanBeInstalledRegardlessOfMode() {
        // 遍历在线与离线组合，同一上下文均只允许一个注册中心扩展。
        for (boolean firstOnline : new boolean[] { true, false }) {
            for (boolean secondOnline : new boolean[] { true, false }) {
                // 准备两个扩展声明，但不启动网络或存储。
                StorageProbe probe = new StorageProbe();
                RegistryConfiguration registry = new DecoratedRegistryConfiguration(new MemoryRegistryConfiguration(), probe::observe);
                registry.setOnline(firstOnline);
                MetConfiguration application = new MetConfiguration();
                application.connector("tcp").protocol("rsf");
                application.addExtension(registry);
                application.addExtension(new MemoryRegistryConfiguration().setOnline(secondOnline));

                // 构建时拒绝重复安装，且未产生存储会话。
                IllegalStateException failure = assertThrows(IllegalStateException.class, application::buildContext);
                String message = "Only one RegistryConfiguration can be installed on a Meteor context";
                assertEquals(message, failure.getCause().getMessage());
                assertEquals(0, probe.openedSessions());
            }
        }
    }

    @Test
    public void offlineModeProvidesManagementRpcWithoutOpeningListener() throws Exception {
        // 准备离线注册中心扩展，传输层仅用于主动连接。
        MetConfiguration application = new MetConfiguration();
        application.connector("tcp").protocol("rsf");
        application.addExtension(new RegistryConfiguration().setOnline(false));

        try (MetContext caller = application.buildContext()) {
            // 构建时提供直连入口并注册本地管理服务。
            TargetAdmin targets = caller.getExtension(TargetAdmin.class);
            assertNotNull(targets);
            assertSame(targets, caller.getExtension(TargetAdmin.class));
            assertNotNull(caller.getServiceInfo(ManagementRPC.class));

            // 启动不产生监听器，也不提供注册中心管理端和客户端。
            caller.start();
            assertNull(caller.bindAddress("rsf"));
            assertNotNull(caller.getServiceInfo(ManagementRPC.class));
            assertNull(caller.getExtension(RegistryAdmin.class));
            assertNull(caller.getExtension(RegistryClient.class));

            // 关闭上下文后，扩展入口一并移除。
            caller.close();
            assertNull(caller.getExtension(TargetAdmin.class));
        }
    }

    @Test
    public void offlineModeNeverReadsStorageConfigurationOrToken() throws Exception {
        // 存储描述和凭据一旦被读取就失败，检查离线分支的边界。
        RegistryConfiguration registry = new RegistryConfiguration() {
            @Override
            public String getProvider() {
                throw new AssertionError("Storage provider must not be read");
            }

            @Override
            protected Callable<StorageAccess> createStorageOpener() {
                throw new AssertionError("Storage must not be opened");
            }

            @Override
            protected RegistryInfo describeStorage() {
                throw new AssertionError("Storage must not be described");
            }
        };
        registry.setRefreshMillis(0);
        registry.setTokenProvider(() -> {
            throw new AssertionError("Storage administration token must not be read");
        });

        // 将无效的在线配置用于离线扩展，仍应能正常启动。
        registry.setOnline(false);
        assertFalse(registry.isOnline());
        MetConfiguration application = new MetConfiguration();
        application.connector("tcp").protocol("rsf");
        application.addExtension(registry);

        try (MetContext context = application.buildContext()) {
            context.start();
            assertNotNull(context.getExtension(TargetAdmin.class));
            assertNull(context.getExtension(RegistryAdmin.class));
            assertNull(context.getExtension(RegistryClient.class));
            assertNotNull(context.getServiceInfo(ManagementRPC.class));
        }

        // 离线配置显式请求存储时必须报错。
        String message = "Registry storage is unavailable in offline mode";
        assertEquals(message, assertThrows(IllegalStateException.class, registry::openStorage).getMessage());
    }

    @Test
    public void onlineChangesOnlyAffectContextsBuiltAfterTheChange() throws Exception {
        // 按离线配置创建第一个上下文。
        StorageProbe probe = new StorageProbe();
        RegistryConfiguration registry = new DecoratedRegistryConfiguration(new MemoryRegistryConfiguration(), probe::observe);
        registry.setOnline(false);
        MetConfiguration application = new MetConfiguration();
        application.connector("tcp").protocol("rsf");
        application.addExtension(registry);

        try (MetContext standalone = application.buildContext()) {
            // 切换在线后创建第二个上下文，再次修改配置不影响已有实例。
            registry.setOnline(true);

            try (MetContext connected = application.buildContext()) {
                registry.setOnline(false);
                assertEquals(0, probe.openedSessions());

                // 分别启动，只有构建时在线的上下文打开存储。
                standalone.start();
                assertEquals(0, probe.openedSessions());
                connected.start();
                assertEquals(1, probe.openedSessions());
                assertNotSame(standalone.getExtension(TargetAdmin.class), connected.getExtension(TargetAdmin.class));
                assertNull(standalone.getExtension(RegistryClient.class));
                assertNotNull(connected.getExtension(RegistryClient.class));
            }
        }

        // 两个上下文退出后，仅释放实际创建的那一个会话。
        assertEquals(1, probe.closedSessions());
    }

    @Test
    public void onlineModeRequiresAProviderConfiguration() {
        // 基础配置默认为在线，但没有具体存储提供者。
        RegistryConfiguration registry = new RegistryConfiguration();
        assertTrue(registry.isOnline());
        MetConfiguration application = new MetConfiguration();
        application.addExtension(registry);

        // 构建扩展时明确报告缺少存储配置。
        IllegalStateException failure = assertThrows(IllegalStateException.class, application::buildContext);
        assertEquals("Online mode requires a storage provider configuration", failure.getCause().getMessage());
    }
}

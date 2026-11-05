/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.test.service;
import java.nio.file.Path;
import java.util.ConcurrentModificationException;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.setting.Settings;
import net.hasor.core.AppContext;
import net.hasor.core.Hasor;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.console.component.commands.CommandDispatcher;
import net.hasor.meteor.console.component.commands.dto.directory.ClusterInfoRequest;
import net.hasor.meteor.console.component.provider.ProviderConnection;
import net.hasor.meteor.console.component.provider.ProviderFactories;
import net.hasor.meteor.console.component.provider.ProviderFactory;
import net.hasor.meteor.console.dao.ClusterDao;
import net.hasor.meteor.console.global.MeteorConfig;
import net.hasor.meteor.console.global.auth.ConsoleCookies;
import net.hasor.meteor.console.global.auth.SessionService;
import net.hasor.meteor.console.model.fo.ClusterProfile;
import net.hasor.meteor.console.model.fo.ManagementTarget;
import net.hasor.meteor.console.service.ClusterService;
import net.hasor.meteor.console.service.GovernanceService;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.RegistryTokenProvider;
import net.hasor.meteor.registry.TargetAdmin;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 由真实容器负责业务服务的单例、初始化与销毁。 */
public class ServiceLifecycleTest {
    @TempDir
    public Path temporary;

    @Test
    public void containerShutdownClosesCachedClusterConnection() throws Exception {
        // 工厂打开真实内存注册中心，装饰器仅观察连接是否随容器退出被释放。
        MemoryRegistryConfiguration storage = new MemoryRegistryConfiguration();
        storage.setManagementToken("lifecycle-token");
        AtomicInteger closed = new AtomicInteger();
        ProviderFactory factory = new ProviderFactory() {
            @Override
            public String name() {
                return "jdbc";
            }

            @Override
            public ProviderConnection open(ClusterProfile profile, RegistryTokenProvider credentials) throws Exception {
                RegistryAdmin registry = storage.buildAdmin();
                return new ProviderConnection() {
                    @Override
                    public RegistryAdmin registry() {
                        return registry;
                    }

                    @Override
                    public void close() throws Exception {
                        closed.incrementAndGet();
                        registry.close();
                    }
                };
            }
        };
        Hasor hasor = this.container(factory);
        CommandDispatcher dispatcher;
        ManagementTarget target = new ManagementTarget();
        target.setScope("cluster");

        try (AppContext application = hasor.build()) {
            ClusterService clusters = application.getInstance(ClusterService.class);
            assertSame(clusters, application.getInstance(ClusterService.class));
            ClusterProfile profile = new ClusterProfile();
            profile.setId("lifecycle");
            profile.setName("生命周期测试");
            profile.setType("jdbc");
            profile.setDatabase("h2");
            profile.setAddress("jdbc:h2:mem:lifecycle");
            application.getInstance(ClusterDao.class).save(profile);

            // 派发入口自行管理连接，服务无需返回或持有管理上下文。
            dispatcher = application.getInstance(CommandDispatcher.class);
            var result = dispatcher.execute(profile.getId(), new ClusterInfoRequest(target));
            assertEquals("memory", result.info().getProvider());
            assertTrue(result.status().isReachable());
            assertEquals(0, closed.get());
            assertTrue(dispatcher.execute(profile.getId(), new ClusterInfoRequest(target)).status().isReachable());
        }

        // 容器触发销毁后，保留的入口不能再打开或使用旧连接。
        assertEquals(1, closed.get());
        assertThrows(ConcurrentModificationException.class, () -> {
            dispatcher.execute("lifecycle", new ClusterInfoRequest(target));
        });
    }

    @Test
    public void dispatcherLifecycleClosesDirectContextButKeepsBorrowedMeteorAlive() throws Exception {
        // 治理服务借用已启动的 Meteor 客户端；目标实例独立监听真实 RSF 端口。
        RegistryConfiguration targetRegistry = new RegistryConfiguration().setOnline(false);
        targetRegistry.setManagementToken("lifecycle-token");
        MetConfiguration targetConfiguration = new MetConfiguration().setApplicationName("lifecycle-target");
        targetConfiguration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        targetConfiguration.addExtension(targetRegistry);
        MetConfiguration callerConfiguration = new MetConfiguration().setApplicationName("lifecycle-caller");
        callerConfiguration.connector("tcp").protocol("rsf");
        callerConfiguration.addExtension(new RegistryConfiguration().setOnline(false));
        MemoryRegistryConfiguration storage = new MemoryRegistryConfiguration();
        storage.setManagementToken("lifecycle-token");
        ProviderFactory factory = new ProviderFactory() {
            @Override
            public String name() {
                return "jdbc";
            }

            @Override
            public ProviderConnection open(ClusterProfile profile, RegistryTokenProvider credentials) throws Exception {
                return ProviderConnection.registry(storage.buildAdmin());
            }
        };

        try (MetContext target = targetConfiguration.buildContext(); //
             MetContext caller = callerConfiguration.buildContext()) {
            target.start();
            caller.start();
            ManagementTarget request = new ManagementTarget();
            request.setDirect(true);
            request.setTarget(target.getListenAddress("rsf"));
            request.setManagementToken("lifecycle-token");
            Hasor hasor = this.container(factory).addModules(binder -> {
                binder.bindType(MetContext.class).toInstance(caller);
                binder.bindType(GovernanceService.class);
            });
            GovernanceService retained;

            // 治理服务只提交命令；直连上下文由派发入口按需准备。
            try (AppContext application = hasor.build()) {
                retained = application.getInstance(GovernanceService.class);
                assertSame(retained, application.getInstance(GovernanceService.class));
                var result = retained.instanceInfo(request);
                assertEquals("lifecycle-target", result.instance().getApplicationName());
            }

            // 容器销毁派发入口后，被借用的客户端仍能独立完成管理 RPC。
            assertThrows(ConcurrentModificationException.class, () -> retained.instanceInfo(request));
            TargetAdmin admin = caller.getExtension(TargetAdmin.class).withTokenProvider(() -> "lifecycle-token");
            var info = admin.getInstanceAdminByAddress(request.getTarget()).instance();
            assertEquals("lifecycle-target", info.getApplicationName());
        }
    }

    private Hasor container(ProviderFactory factory) {
        Hasor hasor = Hasor.create().registerShutdownHook(false).addModules(binder -> {
            binder.bindType(MeteorConfig.class).asEagerSingleton();
            binder.bindType(ClusterDao.class).asEagerSingleton();
            binder.bindType(ConsoleCookies.class).asEagerSingleton();
            binder.bindType(SessionService.class).asEagerSingleton();
            binder.bindType(CommandDispatcher.class);
            binder.bindType(ProviderFactory.class).toInstance(factory);
            binder.bindType(ProviderFactories.class);
            binder.bindType(ClusterService.class);
        });
        hasor.addSettings(Settings.DefaultNameSpace, "hasor.boot.web.connectors.http.enabled", false);
        hasor.addSettings(Settings.DefaultNameSpace, "data.directory", this.temporary.toString());
        hasor.addSettings(Settings.DefaultNameSpace, "security.enable", false);
        return hasor;
    }
}

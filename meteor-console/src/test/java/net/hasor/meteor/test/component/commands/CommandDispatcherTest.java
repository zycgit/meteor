/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.test.component.commands;
import java.nio.file.Path;
import java.util.ConcurrentModificationException;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.setting.Settings;
import net.hasor.core.AppContext;
import net.hasor.core.Hasor;
import net.hasor.meteor.console.component.commands.ChangeResult;
import net.hasor.meteor.console.component.commands.CommandDispatcher;
import net.hasor.meteor.console.component.commands.CommandRequest;
import net.hasor.meteor.console.component.commands.CommandType;
import net.hasor.meteor.console.component.commands.SuccessResult;
import net.hasor.meteor.console.component.commands.action.ClusterInfoRegistryCmd;
import net.hasor.meteor.console.component.commands.dto.directory.ClusterInfoRequest;
import net.hasor.meteor.console.component.commands.dto.directory.ClusterInfoResult;
import net.hasor.meteor.console.component.commands.dto.rule.ReadRuleRequest;
import net.hasor.meteor.console.component.commands.dto.rule.RuleResult;
import net.hasor.meteor.console.component.commands.dto.rule.UpdateRuleRequest;
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
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.RegistryTokenProvider;
import net.hasor.meteor.registry.admin.MetRuleType;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

public class CommandDispatcherTest {
    @TempDir
    public Path temporary;

    @Test
    public void clusterRuleUpdateWritesRegistryWithoutExpandingInstanceBatch() throws Exception {
        // 容器管理命令入口与连接，独立管理入口用于验证实际写入。
        MemoryRegistryConfiguration storage = storage();
        Hasor hasor = this.container(new ObservedProviderFactory(storage, new AtomicInteger()));
        try (RegistryAdmin registry = storage.buildAdmin(); //
             AppContext app = hasor.build()) {
            app.getInstance(ClusterDao.class).save(profile());
            CommandDispatcher dispatcher = app.getInstance(CommandDispatcher.class);
            ManagementTarget target = clusterTarget();
            String rule = "<controlSet/>";

            // 集群整体写入不展开实例批次，affected 为零；实际规则写入仍须完成。
            UpdateRuleRequest request = new UpdateRuleRequest(target, MetRuleType.FLOW_CONTROL, rule);
            ChangeResult result = dispatcher.execute("commands", request);
            assertTrue(result.success());
            assertEquals(0, result.affected());
            assertEquals(rule, registry.getClusterAdmin().fetchRule(MetRuleType.FLOW_CONTROL));
        }
    }

    @Test
    public void readRuleReturnsSavedRuleAndDisabledState() throws Exception {
        // 直接准备存储中的规则及开关，再通过容器内的命令入口读取。
        MemoryRegistryConfiguration storage = storage();
        Hasor hasor = this.container(new ObservedProviderFactory(storage, new AtomicInteger()));
        try (RegistryAdmin registry = storage.buildAdmin(); //
             AppContext app = hasor.build()) {
            app.getInstance(ClusterDao.class).save(profile());
            CommandDispatcher dispatcher = app.getInstance(CommandDispatcher.class);
            String rule = "<controlSet/>";
            registry.getClusterAdmin().updateRule(MetRuleType.FLOW_CONTROL, rule);
            registry.getClusterAdmin().disableRule(MetRuleType.FLOW_CONTROL);

            ReadRuleRequest request = new ReadRuleRequest(clusterTarget(), MetRuleType.FLOW_CONTROL);
            RuleResult result = dispatcher.execute("commands", request);
            assertEquals(MetRuleType.FLOW_CONTROL, result.type());
            assertEquals(rule, result.rule());
            assertEquals(Boolean.FALSE, result.enabled());
            assertTrue(result.switchSupported());
            assertTrue(result.validationSupported());
        }
    }

    @Test
    public void mismatchedRequestTypeIsRejectedBeforeProviderOpens() throws Exception {
        // 声称更新规则却返回成功模型的伪请求，不能进入真实后端。
        MemoryRegistryConfiguration storage = storage();
        AtomicInteger opened = new AtomicInteger();
        ProviderFactory factory = new ObservedProviderFactory(storage, opened);
        CommandRequest<SuccessResult> request = new CommandRequest<>(clusterTarget()) {
            @Override
            public CommandType getCommand() {
                return CommandType.UPDATE_RULE;
            }
        };

        Hasor hasor = this.container(factory);
        try (AppContext app = hasor.build()) {
            CommandDispatcher dispatcher = app.getInstance(CommandDispatcher.class);
            assertThrows(IllegalArgumentException.class, () -> dispatcher.execute("validation", request));
            assertEquals(0, opened.get());
        }
    }

    @Test
    public void missingTargetIsRejectedBeforeProviderOpens() throws Exception {
        // 请求类型合法但缺少管理目标时，在创建后端连接前给出明确参数错误。
        MemoryRegistryConfiguration storage = storage();
        AtomicInteger opened = new AtomicInteger();
        ProviderFactory factory = new ObservedProviderFactory(storage, opened);
        ClusterInfoRequest request = new ClusterInfoRequest(null);

        Hasor hasor = this.container(factory);
        try (AppContext app = hasor.build()) {
            CommandDispatcher dispatcher = app.getInstance(CommandDispatcher.class);
            assertThrows(IllegalArgumentException.class, () -> dispatcher.execute("validation", request));
            assertEquals(0, opened.get());
        }
    }

    @Test
    public void closingUnusedDispatcherDoesNotCreateRegistryResources() throws Exception {
        // 保存配置不会创建连接；容器关闭也不能为尚未使用的集群打开后端。
        MemoryRegistryConfiguration storage = storage();
        AtomicInteger opened = new AtomicInteger();
        ProviderFactory factory = new ObservedProviderFactory(storage, opened);

        Hasor hasor = this.container(factory);
        try (AppContext app = hasor.build()) {
            app.getInstance(ClusterDao.class).save(profile());
            app.getInstance(CommandDispatcher.class);
            assertEquals(0, opened.get());
        }
        assertEquals(0, opened.get());
    }

    @Test
    public void closedDispatcherRejectsCommandsWithoutOpeningProvider() throws Exception {
        // 未使用的入口关闭后，保留其引用也不能重新创建后端连接。
        MemoryRegistryConfiguration storage = storage();
        AtomicInteger opened = new AtomicInteger();
        ProviderFactory factory = new ObservedProviderFactory(storage, opened);
        Hasor hasor = this.container(factory);
        CommandDispatcher retained;
        try (AppContext app = hasor.build()) {
            app.getInstance(ClusterDao.class).save(profile());
            retained = app.getInstance(CommandDispatcher.class);
        }
        ClusterInfoRequest request = new ClusterInfoRequest(clusterTarget());
        assertThrows(ConcurrentModificationException.class, () -> retained.execute("commands", request));
        assertEquals(0, opened.get());
    }

    @Test
    public void containerInitializesCommandBindingsOnceAndReusesDispatcherAcrossContexts() throws Exception {
        // 自定义命令工厂只观察真实命令的创建，确保初始化实际从容器取实现。
        AtomicInteger created = new AtomicInteger();
        MemoryRegistryConfiguration firstStorage = storage();
        MemoryRegistryConfiguration secondStorage = storage();
        ProviderFactory factory = new ProviderFactory() {
            @Override
            public String name() {
                return "jdbc";
            }

            @Override
            public ProviderConnection open(ClusterProfile profile, RegistryTokenProvider credentials) throws Exception {
                if (StringUtils.equals("first", profile.getId())) {
                    return ProviderConnection.registry(firstStorage.buildAdmin());
                }
                return ProviderConnection.registry(secondStorage.buildAdmin());
            }
        };
        Hasor hasor = this.container(factory).addModules(binder -> {
            binder.bindType(ClusterInfoRegistryCmd.class).toProvider(() -> {
                created.incrementAndGet();
                return new ClusterInfoRegistryCmd();
            });
        });
        hasor.addSettings(Settings.DefaultNameSpace, "hasor.boot.web.connectors.http.enabled", false);
        try (RegistryAdmin firstRegistry = firstStorage.buildAdmin(); //
             RegistryAdmin secondRegistry = secondStorage.buildAdmin(); //
             AppContext app = hasor.build()) {
            ClusterDao profiles = app.getInstance(ClusterDao.class);
            ClusterProfile first = profile();
            first.setId("first");
            profiles.save(first);
            ClusterProfile second = profile();
            second.setId("second");
            profiles.save(second);
            CommandDispatcher dispatcher = app.getInstance(CommandDispatcher.class);
            assertEquals(1, created.get());
            assertSame(dispatcher, app.getInstance(CommandDispatcher.class));

            // 同一个入口操作不同上下文，命令实例不能保存前一个注册中心的状态。
            firstRegistry.getClusterAdmin().updateRule(MetRuleType.FLOW_CONTROL, "<controlSet/>");
            secondRegistry.getClusterAdmin().updateRule(MetRuleType.FLOW_CONTROL, "<controlSet></controlSet>");
            ClusterInfoResult info = dispatcher.execute("first", new ClusterInfoRequest(clusterTarget()));
            assertTrue(info.status().isReachable());
            assertEquals("memory", info.info().getProvider());
            ReadRuleRequest request = new ReadRuleRequest(clusterTarget(), MetRuleType.FLOW_CONTROL);
            assertEquals("<controlSet/>", dispatcher.execute("first", request).rule());
            assertEquals("<controlSet></controlSet>", dispatcher.execute("second", request).rule());
            assertEquals(1, created.get());
        }
    }

    @Test
    public void clusterIdSelectsIsolatedCachedConnections() throws Exception {
        // 两个集群分别保存不同规则，工厂只负责把各自配置映射到真实内存存储。
        MemoryRegistryConfiguration firstStorage = storage();
        MemoryRegistryConfiguration secondStorage = storage();
        AtomicInteger opened = new AtomicInteger();
        ProviderFactory factory = new ProviderFactory() {
            @Override
            public String name() {
                return "jdbc";
            }

            @Override
            public ProviderConnection open(ClusterProfile profile, RegistryTokenProvider credentials) throws Exception {
                opened.incrementAndGet();
                if (StringUtils.equals("first", profile.getId())) {
                    return ProviderConnection.registry(firstStorage.buildAdmin());
                }
                return ProviderConnection.registry(secondStorage.buildAdmin());
            }
        };
        Hasor hasor = this.container(factory);
        try (AppContext app = hasor.build()) {
            ClusterDao profiles = app.getInstance(ClusterDao.class);
            ClusterProfile first = profile();
            first.setId("first");
            profiles.save(first);
            ClusterProfile second = profile();
            second.setId("second");
            profiles.save(second);
            CommandDispatcher dispatcher = app.getInstance(CommandDispatcher.class);

            // 公共入口自行选连接，调用方不创建或保存 CommandContext。
            String rule = "<controlSet/>";
            UpdateRuleRequest update = new UpdateRuleRequest(clusterTarget(), MetRuleType.FLOW_CONTROL, rule);
            dispatcher.execute("first", update);
            ReadRuleRequest read = new ReadRuleRequest(clusterTarget(), MetRuleType.FLOW_CONTROL);
            assertEquals("<controlSet/>", dispatcher.execute("first", read).rule());
            assertNull(dispatcher.execute("second", read).rule());
            assertEquals("<controlSet/>", dispatcher.execute("first", read).rule());
            assertEquals(2, opened.get());
        }
    }

    @Test
    public void missingClusterIdIsRejectedBeforeProviderOpens() throws Exception {
        // 缺少显式集群 ID，不能退回到某个全局的当前集群。
        AtomicInteger opened = new AtomicInteger();
        Hasor hasor = this.container(new ObservedProviderFactory(storage(), opened));
        try (AppContext app = hasor.build()) {
            CommandDispatcher dispatcher = app.getInstance(CommandDispatcher.class);
            ClusterInfoRequest request = new ClusterInfoRequest(clusterTarget());
            assertThrows(IllegalArgumentException.class, () -> dispatcher.execute((String) null, request));
            assertEquals(0, opened.get());
        }
    }

    @Test
    public void conflictingClusterIdsAreRejectedBeforeProviderOpens() throws Exception {
        // 派发入口和请求目标不能指向不同集群，避免带错 ID 的请求误操作另一后端。
        AtomicInteger opened = new AtomicInteger();
        Hasor hasor = this.container(new ObservedProviderFactory(storage(), opened));
        try (AppContext app = hasor.build()) {
            ClusterProfile profile = profile();
            profile.setId("first");
            app.getInstance(ClusterDao.class).save(profile);
            ManagementTarget target = clusterTarget();
            target.setClusterId("second");
            ClusterInfoRequest request = new ClusterInfoRequest(target);
            CommandDispatcher dispatcher = app.getInstance(CommandDispatcher.class);
            assertThrows(IllegalArgumentException.class, () -> dispatcher.execute("first", request));
            assertEquals(0, opened.get());
        }
    }

    @Test
    public void directTargetRejectsClusterIdBeforeOpeningAnyConnection() throws Exception {
        // 直连请求不能同时选择集群，错误应在获取直连客户端或打开 Provider 前被拒绝。
        AtomicInteger opened = new AtomicInteger();
        Hasor hasor = this.container(new ObservedProviderFactory(storage(), opened));
        try (AppContext app = hasor.build()) {
            ManagementTarget target = clusterTarget();
            target.setDirect(true);
            ClusterInfoRequest request = new ClusterInfoRequest(target);
            CommandDispatcher dispatcher = app.getInstance(CommandDispatcher.class);
            assertThrows(IllegalArgumentException.class, () -> dispatcher.execute("first", request));
            assertEquals(0, opened.get());
        }
    }

    @Test
    public void savingClusterClosesOldConnectionAndNextCommandOpensNewOne() throws Exception {
        // 保存时的连接检查使用临时会话，旧缓存与检查会话都应关闭。
        AtomicInteger opened = new AtomicInteger();
        ObservedProviderFactory factory = new ObservedProviderFactory(storage(), opened);
        Hasor hasor = this.container(factory);
        try (AppContext app = hasor.build()) {
            ClusterProfile profile = profile();
            profile.setId("saved");
            app.getInstance(ClusterDao.class).save(profile);
            CommandDispatcher dispatcher = app.getInstance(CommandDispatcher.class);
            ClusterInfoRequest request = new ClusterInfoRequest(clusterTarget());
            dispatcher.execute("saved", request);
            assertEquals(1, opened.get());

            // 上层只保存配置，Dispatcher 负责使旧会话失效并在后续请求中重建。
            ClusterProfile changed = profile();
            changed.setId("saved");
            changed.setName("已修改的连接");
            app.getInstance(ClusterService.class).save(changed);
            assertEquals(2, opened.get());
            assertEquals(2, factory.closed.get());
            assertTrue(dispatcher.execute("saved", request).status().isReachable());
            assertEquals(3, opened.get());
        }
        assertEquals(3, factory.closed.get());
    }

    @Test
    public void removingClusterClosesConnectionAndRejectsFollowingCommands() throws Exception {
        // 已使用的连接在配置删除后不能继续由缓存提供管理能力。
        AtomicInteger opened = new AtomicInteger();
        ObservedProviderFactory factory = new ObservedProviderFactory(storage(), opened);
        Hasor hasor = this.container(factory);
        try (AppContext app = hasor.build()) {
            ClusterProfile profile = profile();
            profile.setId("removed");
            app.getInstance(ClusterDao.class).save(profile);
            CommandDispatcher dispatcher = app.getInstance(CommandDispatcher.class);
            ClusterInfoRequest request = new ClusterInfoRequest(clusterTarget());
            dispatcher.execute("removed", request);

            // 删除通过服务层触发失效，后续请求必须重新校验集群是否存在。
            app.getInstance(ClusterService.class).remove("removed");
            assertEquals(1, factory.closed.get());
            assertThrows(NoSuchElementException.class, () -> dispatcher.execute("removed", request));
            assertEquals(1, opened.get());
        }
    }

    private Hasor container(ProviderFactory factory) {
        Hasor hasor = Hasor.create().registerShutdownHook(false).addModules(binder -> {
            binder.bindType(MeteorConfig.class).asEagerSingleton();
            binder.bindType(ClusterDao.class).asEagerSingleton();
            binder.bindType(ConsoleCookies.class).asEagerSingleton();
            binder.bindType(SessionService.class).asEagerSingleton();
            binder.bindType(ClusterService.class);
            binder.bindType(CommandDispatcher.class);
            binder.bindType(ProviderFactories.class);
            binder.bindType(ProviderFactory.class).toInstance(factory);
        });
        hasor.addSettings(Settings.DefaultNameSpace, "hasor.boot.web.connectors.http.enabled", false);
        hasor.addSettings(Settings.DefaultNameSpace, "data.directory", this.temporary.toString());
        hasor.addSettings(Settings.DefaultNameSpace, "security.enable", false);
        return hasor;
    }

    private static final class ObservedProviderFactory implements ProviderFactory {
        private final MemoryRegistryConfiguration storage;
        private final AtomicInteger               opened;
        private final AtomicInteger               closed = new AtomicInteger();

        private ObservedProviderFactory(MemoryRegistryConfiguration storage, AtomicInteger opened) {
            this.storage = storage;
            this.opened = opened;
        }

        @Override
        public String name() {
            return "jdbc";
        }

        @Override
        public ProviderConnection open(ClusterProfile profile, RegistryTokenProvider credentials) throws Exception {
            this.opened.incrementAndGet();
            RegistryAdmin registry = this.storage.buildAdmin();
            return new ProviderConnection() {
                @Override
                public RegistryAdmin registry() {
                    return registry;
                }

                @Override
                public void close() throws Exception {
                    ObservedProviderFactory.this.closed.incrementAndGet();
                    registry.close();
                }
            };
        }
    }

    private static MemoryRegistryConfiguration storage() {
        MemoryRegistryConfiguration storage = new MemoryRegistryConfiguration();
        storage.setManagementToken("commands-token");
        return storage;
    }

    private static ClusterProfile profile() {
        ClusterProfile profile = new ClusterProfile();
        profile.setId("commands");
        profile.setName("命令入口内存测试");
        profile.setType("jdbc");
        profile.setDatabase("h2");
        profile.setAddress("jdbc:h2:mem:commands");
        return profile;
    }

    private static ManagementTarget clusterTarget() {
        ManagementTarget target = new ManagementTarget();
        target.setScope("cluster");
        return target;
    }
}

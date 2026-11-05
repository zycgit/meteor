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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.ExceptionUtils;
import net.hasor.cobble.setting.Settings;
import net.hasor.core.AppContext;
import net.hasor.core.Hasor;
import net.hasor.meteor.console.component.commands.CommandDispatcher;
import net.hasor.meteor.console.component.commands.dto.directory.ClusterInfoRequest;
import net.hasor.meteor.console.component.commands.dto.directory.ClusterInfoResult;
import net.hasor.meteor.console.component.provider.ProviderConnection;
import net.hasor.meteor.console.component.provider.ProviderFactories;
import net.hasor.meteor.console.component.provider.ProviderFactory;
import net.hasor.meteor.console.dao.ClusterDao;
import net.hasor.meteor.console.global.MeteorConfig;
import net.hasor.meteor.console.global.auth.ConsoleCookies;
import net.hasor.meteor.console.global.auth.SessionService;
import net.hasor.meteor.console.model.fo.ClusterProfile;
import net.hasor.meteor.console.model.fo.ManagementTarget;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.RegistryTokenProvider;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** 派发入口拥有缓存连接，并按闲置期限回收；实际命令仍访问真实内存注册中心。 */
public class CommandDispatcherLifecycleTest {
    @TempDir
    public Path temporary;

    @ParameterizedTest(name = "拒绝非正寿命：{0} ms")
    @ValueSource(longs = { 0, -1 })
    public void nonPositiveLifetimeFailsDuringContainerStartup(long lifetime) {
        // 非正寿命是配置错误，应在容器初始化阶段拒绝，不能打开后端连接。
        ObservedProviderFactory factory = new ObservedProviderFactory(false);
        Hasor hasor = this.container(factory, lifetime);
        RuntimeException failure = assertThrows(RuntimeException.class, hasor::build);
        Throwable cause = ExceptionUtils.getRootCause(failure);
        assertInstanceOf(IllegalArgumentException.class, cause);
        assertTrue(cause.getMessage().contains("commands.context.ttl"));
        assertEquals(0, factory.opened.get());
    }

    @Test
    public void idleConnectionExpiresAndFollowingCommandReopensIt() throws Exception {
        // 短寿命便于观察固定一秒巡检，不直接调用回收实现。
        ObservedProviderFactory factory = new ObservedProviderFactory(false);
        Hasor hasor = this.container(factory, 100);
        try (AppContext app = hasor.build()) {
            app.getInstance(ClusterDao.class).save(profile());
            CommandDispatcher dispatcher = app.getInstance(CommandDispatcher.class);
            assertTrue(dispatcher.execute("lifecycle", request()).status().isReachable());
            assertEquals(1, factory.opened.get());

            // 到期释放独占连接，后续请求再按集群配置建立新连接。
            assertTrue(factory.firstClose.await(3, TimeUnit.SECONDS), "闲置连接应由周期巡检关闭");
            assertEquals(1, factory.closed.get());
            assertTrue(dispatcher.execute("lifecycle", request()).status().isReachable());
            assertEquals(2, factory.opened.get());
        }
        assertEquals(2, factory.closed.get());
    }

    @Test
    public void eachCommandRenewsIdleConnectionLifetime() throws Exception {
        // 寿命大于一次巡检，首次请求后在到期前再次提交命令。
        ObservedProviderFactory factory = new ObservedProviderFactory(false);
        Hasor hasor = this.container(factory, 1800);
        try (AppContext app = hasor.build()) {
            app.getInstance(ClusterDao.class).save(profile());
            CommandDispatcher dispatcher = app.getInstance(CommandDispatcher.class);
            dispatcher.execute("lifecycle", request());
            assertFalse(factory.firstClose.await(1100, TimeUnit.MILLISECONDS));
            dispatcher.execute("lifecycle", request());

            // 原寿命已过但新寿命尚未结束，第二次巡检仍应保留同一连接。
            assertFalse(factory.firstClose.await(1100, TimeUnit.MILLISECONDS), "命令应续期而非沿用首次时间");
            assertEquals(1, factory.opened.get());
            assertTrue(factory.firstClose.await(3, TimeUnit.SECONDS));
            assertEquals(1, factory.closed.get());
        }
    }

    @Test
    public void expirationDoesNotCloseConnectionWhileCommandIsRunning() throws Exception {
        // 只阻塞真实连接的入口，用明确的信号控制命令跨越巡检时刻。
        ObservedProviderFactory factory = new ObservedProviderFactory(true);
        Hasor hasor = this.container(factory, 100);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (AppContext app = hasor.build()) {
            app.getInstance(ClusterDao.class).save(profile());
            CommandDispatcher dispatcher = app.getInstance(CommandDispatcher.class);
            Future<ClusterInfoResult> running = executor.submit(() -> dispatcher.execute("lifecycle", request()));
            try {
                assertTrue(factory.entered.await(3, TimeUnit.SECONDS));
                assertFalse(factory.firstClose.await(1500, TimeUnit.MILLISECONDS), "执行中的连接不能被回收");
            } finally {
                factory.release.countDown();
            }

            // 命令完成后才允许回收，执行结果仍来自实际管理接口。
            assertTrue(running.get(3, TimeUnit.SECONDS).status().isReachable());
            assertTrue(factory.firstClose.await(3, TimeUnit.SECONDS));
            assertEquals(1, factory.closed.get());
        } finally {
            factory.release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    public void containerShutdownStopsExpirationAndRejectsNewCommands() throws Exception {
        // 容器销毁统一关闭已缓存连接，保留入口仅用于观察关闭后的行为。
        ObservedProviderFactory factory = new ObservedProviderFactory(false);
        Hasor hasor = this.container(factory, 100);
        CommandDispatcher retained;
        try (AppContext app = hasor.build()) {
            app.getInstance(ClusterDao.class).save(profile());
            retained = app.getInstance(CommandDispatcher.class);
            retained.execute("lifecycle", request());
        }

        // 关闭后不会因下一轮巡检重复释放或重新创建连接。
        assertEquals(1, factory.closed.get());
        assertThrows(ConcurrentModificationException.class, () -> retained.execute("lifecycle", request()));
        assertThrows(ConcurrentModificationException.class, () -> retained.test(profile()));
        assertFalse(factory.secondClose.await(1200, TimeUnit.MILLISECONDS));
        assertEquals(1, factory.opened.get());
        assertEquals(1, factory.closed.get());
    }

    @Test
    public void shutdownWaitsForTemporaryConnectionTestAndClosesItOnce() throws Exception {
        // 候选连接检查尚未保存到集群缓存，仍然由派发入口统一管理生命周期。
        ObservedProviderFactory factory = new ObservedProviderFactory(true);
        Hasor hasor = this.container(factory, 100);
        AppContext app = hasor.build();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<?> closing = null;
        try {
            CommandDispatcher dispatcher = app.getInstance(CommandDispatcher.class);
            Future<?> checking = workers.submit(() -> dispatcher.test(profile()));
            assertTrue(factory.entered.await(3, TimeUnit.SECONDS));
            CountDownLatch closeStarted = new CountDownLatch(1);
            Future<?> shutdown = workers.submit(() -> {
                closeStarted.countDown();
                app.close();
                return null;
            });
            closing = shutdown;

            // 停机不能越过仍在执行的临时连接检查，更不能提前释放其连接。
            assertTrue(closeStarted.await(3, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> shutdown.get(200, TimeUnit.MILLISECONDS));
            assertEquals(0, factory.closed.get());
            factory.release.countDown();
            try {
                checking.get(3, TimeUnit.SECONDS);
            } catch (ExecutionException failure) {
                // 停机可能在后续目录检查前关闭上下文，此时明确拒绝剩余检查。
                assertInstanceOf(ConcurrentModificationException.class, ExceptionUtils.getRootCause(failure));
            }
            shutdown.get(3, TimeUnit.SECONDS);

            // 临时会话与容器销毁共享关闭职责，不重复关闭，也不再打开新的连接。
            assertEquals(1, factory.opened.get());
            assertEquals(1, factory.closed.get());
            assertThrows(ConcurrentModificationException.class, () -> dispatcher.test(profile()));
            assertEquals(1, factory.opened.get());
        } finally {
            factory.release.countDown();
            try {
                if (closing == null) {
                    app.close();
                } else {
                    closing.get(3, TimeUnit.SECONDS);
                }
            } finally {
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
            }
        }
    }

    private Hasor container(ProviderFactory factory, long lifetime) {
        Hasor hasor = Hasor.create().registerShutdownHook(false).addModules(binder -> {
            binder.bindType(MeteorConfig.class).asEagerSingleton();
            binder.bindType(ClusterDao.class).asEagerSingleton();
            binder.bindType(ConsoleCookies.class).asEagerSingleton();
            binder.bindType(SessionService.class).asEagerSingleton();
            binder.bindType(ProviderFactory.class).toInstance(factory);
            binder.bindType(ProviderFactories.class);
            binder.bindType(CommandDispatcher.class).asEagerSingleton();
        });
        hasor.addSettings(Settings.DefaultNameSpace, "hasor.boot.web.connectors.http.enabled", false);
        hasor.addSettings(Settings.DefaultNameSpace, "data.directory", this.temporary.toString());
        hasor.addSettings(Settings.DefaultNameSpace, "security.enable", false);
        hasor.addSettings(Settings.DefaultNameSpace, "commands.context.ttl", lifetime);
        return hasor;
    }

    private static ClusterProfile profile() {
        ClusterProfile profile = new ClusterProfile();
        profile.setId("lifecycle");
        profile.setName("命令连接寿命测试");
        profile.setType("jdbc");
        profile.setDatabase("h2");
        profile.setAddress("jdbc:h2:mem:command-lifecycle");
        return profile;
    }

    private static ClusterInfoRequest request() {
        ManagementTarget target = new ManagementTarget();
        target.setScope("cluster");
        return new ClusterInfoRequest(target);
    }

    /** 装饰真实连接，仅记录开关次数并可阻塞第一次进入管理接口的调用。 */
    private static final class ObservedProviderFactory implements ProviderFactory {
        private final MemoryRegistryConfiguration storage     = new MemoryRegistryConfiguration();
        private final AtomicBoolean               blockFirst;
        private final AtomicInteger               opened      = new AtomicInteger();
        private final AtomicInteger               closed      = new AtomicInteger();
        private final CountDownLatch              firstClose  = new CountDownLatch(1);
        private final CountDownLatch              secondClose = new CountDownLatch(1);
        private final CountDownLatch              entered     = new CountDownLatch(1);
        private final CountDownLatch              release     = new CountDownLatch(1);

        private ObservedProviderFactory(boolean blockFirst) {
            this.blockFirst = new AtomicBoolean(blockFirst);
            this.storage.setManagementToken("lifecycle-token");
        }

        @Override
        public String name() {
            return "jdbc";
        }

        @Override
        public ProviderConnection open(ClusterProfile profile, RegistryTokenProvider credentials) throws Exception {
            RegistryAdmin registry = this.storage.buildAdmin();
            this.opened.incrementAndGet();
            return new ProviderConnection() {
                @Override
                public RegistryAdmin registry() {
                    if (ObservedProviderFactory.this.blockFirst.compareAndSet(true, false)) {
                        ObservedProviderFactory.this.entered.countDown();
                        try {
                            if (!ObservedProviderFactory.this.release.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("测试未释放命令入口");
                            }
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("命令等待被中断", failure);
                        }
                    }
                    return registry;
                }

                @Override
                public void close() throws Exception {
                    registry.close();
                    int total = ObservedProviderFactory.this.closed.incrementAndGet();
                    ObservedProviderFactory.this.firstClose.countDown();
                    if (total >= 2) {
                        ObservedProviderFactory.this.secondClose.countDown();
                    }
                }
            };
        }
    }
}

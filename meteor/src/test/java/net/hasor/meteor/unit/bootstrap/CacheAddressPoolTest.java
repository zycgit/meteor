/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.bootstrap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BiFunction;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.MetUpdater;
import net.hasor.meteor.address.DiskCache;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.address.dsl.RouteContext;
import net.hasor.meteor.address.provider.AddressProvider;
import net.hasor.meteor.bootstrap.CacheAddressPool;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class CacheAddressPoolTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void poolUpdaterAndProvidersShareAddressChangesBeforeStart() throws Exception {
        // 启动前通过更新器准备动态地址，固定地址提供器独立于服务桶。
        MetConfiguration configuration = this.settings();
        try (CacheAddressPool pool = new CacheAddressPool(configuration)) {
            MetUpdater updater = pool;
            AddressProvider provider = pool.getProvider();
            InterAddress pooled = new InterAddress("rsf://127.0.0.1:2111/default");
            InterAddress fixed = new InterAddress("rsf://127.0.0.1:2112/default");
            updater.appendAddress("service", Collections.singletonList(pooled));
            assertTrue(provider.isDistributed());
            RouteContext request = new RouteContext("service", "echo", new Object[0]);
            assertEquals(pooled, provider.get(request));
            AddressProvider fixedProvider = pool.getProvider(fixed);
            assertFalse(fixedProvider.isDistributed());
            assertEquals(fixed, fixedProvider.get(request));

            // 删除服务桶只移除动态地址，固定目标仍可继续使用。
            pool.removeBucket("service");
            assertNull(provider.get(request));
            assertEquals(fixed, fixedProvider.get(request));
        }
    }

    @Test
    public void connectionFailureInvalidatesDynamicAddressesButPreservesStaticOnes() throws Exception {
        // 同一服务同时包含动态和静态地址，连接失败只使动态地址失效。
        MetConfiguration configuration = this.settings();
        try (CacheAddressPool pool = new CacheAddressPool(configuration)) {
            InterAddress dynamic = new InterAddress("rsf://127.0.0.1:2111/default");
            InterAddress fixed = new InterAddress("rsf://127.0.0.1:2112/default");
            pool.appendAddress("service", Collections.singletonList(dynamic));
            pool.appendStaticAddress("service", Collections.singletonList(fixed));

            // 分别报告连接失败，检查动态失效集合与仍可用的静态地址。
            pool.invalidAddress(dynamic);
            pool.invalidAddress(fixed);
            assertEquals(Collections.singletonList(dynamic), pool.queryInvalidAddresses("service"));
            assertEquals(Collections.singletonList(fixed), pool.queryAvailableAddresses("service"));
        }
    }

    @Test
    public void repeatedStartSchedulesOneMaintenanceTaskAtATime() {
        // 重复启动同一个地址池，只安排一个维护任务。
        MetConfiguration configuration = this.settings();
        ManualScheduler scheduler = new ManualScheduler();
        try (CacheAddressPool pool = new CacheAddressPool(configuration)) {
            pool.start(scheduler);
            pool.start(scheduler);
            assertEquals(1, scheduler.tasks.size());
            ScheduledTask first = scheduler.tasks.get(0);
            assertEquals(configuration.getRefreshCacheTime(), first.delayMillis);

            // 本次维护执行完毕后，才登记下一次维护任务。
            first.action.run();
            assertEquals(2, scheduler.tasks.size());
        }
    }

    @Test
    public void closeCancelsOnlyOwnedTaskAndRejectsDequeuedCallbacks() {
        // 调度器先有外部任务，再为地址池登记并推进一次维护。
        MetConfiguration configuration = this.settings();
        ManualScheduler scheduler = new ManualScheduler();
        Cancellable unrelated = scheduler.apply(() -> {
        }, 10L);
        try (CacheAddressPool pool = new CacheAddressPool(configuration)) {
            pool.start(scheduler);
            scheduler.tasks.get(1).action.run();
            ScheduledTask next = scheduler.tasks.get(2);

            // 重复关闭只取消地址池自己的下一次任务，不影响外部任务。
            pool.close();
            pool.close();
            assertTrue(next.cancelled);
            assertFalse(((ScheduledTask) unrelated).cancelled);

            // 模拟取消前已出队的回调；关闭后不能重排任务，也不能重新启动。
            next.action.run();
            assertEquals(3, scheduler.tasks.size());
            assertThrows(IllegalStateException.class, () -> pool.start(scheduler));
        }
    }

    @Test
    public void closingDuringRefreshPreventsItsReschedule() throws Exception {
        // 用观察器阻塞正在执行的刷新，在刷新结束前关闭地址池。
        ManualScheduler scheduler = new ManualScheduler();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        MetConfiguration configuration = this.settings();
        try (CacheAddressPool pool = new CacheAddressPool(configuration)) {
            InterAddress address = new InterAddress("rsf://127.0.0.1:2111/default");
            pool.appendAddress("service", Collections.singletonList(address));
            pool.getBucket("service").addObserver((source, event) -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Refresh was not released");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            });

            // 刷新进入阻塞点后关闭，再放行并等待刷新结束。
            pool.start(scheduler);
            Future<?> refreshing = executor.submit(scheduler.tasks.get(0).action);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            pool.close();
            release.countDown();
            refreshing.get(5, TimeUnit.SECONDS);
            assertEquals(1, scheduler.tasks.size());
            assertTrue(scheduler.tasks.get(0).cancelled);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void closeBeforeStartCannotBeReversed() {
        // 尚未启动的地址池关闭后，不能通过启动恢复。
        MetConfiguration configuration = this.settings();
        try (CacheAddressPool pool = new CacheAddressPool(configuration)) {
            pool.close();
            assertThrows(IllegalStateException.class, () -> pool.start(new ManualScheduler()));
        }
    }

    @Test
    public void invalidIntervalAbortsStartupAndPreventsRestart() {
        // 非正刷新间隔在启动阶段拒绝，并使地址池进入已关闭状态。
        MetConfiguration configuration = this.settings();
        configuration.setRefreshCacheTime(0);
        try (CacheAddressPool pool = new CacheAddressPool(configuration)) {
            assertThrows(IllegalArgumentException.class, () -> pool.start(new ManualScheduler()));
            assertThrows(IllegalStateException.class, () -> pool.start(new ManualScheduler()));
        }
    }

    @Test
    public void rejectedSchedulerAbortsStartupAndPreservesCause() {
        // 外部调度器拒绝任务时保留原异常，启动失败不能再次启动。
        MetConfiguration configuration = this.settings();
        RejectedExecutionException rejection = new RejectedExecutionException("scheduler unavailable");
        try (CacheAddressPool pool = new CacheAddressPool(configuration)) {
            RejectedExecutionException failure = assertThrows(RejectedExecutionException.class, () -> {
                pool.start((task, delay) -> {
                    throw rejection;
                });
            });
            assertSame(rejection, failure);
            assertThrows(IllegalStateException.class, () -> pool.start(new ManualScheduler()));
        }
    }

    @Test
    public void diskCacheRestoresBeforeStartReturnsAndOwnsItsMaintenance() throws Exception {
        // 先生成磁盘快照，再用独立地址池验证启动恢复。
        MetConfiguration configuration = this.settings();
        InterAddress address = new InterAddress("rsf://127.0.0.1:2111/default");
        try (CacheAddressPool seed = new CacheAddressPool(configuration); //
             DiskCache cache = new DiskCache(seed, this.temporary.getRoot(), 60000, 3600000)) {
            seed.appendAddress("service", address);
            cache.storeConfig();
        }

        // 启用磁盘缓存时启动同步恢复地址，并由缓存自身维护，不借用调度器。
        configuration.setLocalDiskCache(true);
        try (CacheAddressPool pool = new CacheAddressPool(configuration)) {
            pool.appendAddress("service", Collections.emptyList());
            RouteContext request = new RouteContext("service", "echo", new Object[0]);
            AddressProvider provider = pool.getProvider();
            assertNull(provider.get(request));
            ManualScheduler scheduler = new ManualScheduler();
            pool.start(scheduler);
            assertEquals(0, scheduler.tasks.size());
            assertEquals(address, provider.get(request));
        }
    }

    @Test
    public void disabledDiskCacheIgnoresExistingSnapshotAndBorrowsScheduler() throws Exception {
        // 磁盘上存在快照，但未启用磁盘缓存的地址池仍从空地址集启动。
        MetConfiguration configuration = this.settings();
        InterAddress address = new InterAddress("rsf://127.0.0.1:2111/default");
        try (CacheAddressPool seed = new CacheAddressPool(configuration); //
             DiskCache cache = new DiskCache(seed, this.temporary.getRoot(), 60000, 3600000)) {
            seed.appendAddress("service", address);
            cache.storeConfig();
        }

        // 内存模式不读取快照，并向提供的调度器安排一次维护任务。
        ManualScheduler scheduler = new ManualScheduler();
        try (CacheAddressPool pool = new CacheAddressPool(configuration)) {
            pool.appendAddress("service", Collections.emptyList());
            pool.start(scheduler);
            RouteContext request = new RouteContext("service", "echo", new Object[0]);
            assertNull(pool.getProvider().get(request));
            assertEquals(1, scheduler.tasks.size());
        }
    }

    @Test
    public void xmlRouteReadsActualArguments() throws Exception {
        // 地址池按请求的实际参数执行 XML 路由筛选。
        MetConfiguration configuration = this.settings();
        try (CacheAddressPool pool = new CacheAddressPool(configuration)) {
            InterAddress first = new InterAddress("rsf://127.0.0.1:2111/default");
            InterAddress second = new InterAddress("rsf://127.0.0.1:2112/default");
            pool.appendAddress("service", Arrays.asList(first, second));
            String argsScript = """
                    <route>
                        <filter name="selected" from="addresses" item="a" test="a.port == args[0]"/>
                        <return value="selected"/>
                    </route>
                    """;

            // 更新路由后使用第二个端口作为实参，只能选中第二个地址。
            assertTrue(pool.updateArgsRoute("service", argsScript));
            RouteContext request = new RouteContext("service", "echo", new Object[] { 2112 });
            assertEquals(second, pool.getProvider().get(request));
        }
    }

    private MetConfiguration settings() {
        MetConfiguration settings = new MetConfiguration();
        settings.setUnitName("default");
        settings.setInvalidWaitTime(30000);
        settings.setRefreshCacheTime(60000);
        settings.setLocalDiskCache(false);
        settings.setDiskCacheTimeInterval(3600000);
        settings.setDataHome(this.temporary.getRoot().toPath());
        return settings;
    }

    private static final class ManualScheduler implements BiFunction<Runnable, Long, Cancellable> {
        private final List<ScheduledTask> tasks = new ArrayList<>();

        @Override
        public Cancellable apply(Runnable action, Long delay) {
            ScheduledTask task = new ScheduledTask(action, delay);
            this.tasks.add(task);
            return task;
        }
    }

    private static final class ScheduledTask implements Cancellable {
        private final Runnable action;
        private final long     delayMillis;
        private       boolean  cancelled;

        private ScheduledTask(Runnable action, long delayMillis) {
            this.action = action;
            this.delayMillis = delayMillis;
        }

        @Override
        public boolean cancel() {
            this.cancelled = true;
            return true;
        }
    }

}

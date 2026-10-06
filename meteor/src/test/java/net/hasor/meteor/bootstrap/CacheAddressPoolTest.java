/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.bootstrap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BiFunction;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.MetSettings;
import net.hasor.meteor.MetUpdater;
import net.hasor.meteor.address.DiskCache;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.address.provider.AddressProvider;
import net.hasor.meteor.address.route.ArgsKey;
import net.hasor.meteor.address.route.DefaultArgsKey;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class CacheAddressPoolTest {
    @Rule
    public  TemporaryFolder          temporary       = new TemporaryFolder();
    private boolean                  diskCache;
    private long                     refreshInterval = 60000;
    private Class<? extends ArgsKey> argsKey         = DefaultArgsKey.class;

    @Test
    public void poolUpdaterAndProvidersShareAddressChangesBeforeStart() throws Exception {
        try (CacheAddressPool pool = this.pool()) {
            MetUpdater updater = pool;
            AddressProvider provider = pool.getProvider();
            InterAddress pooled = new InterAddress("rsf://127.0.0.1:2111/default");
            InterAddress fixed = new InterAddress("rsf://127.0.0.1:2112/default");
            updater.appendAddress("service", Collections.singletonList(pooled));
            assertTrue(provider.isDistributed());
            assertEquals(pooled, provider.get("service", "echo", new Object[0]));
            AddressProvider fixedProvider = pool.getProvider(fixed);
            assertFalse(fixedProvider.isDistributed());
            assertEquals(fixed, fixedProvider.get("service", "echo", new Object[0]));
            pool.removeBucket("service");
            assertNull(provider.get("service", "echo", new Object[0]));
            assertEquals(fixed, fixedProvider.get("service", "echo", new Object[0]));
        }
    }

    @Test
    public void connectionFailureInvalidatesDynamicAddressesButPreservesStaticOnes() throws Exception {
        try (CacheAddressPool pool = this.pool()) {
            InterAddress dynamic = new InterAddress("rsf://127.0.0.1:2111/default");
            InterAddress fixed = new InterAddress("rsf://127.0.0.1:2112/default");
            pool.appendAddress("service", Collections.singletonList(dynamic));
            pool.appendStaticAddress("service", Collections.singletonList(fixed));
            pool.invalidAddress(dynamic);
            pool.invalidAddress(fixed);
            assertEquals(Collections.singletonList(dynamic), pool.queryInvalidAddresses("service"));
            assertEquals(Collections.singletonList(fixed), pool.queryAvailableAddresses("service"));
        }
    }

    @Test
    public void repeatedStartSchedulesOnceAndCloseCancelsOnlyOwnedTask() {
        ManualScheduler scheduler = new ManualScheduler();
        Cancellable unrelated = scheduler.apply(() -> {
        }, 10L);
        try (CacheAddressPool pool = this.pool()) {
            pool.start(scheduler);
            pool.start(scheduler);
            assertEquals(2, scheduler.tasks.size());
            ScheduledTask first = scheduler.tasks.get(1);
            assertEquals(this.refreshInterval, first.delayMillis);
            first.action.run();
            assertEquals(3, scheduler.tasks.size());
            ScheduledTask next = scheduler.tasks.get(2);
            pool.close();
            pool.close();
            assertTrue(next.cancelled);
            assertFalse(((ScheduledTask) unrelated).cancelled);
            // A timer may have dequeued the callback just before cancellation.
            next.action.run();
            assertEquals(3, scheduler.tasks.size());
            assertThrows(IllegalStateException.class, () -> pool.start(scheduler));
        }
    }

    @Test
    public void closingDuringRefreshPreventsItsReschedule() throws Exception {
        ManualScheduler scheduler = new ManualScheduler();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (CacheAddressPool pool = this.pool()) {
            pool.appendAddress("service", Collections.singletonList(new InterAddress("rsf://127.0.0.1:2111/default")));
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
        try (CacheAddressPool pool = this.pool()) {
            pool.close();
            assertThrows(IllegalStateException.class, () -> pool.start(new ManualScheduler()));
        }
    }

    @Test
    public void invalidIntervalAndRejectedSchedulerAbortStartup() {
        this.refreshInterval = 0;
        try (CacheAddressPool pool = this.pool()) {
            assertThrows(IllegalArgumentException.class, () -> pool.start(new ManualScheduler()));
            assertThrows(IllegalStateException.class, () -> pool.start(new ManualScheduler()));
        }
        this.refreshInterval = 60000;
        RejectedExecutionException rejection = new RejectedExecutionException("scheduler unavailable");
        try (CacheAddressPool pool = this.pool()) {
            assertSame(rejection, assertThrows(RejectedExecutionException.class, () -> pool.start((task, delay) -> {
                throw rejection;
            })));
            assertThrows(IllegalStateException.class, () -> pool.start(new ManualScheduler()));
        }
    }

    @Test
    public void diskCacheRestoresBeforeStartReturnsAndOwnsItsMaintenance() throws Exception {
        InterAddress address = new InterAddress("rsf://127.0.0.1:2111/default");
        CacheAddressPool seed = this.pool();
        seed.appendAddress("service", address);
        try (DiskCache cache = new DiskCache(seed, this.temporary.getRoot(), 60000, 3600000)) {
            cache.storeConfig();
        }
        this.diskCache = true;
        try (CacheAddressPool pool = this.pool()) {
            pool.appendAddress("service", Collections.emptyList());
            assertNull(pool.getProvider().get("service", "echo", new Object[0]));
            ManualScheduler scheduler = new ManualScheduler();
            pool.start(scheduler);
            assertEquals(0, scheduler.tasks.size());
            assertEquals(address, pool.getProvider().get("service", "echo", new Object[0]));
        }
        this.diskCache = false;
        ManualScheduler scheduler = new ManualScheduler();
        try (CacheAddressPool pool = this.pool()) {
            pool.appendAddress("service", Collections.emptyList());
            pool.start(scheduler);
            assertNull(pool.getProvider().get("service", "echo", new Object[0]));
            assertEquals(1, scheduler.tasks.size());
        }
    }

    @Test
    public void configuredArgsKeyControlsArgumentRouting() throws Exception {
        this.argsKey = RoutingArgsKey.class;
        try (CacheAddressPool pool = this.pool()) {
            InterAddress first = new InterAddress("rsf://127.0.0.1:2111/default");
            InterAddress second = new InterAddress("rsf://127.0.0.1:2112/default");
            pool.appendAddress("service", Arrays.asList(first, second));
            assertTrue(pool.updateArgsRoute("service", "def evalAddress(id, addresses) { return [echo: [selected: ['127.0.0.1:2112']]] }"));
            assertEquals(second, pool.getProvider().get("service", "echo", new Object[] { "selected" }));
        }
    }

    @Test
    public void invalidArgsKeyFailsBeforeResourcesAreStarted() {
        this.argsKey = ArgsKey.class;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, this::pool);
        assertTrue(failure.getCause() instanceof NoSuchMethodException);
    }

    public static class RoutingArgsKey implements ArgsKey {
        @Override
        public String eval(String service, String method, Object[] args) {
            return String.valueOf(args[0]);
        }
    }

    private CacheAddressPool pool() {
        return new CacheAddressPool(this.settings());
    }

    private MetSettings settings() {
        return new Configuration().setUnitName("default").setInvalidWaitTime(30000).setRefreshCacheTime(this.refreshInterval).setLocalDiskCache(this.diskCache).setDiskCacheTimeInterval(3600000).setDataHome(this.temporary.getRoot().toPath()).setArgsKey(this.argsKey);
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

    private interface ThrowingAction {
        void run() throws Throwable;
    }

    private static <T extends Throwable> T assertThrows(Class<T> type, ThrowingAction action) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (!type.isInstance(failure)) {
                throw new AssertionError("Expected " + type.getName(), failure);
            }
            return type.cast(failure);
        }
        throw new AssertionError("Expected " + type.getName());
    }
}

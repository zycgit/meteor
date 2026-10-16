/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.zookeeper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.hasor.cobble.ExceptionUtils;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.CuratorCache;

/** ZooKeeper 节点通知驱动查询，并管理本订阅的 Curator 缓存与重试生命周期。 */
final class ZookeeperStorageWatch implements Cancellable {
    private static final Logger                          LOGGER    = LoggerFactory.getLogger(ZookeeperStorageWatch.class);
    private final        CuratorFramework                client;
    private final        Callable<Map<String, String>>   snapshot;
    private final        Runnable                        listener;
    private final        ScheduledExecutorService        executor;
    private final        Consumer<ZookeeperStorageWatch> onCancel;
    private final        List<CuratorCache>              caches    = new ArrayList<>();
    private final        AtomicBoolean                   queued    = new AtomicBoolean();
    private final        AtomicBoolean                   refresh   = new AtomicBoolean();
    private final        AtomicBoolean                   cancelled = new AtomicBoolean();
    private              Map<String, String>             previous;
    private volatile     ScheduledFuture<?>              retry;

    ZookeeperStorageWatch(CuratorFramework client, Callable<Map<String, String>> snapshot, Runnable listener, ScheduledExecutorService executor, Consumer<ZookeeperStorageWatch> onCancel) {
        this.client = client;
        this.snapshot = snapshot;
        this.listener = Objects.requireNonNull(listener, "listener");
        this.executor = executor;
        this.onCancel = onCancel;
    }

    void addOwnedCache(CuratorCache cache) {
        this.caches.add(cache);
    }

    void start() {
        for (CuratorCache cache : this.caches) {
            cache.start();
        }
        this.signal();
    }

    void refresh() {
        this.refresh.set(true);
    }

    void signal() {
        if (this.cancelled.get() || !this.queued.compareAndSet(false, true)) {
            return;
        }

        try {
            this.executor.execute(this::read);
        } catch (RejectedExecutionException stopped) {
            this.queued.set(false);
        }
    }

    private void read() {
        this.queued.set(false);
        if (this.cancelled.get()) {
            return;
        }

        ScheduledFuture<?> pending = this.retry;
        if (pending != null) {
            pending.cancel(false);
        }
        if (this.refresh.getAndSet(false)) {
            this.previous = null;
        }

        try {
            if (!this.client.getZookeeperClient().isConnected()) {
                throw new IllegalStateException("ZooKeeper is disconnected");
            }
            Map<String, String> current = this.snapshot.call();
            if (!this.cancelled.get() && !current.equals(this.previous)) {
                this.listener.run();
                this.previous = current;
            }
        } catch (Exception failure) {
            this.previous = null;
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("ZooKeeper subscription will retry\n" + ExceptionUtils.getStackTrace(failure));
            }
            if (!this.cancelled.get()) {
                try {
                    this.retry = this.executor.schedule(this::signal, 1, TimeUnit.SECONDS);
                    if (this.cancelled.get()) {
                        this.retry.cancel(false);
                    }
                } catch (RejectedExecutionException stopped) {
                    // 存储正在关闭。
                }
            }
        }
    }

    @Override
    public boolean cancel() {
        if (!this.cancelled.compareAndSet(false, true)) {
            return false;
        }

        ScheduledFuture<?> pending = this.retry;
        if (pending != null) {
            pending.cancel(false);
        }

        try {
            for (CuratorCache cache : this.caches) {
                cache.close();
            }
        } finally {
            this.caches.clear();
            this.onCancel.accept(this);
        }
        return true;
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.bootstrap;
import java.io.File;
import java.util.function.BiFunction;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.MetSettings;
import net.hasor.meteor.MetUpdater;
import net.hasor.meteor.address.AddressPool;
import net.hasor.meteor.address.DiskCache;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.address.provider.AddressProvider;
import net.hasor.meteor.address.provider.InstanceAddressProvider;
import net.hasor.meteor.address.provider.PoolAddressProvider;

/** Composes the independent address pool, disk cache and providers using RSF settings. */
public class CacheAddressPool extends AddressPool implements MetUpdater, AutoCloseable {
    private final    MetSettings                             settings;
    private final    PoolAddressProvider                     provider;
    private volatile State                                   state = State.NEW;
    private          DiskCache                               diskCache;
    private          long                                    refreshCacheMs;
    private          BiFunction<Runnable, Long, Cancellable> scheduler;
    private          Cancellable                             refreshTask;

    private enum State {
        NEW,
        STARTED,
        CLOSED
    }

    public CacheAddressPool(MetSettings settings) {
        super(settings.getUnitName(), settings.getInvalidWaitTime());
        this.settings = settings;
        this.provider = new PoolAddressProvider(this);
    }

    public AddressProvider getProvider() {
        return this.provider;
    }

    public AddressProvider getProvider(InterAddress target) {
        return new InstanceAddressProvider(target);
    }

    /** Start the disk cache, or borrow the scheduler for memory-only maintenance. */
    public synchronized void start(BiFunction<Runnable, Long, Cancellable> scheduler) {
        if (this.state == State.CLOSED) {
            throw new IllegalStateException("Address pool is closed");
        }
        if (this.state == State.STARTED) {
            return;
        }

        this.state = State.STARTED;
        try {
            this.refreshCacheMs = this.settings.getRefreshCacheTime();
            if (this.refreshCacheMs <= 0) {
                throw new IllegalArgumentException("Address refresh interval must be positive");
            }

            if (this.settings.isLocalDiskCache()) {
                File dataHome = this.settings.getDataHome().toFile();
                long diskCacheInterval = this.settings.getDiskCacheTimeInterval();
                this.diskCache = new DiskCache(this, dataHome, this.refreshCacheMs, diskCacheInterval);
                this.diskCache.restoreConfig();
            } else {
                this.scheduler = scheduler;
                this.scheduleRefresh();
            }
        } catch (RuntimeException | Error failure) {
            try {
                this.close();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    @Override
    public void close() {
        Cancellable refresh;
        DiskCache cache;
        synchronized (this) {
            if (this.state == State.CLOSED) {
                return;
            }
            this.state = State.CLOSED;
            refresh = this.refreshTask;
            cache = this.diskCache;
        }

        // 停止定时器可能等待正在执行的维护任务，不能同时占用任务所需的锁。
        try {
            if (refresh != null) {
                refresh.cancel();
            }
        } finally {
            if (cache != null) {
                cache.close();
            }
        }
    }

    private synchronized void scheduleRefresh() {
        if (this.state == State.STARTED) {
            this.refreshTask = this.scheduler.apply(this::maintainAddresses, this.refreshCacheMs);
        }
    }

    private void maintainAddresses() {
        if (this.state != State.STARTED) {
            return;
        }
        try {
            this.refreshAddressCache();
        } catch (RuntimeException failure) {
            logger.error("Address refresh failed", failure);
        } finally {
            this.scheduleRefresh();
        }
    }
}

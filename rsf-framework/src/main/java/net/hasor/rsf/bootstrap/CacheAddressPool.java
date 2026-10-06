/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.bootstrap;
import java.util.function.BiFunction;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.rsf.RsfSettings;
import net.hasor.rsf.RsfUpdater;
import net.hasor.rsf.address.AddressPool;
import net.hasor.rsf.address.DiskCache;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.address.provider.AddressProvider;
import net.hasor.rsf.address.provider.InstanceAddressProvider;
import net.hasor.rsf.address.provider.PoolAddressProvider;
import net.hasor.rsf.address.route.ArgsKey;

/** Composes the independent address pool, disk cache and providers using RSF settings. */
public class CacheAddressPool extends AddressPool implements RsfUpdater, AutoCloseable {
    private final    RsfSettings                             settings;
    private final    ArgsKey                                 routeArgsKey;
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

    public CacheAddressPool(RsfSettings settings) {
        super(settings.getUnitName(), settings.getInvalidWaitTime());
        this.settings = settings;
        this.routeArgsKey = this.createArgsKey();
        this.provider = new PoolAddressProvider(this);
    }

    @Override
    protected ArgsKey getArgsKey() {
        return this.routeArgsKey;
    }

    public AddressProvider getProvider() {
        return this.provider;
    }

    public AddressProvider getProvider(InterAddress target) {
        return new InstanceAddressProvider(target);
    }

    private ArgsKey createArgsKey() {
        Class<? extends ArgsKey> type = this.settings.getArgsKeyClass();
        try {
            return type.getConstructor().newInstance();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("Cannot construct address ArgsKey: " + type.getName(), failure);
        }
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
                this.diskCache = new DiskCache(this, this.settings.getDataHome().toFile(), this.refreshCacheMs, this.settings.getDiskCacheTimeInterval());
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
    public synchronized void close() {
        if (this.state == State.CLOSED) {
            return;
        }

        this.state = State.CLOSED;

        try {
            if (this.refreshTask != null) {
                this.refreshTask.cancel();
            }
        } finally {
            if (this.diskCache != null) {
                this.diskCache.close();
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

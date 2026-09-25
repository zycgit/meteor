/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.utils;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timer;
import io.netty.util.TimerTask;
import net.hasor.cobble.concurrent.ThreadUtils;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 *
 * @version : 2015年3月28日
 * @author 赵永春 (zyc@hasor.net)
 */
public class TimerManager implements AutoCloseable {
    @Override public void close() { this.timer.stop(); }
    private final int   defaultTimeout;
    private final Timer timer;

    public TimerManager(int defaultTimeout, ClassLoader loader) {
        this(defaultTimeout, "RSF", loader);
    }

    public TimerManager(int defaultTimeout, String name, ClassLoader loader) {
        this.defaultTimeout = defaultTimeout;
        name = Objects.requireNonNull(name);
        this.timer = new HashedWheelTimer(ThreadUtils.threadFactory(loader, name + "-Timer-%s", false));
    }

    public void atTime(TimerTask timeTask) {
        this.atTime(timeTask, this.defaultTimeout);
    }

    public void atTime(TimerTask timeTask, int timeout) {
        int reqTimeout = validateTimeout(timeout);
        this.timer.newTimeout(timeTask, reqTimeout, TimeUnit.MILLISECONDS);
    }

    private int validateTimeout(int timeout) {
        if (timeout <= 0) {
            timeout = this.defaultTimeout;
        }
        return timeout;
    }
}
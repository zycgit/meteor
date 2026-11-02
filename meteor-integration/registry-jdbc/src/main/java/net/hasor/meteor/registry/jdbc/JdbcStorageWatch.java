/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.jdbc;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledFuture;
import net.hasor.cobble.ExceptionUtils;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;

/** 通用轮询订阅；仅比较记录身份和版本，不将存储异常解释为记录删除。 */
final class JdbcStorageWatch implements Runnable, Cancellable {
    private static final Logger                        LOGGER = LoggerFactory.getLogger(JdbcStorageWatch.class);
    private final        Callable<Map<String, String>> snapshot;
    private final        Runnable                      listener;
    private              Map<String, String>           previous;
    private volatile     boolean                       cancelled;
    private              ScheduledFuture<?>            future;

    JdbcStorageWatch(Callable<Map<String, String>> snapshot, Runnable listener) {
        this.snapshot = snapshot;
        this.listener = listener;
    }

    synchronized void scheduled(ScheduledFuture<?> future) {
        this.future = future;
        if (this.cancelled) {
            future.cancel(false);
        }
    }

    @Override
    public void run() {
        if (this.cancelled) {
            return;
        }

        try {
            Map<String, String> current = this.snapshot.call();
            if (!this.cancelled && !current.equals(this.previous)) {
                this.listener.run();
                this.previous = current;
            }
        } catch (Exception failure) {
            // 恢复后即使版本未变也通知；失败的回调同样在下一轮重试。
            if (this.previous != null && LOGGER.isDebugEnabled()) {
                LOGGER.debug("Storage subscription will retry after failure\n" + ExceptionUtils.getStackTrace(failure));
            }
            this.previous = null;
        }
    }

    @Override
    public synchronized boolean cancel() {
        if (this.cancelled) {
            return false;
        }

        this.cancelled = true;
        if (this.future != null) {
            this.future.cancel(false);
        }
        return true;
    }
}

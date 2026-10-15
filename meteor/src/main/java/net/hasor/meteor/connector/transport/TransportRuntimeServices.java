/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport;
import java.util.Objects;
import java.util.function.BiFunction;
import net.hasor.cobble.concurrent.future.Cancellable;

/** Runtime services supplied to a transport without mixing them into transport configuration. */
public final class TransportRuntimeServices {
    private final ClassLoader                             classLoader;
    private final BiFunction<Runnable, Long, Cancellable> scheduler;

    public TransportRuntimeServices(ClassLoader classLoader, BiFunction<Runnable, Long, Cancellable> scheduler) {
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public ClassLoader classLoader() {
        return this.classLoader;
    }

    public Cancellable schedule(Runnable task, long delayMillis) {
        return this.scheduler.apply(task, delayMillis);
    }
}

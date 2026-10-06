/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.protocol;
import java.util.function.BiFunction;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.rsf.RsfContext;
import net.hasor.rsf.address.InterAddress;

/** Services available to wire codecs, without access to endpoint or connection management. */
public final class ProtocolContext {
    private final RsfContext                              context;
    private final BiFunction<Runnable, Long, Cancellable> scheduler;
    private final InterAddress                            bindAddress;
    private final boolean                                 outbound;

    public ProtocolContext(RsfContext context, BiFunction<Runnable, Long, Cancellable> scheduler, InterAddress bindAddress, boolean outbound) {
        this.context = context;
        this.scheduler = scheduler;
        this.bindAddress = bindAddress;
        this.outbound = outbound;
    }

    public InterAddress bindAddress() {
        return this.bindAddress;
    }

    public boolean outbound() {
        return this.outbound;
    }

    public RsfContext context() {
        return this.context;
    }

    public Cancellable schedule(Runnable action, long delayMillis) {
        return this.scheduler.apply(action, delayMillis);
    }
}

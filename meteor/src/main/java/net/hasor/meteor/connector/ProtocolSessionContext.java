/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.function.BiFunction;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;

/** Services available to wire codecs, without access to endpoint or connection management. */
public final class ProtocolSessionContext {
    private final MetContext                              metContext;
    private final BiFunction<Runnable, Long, Cancellable> scheduler;
    private final InterAddress                            bindAddress;
    private final boolean                                 outbound;
    private final RouteMountIndex                         routes;

    ProtocolSessionContext(MetContext metContext, BiFunction<Runnable, Long, Cancellable> scheduler, //
            InterAddress bindAddress, boolean outbound, RouteMountIndex routes) {
        this.metContext = metContext;
        this.scheduler = scheduler;
        this.bindAddress = bindAddress;
        this.outbound = outbound;
        this.routes = routes;
    }

    /** Active listener address, or null for an endpoint without a listener. */
    public InterAddress bindAddress() {
        return this.bindAddress;
    }

    public boolean isOutbound() {
        return this.outbound;
    }

    /** Immutable route selection prepared for this endpoint protocol. */
    public RouteMountIndex routes() {
        return this.routes;
    }

    public MetContext metContext() {
        return this.metContext;
    }

    public Cancellable schedule(Runnable action, long delayMillis) {
        return this.scheduler.apply(action, delayMillis);
    }
}

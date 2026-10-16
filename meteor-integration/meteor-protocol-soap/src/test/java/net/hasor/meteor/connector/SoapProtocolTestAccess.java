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

/** Thin test access to the package-owned protocol context constructor. */
public final class SoapProtocolTestAccess {
    private SoapProtocolTestAccess() {
    }

    public static ProtocolSessionContext context( //
            MetContext context, BiFunction<Runnable, Long, Cancellable> scheduler, //
            InterAddress bindAddress, boolean outbound) {
        return new ProtocolSessionContext(context, scheduler, bindAddress, outbound);
    }

    public static RouteSelection selection(ProtocolConfig config, String uri) {
        RouteMount mount = config.matchContext(uri);
        return new RouteSelection(config.mountId(), mount, config.options());
    }
}

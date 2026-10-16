/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.Map;
import java.util.function.BiFunction;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;

/** Thin test access to package-owned protocol assembly values. */
public final class ProtocolTestAccess {
    private ProtocolTestAccess() {
    }

    public static ProtocolConfig config(String name, String scheme, String protocol, Map<String, String> options) {
        return new ProtocolConfig(name, scheme, protocol, options);
    }

    public static ProtocolSessionContext context( //
            MetContext context, BiFunction<Runnable, Long, Cancellable> scheduler, //
            InterAddress bindAddress, boolean outbound) {
        return new ProtocolSessionContext(context, scheduler, bindAddress, outbound);
    }
}

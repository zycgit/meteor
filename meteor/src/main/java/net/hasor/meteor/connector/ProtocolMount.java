/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.Objects;
import net.hasor.meteor.connector.transport.NetworkRoute;

/** One endpoint-owned runtime mount and all values resolved for its route. */
final class ProtocolMount<M> {
    private final ProtocolConfig     config;
    private final ProtocolFactory<M> factory;
    private final NetworkRoute<M>    route;

    ProtocolMount(ProtocolConfig config, ProtocolFactory<M> factory, NetworkRoute<M> route) {
        this.config = Objects.requireNonNull(config, "config");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.route = Objects.requireNonNull(route, "route");
    }

    String mountId() {
        return this.config.mountId();
    }

    ProtocolConfig config() {
        return this.config;
    }

    ProtocolFactory<M> factory() {
        return this.factory;
    }

    NetworkRoute<M> route() {
        return this.route;
    }
}

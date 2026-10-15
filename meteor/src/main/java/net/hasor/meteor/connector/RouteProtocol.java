/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.Objects;

/** One endpoint-owned runtime mount and all values resolved for its route. */
final class RouteProtocol<M> {
    private final ProtocolConfig     config;
    private final ProtocolFactory<M> factory;

    RouteProtocol(ProtocolConfig config, ProtocolFactory<M> factory) {
        this.config = Objects.requireNonNull(config, "config");
        this.factory = Objects.requireNonNull(factory, "factory");
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
}

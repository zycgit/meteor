/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.Objects;

/** Resolved protocol configuration and provider bound to one endpoint mount. */
final class ProtocolMountBinding<M> {
    private final ProtocolConfig      config;
    private final ProtocolProvider<M> provider;
    private final RouteMountIndex     routes;

    ProtocolMountBinding(ProtocolConfig config, ProtocolProvider<M> provider, RouteMountIndex routes) {
        this.config = Objects.requireNonNull(config, "config");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.routes = routes;
    }

    ProtocolConfig config() {
        return this.config;
    }

    RouteMountIndex routes() {
        return this.routes;
    }

    ProtocolProvider<M> provider() {
        return this.provider;
    }
}

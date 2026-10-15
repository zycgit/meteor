/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.Objects;

/** Resolved protocol configuration and factory bound to one endpoint mount. */
final class ProtocolMountBinding<M> {
    private final ProtocolConfig     config;
    private final ProtocolFactory<M> factory;

    ProtocolMountBinding(ProtocolConfig config, ProtocolFactory<M> factory) {
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

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.transport;
import java.util.Map;
import net.hasor.rsf.connector.ConnectorConfig;

/** Transport-only SPI. Creating a provider does not allocate network resources. */
public interface NetworkConnectorFactory<M> {
    String name();

    Class<M> messageType();

    /** Message routes can share an accepted physical channel without closing each other. */
    default boolean sharedRoutes() {
        return false;
    }

    /** Normalize and validate routing options before any network resources are allocated. */
    default Map<String, NetworkRoute<M>> prepareRoutes(Map<String, NetworkRoute<M>> routes) {
        return routes;
    }

    NetworkConnector<M> create(ConnectorConfig config, ClassLoader loader);
}

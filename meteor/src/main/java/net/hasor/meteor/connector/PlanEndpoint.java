/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.hasor.meteor.connector.transport.TransportPlan;

/** Immutable endpoint assembly produced during connector initialization. */
final class PlanEndpoint<M> {
    private final ConnectorConfig                      config;
    private final TransportPlan<M>                     transportPlan;
    private final boolean                              sharedRoutes;
    private final Map<String, ProtocolMountBinding<M>> protocolBindings;

    PlanEndpoint(ConnectorConfig config, TransportPlan<M> transportPlan, boolean sharedRoutes, //
            Map<String, ProtocolMountBinding<M>> protocolBindings) {
        this.config = Objects.requireNonNull(config, "config");
        this.transportPlan = Objects.requireNonNull(transportPlan, "transportPlan");
        this.sharedRoutes = sharedRoutes;
        this.protocolBindings = Collections.unmodifiableMap(new LinkedHashMap<>(protocolBindings));
    }

    ConnectorConfig config() {
        return this.config;
    }

    TransportPlan<M> transportPlan() {
        return this.transportPlan;
    }

    boolean sharedRoutes() {
        return this.sharedRoutes;
    }

    Map<String, ProtocolMountBinding<M>> protocolBindings() {
        return this.protocolBindings;
    }
}

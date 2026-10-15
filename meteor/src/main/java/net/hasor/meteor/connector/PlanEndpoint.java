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
import java.util.function.Function;
import net.hasor.meteor.connector.transport.TransportPlan;

/** Immutable endpoint assembly produced during connector initialization. */
final class PlanEndpoint<M> {
    private final ConnectorConfig                      config;
    private final TransportPlan<M>                     transportPlan;
    private final boolean                              sharedRoutes;
    private final Map<String, ProtocolMountBinding<M>> protocolBindings;
    private final PlanRouteTable<M>                    routeTablePlan;

    PlanEndpoint(ConnectorConfig config, TransportPlan<M> transportPlan, boolean sharedRoutes, //
            Map<String, ProtocolMountBinding<M>> protocolBindings, PlanRouteTable<M> routeTablePlan) {
        this.config = Objects.requireNonNull(config, "config");
        this.transportPlan = Objects.requireNonNull(transportPlan, "transportPlan");
        this.sharedRoutes = sharedRoutes;
        this.protocolBindings = Collections.unmodifiableMap(new LinkedHashMap<>(protocolBindings));
        this.routeTablePlan = Objects.requireNonNull(routeTablePlan, "routeTablePlan");
    }

    ConnectorEndpoint<M> activate(ConnectorManager manager, RouteServiceRegistry registry) {
        Function<String, RouteSelection> exactResolver = //
                registry.addEndpoint(this.config, this.protocolBindings.values());
        RouteTable<M> routeTable = this.routeTablePlan.activate(exactResolver);
        return new ConnectorEndpoint<>(this.config, this.transportPlan, this.sharedRoutes, //
                this.protocolBindings, routeTable, manager);
    }
}

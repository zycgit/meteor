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
final class PlanConnector<M> {
    private final ConnectorConfig               config;
    private final TransportPlan<M>              transport;
    private final boolean                       sharedRoutes;
    private final Map<String, RouteProtocol<M>> protocols;
    private final PlanRoutes<M>                 routes;

    PlanConnector(ConnectorConfig config, TransportPlan<M> transport, boolean sharedRoutes, //
            Map<String, RouteProtocol<M>> protocols, PlanRoutes<M> routes) {
        this.config = Objects.requireNonNull(config, "config");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.sharedRoutes = sharedRoutes;
        this.protocols = Collections.unmodifiableMap(new LinkedHashMap<>(protocols));
        this.routes = Objects.requireNonNull(routes, "routes");
    }

    ConnectorEndpoint<M> activate(ConnectorManager manager, RouteRegistry registry) {
        Function<String, RouteSelection> exactResolver = registry.addEndpoint(this.config, this.protocols.values());
        RouteTable<M> routeTable = this.routes.activate(exactResolver);
        return new ConnectorEndpoint<>(this.config, this.transport, this.sharedRoutes, //
                this.protocols, routeTable, manager);
    }
}
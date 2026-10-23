/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.Map;
import java.util.function.Function;

/** One runtime route with protocol options, context mounts and an optional transport protocol probe. */
public final class RouteDefinition<M> {
    private final Map<String, String>           options;
    private final Function<M, RouteProbeResult> protocolProbe;
    private final RouteMountIndex               mountIndex;

    RouteDefinition(Map<String, String> options, RouteMountIndex mountIndex, Function<M, RouteProbeResult> protocolProbe) {
        this.options = Map.copyOf(options);
        this.protocolProbe = protocolProbe;
        this.mountIndex = mountIndex;
    }

    public Map<String, String> options() {
        return this.options;
    }

    /** Transport message probe, or null when the route does not require content probing. */
    public Function<M, RouteProbeResult> protocolProbe() {
        return this.protocolProbe;
    }

    RouteMountIndex mountIndex() {
        return this.mountIndex;
    }
}

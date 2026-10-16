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

/** Thin test access that activates package-private route planning results. */
public final class RouteTestAccess {
    private RouteTestAccess() {
    }

    public static <M> RouteTable<M> activate(Map<String, RouteDefinition<M>> definitions, //
            Function<String, RouteSelection> exactResolver) {
        return new PlanRouteTable<>(definitions).activate(exactResolver);
    }

    public static <M> RouteDefinition<M> definition(Map<String, String> options, RouteMountIndex mountIndex, //
            Function<M, RouteProbeResult> probe) {
        return new RouteDefinition<>(options, mountIndex, probe);
    }
}

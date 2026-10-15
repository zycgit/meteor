/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.*;
import java.util.function.Function;

/** Startup result that can activate an immutable runtime route table. */
final class PlanRouteTable<M> {
    private final Map<String, RouteDefinition<M>> definitions;
    private final List<RouteSelection>             prefixes;

    PlanRouteTable(Map<String, RouteDefinition<M>> definitions) {
        this.definitions = Collections.unmodifiableMap(new LinkedHashMap<>(definitions));
        this.prefixes = this.compilePrefixes(this.definitions);
    }

    RouteTable<M> activate(Function<String, RouteSelection> exactResolver) {
        return new RouteTable<>(this.definitions, this.prefixes, exactResolver);
    }

    private List<RouteSelection> compilePrefixes(Map<String, RouteDefinition<M>> definitions) {
        List<RouteSelection> prefixes = new ArrayList<>();
        for (Map.Entry<String, RouteDefinition<M>> entry : definitions.entrySet()) {
            RouteDefinition<M> route = entry.getValue();
            if (route.mounts() == null) {
                continue;
            }

            for (RouteMount mount : route.mounts().mounts()) {
                prefixes.add(new RouteSelection(entry.getKey(), mount, route.options()));
            }
        }

        prefixes.sort(Comparator.comparingInt((RouteSelection route) -> route.mount().path().length()).reversed());
        return List.copyOf(prefixes);
    }
}

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
final class PlanRoutes<M> {
    private final Map<String, RouteEntry<M>> entries;
    private final List<RouteSelection>       prefixes;

    PlanRoutes(Map<String, RouteEntry<M>> entries) {
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
        this.prefixes = this.compilePrefixes(this.entries);
    }

    RouteTable<M> activate(Function<String, RouteSelection> exactResolver) {
        return new RouteTableImpl<>(this.entries, this.prefixes, exactResolver);
    }

    private List<RouteSelection> compilePrefixes(Map<String, RouteEntry<M>> routes) {
        List<RouteSelection> prefixes = new ArrayList<>();
        for (Map.Entry<String, RouteEntry<M>> entry : routes.entrySet()) {
            RouteEntry<M> route = entry.getValue();
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

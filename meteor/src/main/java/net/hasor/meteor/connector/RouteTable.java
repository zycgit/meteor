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

/** Immutable runtime route table with static path indexes computed at construction. */
public final class RouteTable<M> {
    private final Map<String, RouteDefinition<M>>  definitions;
    private final List<RouteSelection>             prefixes;
    private final Function<String, RouteSelection> exactResolver;

    RouteTable(Map<String, RouteDefinition<M>> definitions, Function<String, RouteSelection> exactResolver) {
        this.definitions = Collections.unmodifiableMap(new LinkedHashMap<>(definitions));
        this.prefixes = this.compilePrefixes();
        this.exactResolver = exactResolver;
    }

    private List<RouteSelection> compilePrefixes() {
        List<RouteSelection> prefixes = new ArrayList<>();
        for (Map.Entry<String, RouteDefinition<M>> entry : this.definitions.entrySet()) {
            RouteDefinition<M> route = entry.getValue();
            RouteMountIndex mounts = route.mountIndex();
            if (mounts == null) {
                continue;
            }

            for (RouteMount mount : mounts.mounts()) {
                prefixes.add(new RouteSelection(entry.getKey(), mount, route.options()));
            }
        }

        // 最长路径优先；相同长度保留挂载声明顺序。
        prefixes.sort(Comparator.comparingInt((RouteSelection route) -> route.mount().path().length()).reversed());
        return List.copyOf(prefixes);
    }

    /** Return immutable protocol entries indexed by their protocol names. */
    public Map<String, RouteDefinition<M>> definitions() {
        return this.definitions;
    }

    /** Return whether the named protocol belongs to this endpoint. */
    public boolean contains(String protocolName) {
        return protocolName != null && this.definitions.containsKey(protocolName.toLowerCase(Locale.ROOT));
    }

    /** Select an HTTP-style route from its request URI. */
    public RouteSelection select(String uri) {
        RouteSelection exact = this.exactResolver == null ? null : this.exactResolver.apply(uri);
        if (exact != null) {
            return exact;
        }

        int query = uri.indexOf('?');
        int end = query < 0 ? uri.length() : query;
        for (RouteSelection route : this.prefixes) {
            if (route.matches(uri, end)) {
                return route;
            }
        }
        return null;
    }

    /** Validate a protocol name or select the only configured protocol. */
    public String resolveProtocol(String protocolName) {
        if (protocolName != null) {
            String normalized = protocolName.toLowerCase(Locale.ROOT);
            if (!this.definitions.containsKey(normalized)) {
                throw new IllegalArgumentException("Unknown protocol: " + protocolName);
            }

            return normalized;
        }

        if (this.definitions.size() == 1) {
            return this.definitions.keySet().iterator().next();
        }

        return null;
    }
}

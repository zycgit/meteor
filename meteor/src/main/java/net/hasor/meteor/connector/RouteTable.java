/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Immutable runtime route table consumed by transports after startup planning has completed. */
public final class RouteTable<M> {
    private final Map<String, RouteDefinition<M>> definitions;
    private final List<RouteSelection>             prefixes;
    private final Function<String, RouteSelection> exactResolver;

    RouteTable(Map<String, RouteDefinition<M>> definitions, List<RouteSelection> prefixes, //
            Function<String, RouteSelection> exactResolver) {
        this.definitions = definitions;
        this.prefixes = prefixes;
        this.exactResolver = exactResolver;
    }

    /** Return immutable protocol entries indexed by their runtime mount ID. */
    public Map<String, RouteDefinition<M>> definitions() {
        return this.definitions;
    }

    /** Return whether the supplied runtime mount ID belongs to this endpoint. */
    public boolean contains(String mountId) {
        return this.definitions.containsKey(mountId);
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

    /** Validate an explicit mount or resolve the only mount when no selection was supplied. */
    public String resolveMountId(String mountId) {
        if (mountId != null) {
            if (!this.definitions.containsKey(mountId)) {
                throw new IllegalArgumentException("Unknown protocol mount: " + mountId);
            }

            return mountId;
        }

        if (this.definitions.size() == 1) {
            return this.definitions.keySet().iterator().next();
        }

        return null;
    }
}

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

/** Default runtime route table activated from a startup plan. */
final class RouteTableImpl<M> implements RouteTable<M> {
    private final Map<String, RouteEntry<M>>       entries;
    private final List<RouteSelection>             prefixes;
    private final Function<String, RouteSelection> exactResolver;

    RouteTableImpl(Map<String, RouteEntry<M>> entries, List<RouteSelection> prefixes, Function<String, RouteSelection> exactResolver) {
        this.entries = entries;
        this.prefixes = prefixes;
        this.exactResolver = exactResolver;
    }

    @Override
    public Map<String, RouteEntry<M>> entries() {
        return this.entries;
    }

    @Override
    public boolean contains(String mountId) {
        return this.entries.containsKey(mountId);
    }

    @Override
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

    @Override
    public String fixedMount(String mountId) {
        if (mountId != null) {
            if (!this.entries.containsKey(mountId)) {
                throw new IllegalArgumentException("Unknown protocol mount: " + mountId);
            }

            return mountId;
        }

        if (this.entries.size() == 1) {
            return this.entries.keySet().iterator().next();
        }

        return null;
    }
}

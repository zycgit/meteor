/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.*;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.connector.transport.ContextRoute;

/** Endpoint path index with fixed protocol mounts and dynamic exact service routes. */
final class ContextRoutes {
    private final    List<ContextRoute>                      prefixes;
    private final    Map<MetBindInfo<?>, List<ContextRoute>> registrations = new IdentityHashMap<>();
    private volatile Map<String, ContextRoute>               exact         = Collections.emptyMap();

    ContextRoutes(Collection<ContextRoute> prefixes) {
        List<ContextRoute> ordered = new ArrayList<>(prefixes);
        ordered.sort(Comparator.comparingInt((ContextRoute route) -> route.mount().path().length()).reversed());
        this.prefixes = Collections.unmodifiableList(ordered);
    }

    /** Validate exact path conflicts before registration. */
    void validate(Collection<ContextRoute> routes) {
        Map<String, ContextRoute> selected = new HashMap<>(this.exact);
        for (ContextRoute route : routes) {
            add(selected, route);
        }
    }

    /** Publish a complete immutable exact-route snapshot. */
    synchronized void register(MetBindInfo<?> service, List<ContextRoute> routes) {
        if (routes.isEmpty()) {
            return;
        }

        Map<String, ContextRoute> selected = new HashMap<>(this.exact);
        for (ContextRoute route : routes) {
            add(selected, route);
        }
        this.registrations.put(service, List.copyOf(routes));
        this.exact = Collections.unmodifiableMap(selected);
    }

    /** Remove routes registered by the supplied service identity. */
    synchronized void remove(MetBindInfo<?> service) {
        if (this.registrations.remove(service) == null) {
            return;
        }

        Map<String, ContextRoute> selected = new HashMap<>();
        for (List<ContextRoute> routes : this.registrations.values()) {
            for (ContextRoute route : routes) {
                add(selected, route);
            }
        }
        this.exact = Collections.unmodifiableMap(selected);
    }

    private static void add(Map<String, ContextRoute> selected, ContextRoute route) {
        ContextRoute previous = selected.putIfAbsent(route.mount().path(), route);
        if (previous != null && !StringUtils.equals(previous.mountId(), route.mountId())) {
            throw new IllegalArgumentException("Conflicting exact service path: " + route.mount().path());
        }
    }

    /** Prefer exact service paths, then select the longest configured directory prefix. */
    ContextRoute match(String uri) {
        int query = uri.indexOf('?');
        int end = query < 0 ? uri.length() : query;
        Map<String, ContextRoute> exact = this.exact;
        if (!exact.isEmpty()) {
            ContextRoute selected = exact.get(query < 0 ? uri : uri.substring(0, end));
            if (selected != null) {
                return selected;
            }
        }
        for (ContextRoute route : this.prefixes) {
            if (route.matches(uri, end)) {
                return route;
            }
        }
        return null;
    }
}

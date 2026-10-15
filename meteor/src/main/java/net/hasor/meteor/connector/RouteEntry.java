/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** One runtime route with protocol options, context mounts and a non-consuming message probe. */
public final class RouteEntry<M> {
    private final Map<String, String>     options;
    private final Function<M, RouteMatch> probe;
    private final RouteMounts             mounts;

    RouteEntry(Map<String, String> options, RouteMounts mounts, Function<M, RouteMatch> probe) {
        this.options = Map.copyOf(options);
        this.probe = Objects.requireNonNull(probe, "probe");
        this.mounts = mounts;
    }

    public Map<String, String> options() {
        return this.options;
    }

    public RouteMatch match(M message) {
        return this.probe.apply(message);
    }

    RouteMounts mounts() {
        return this.mounts;
    }
}
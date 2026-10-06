/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport;
import java.util.Map;
import java.util.function.Function;

/** Route options and a non-consuming message probe, independent of application payloads. */
public final class NetworkRoute<M> {
    private final Map<String, String>     options;
    private final Function<M, RouteMatch> probe;

    public NetworkRoute(Map<String, String> options, Function<M, RouteMatch> probe) {
        this.options = Map.copyOf(options);
        this.probe = probe;
    }

    public Map<String, String> options() {
        return this.options;
    }

    public RouteMatch match(M message) {
        return this.probe.apply(message);
    }

    public NetworkRoute<M> withOptions(Map<String, String> options) {
        return new NetworkRoute<>(options, this.probe);
    }
}

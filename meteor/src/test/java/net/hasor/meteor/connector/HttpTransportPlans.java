/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.LinkedHashMap;
import java.util.Map;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;

/** Builds the compiled route view used by direct HTTP transport tests. */
public final class HttpTransportPlans {
    private HttpTransportPlans() {
    }

    public static RouteTable<HttpExchange> routes(ConnectorConfig config) {
        Map<String, RouteDefinition<HttpExchange>> routes = new LinkedHashMap<>();
        for (ProtocolConfig protocol : config.protocols()) {
            Map<String, String> options = new LinkedHashMap<>(config.options());
            options.putAll(protocol.options());
            RouteMountIndex mounts = new RouteMountIndex(config.name(), protocol);
            RouteDefinition<HttpExchange> route = new RouteDefinition<>(options, mounts, //
                    message -> RouteProbeResult.REJECT);
            routes.put(protocol.name(), route);
        }
        return new RouteTable<>(routes, null);
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.transport.NetworkConnector;
import net.hasor.meteor.connector.transport.NetworkConnectorFactory;
import net.hasor.meteor.connector.transport.NetworkRoute;

public final class HttpConnectorFactory implements NetworkConnectorFactory<HttpExchange> {
    public Map<String, NetworkRoute<HttpExchange>> prepareRoutes(Map<String, NetworkRoute<HttpExchange>> routes) {
        Map<String, NetworkRoute<HttpExchange>> prepared = new LinkedHashMap<>();
        Set<String> paths = new HashSet<>();
        routes.forEach((name, route) -> {
            Map<String, String> options = new LinkedHashMap<>(route.options());
            String path = options.getOrDefault("contextPath", routes.size() == 1 ? "/" : "/" + name);
            if (!path.startsWith("/")) {
                throw new IllegalArgumentException("Invalid HTTP mount: " + path);
            }

            path = path.replaceAll("/+$", "");
            path = path.isEmpty() ? "/" : path;
            if (!paths.add(path)) {
                throw new IllegalArgumentException("Duplicate HTTP mount: " + path);
            }

            options.put("contextPath", path);
            prepared.put(name, route.withOptions(options));
        });
        return prepared;
    }

    public boolean sharedRoutes() {
        return true;
    }

    public String name() {
        return "http";
    }

    public Class<HttpExchange> messageType() {
        return HttpExchange.class;
    }

    public NetworkConnector<HttpExchange> create(ConnectorConfig config, ClassLoader loader) {
        return new HttpConnector(config, loader);
    }
}

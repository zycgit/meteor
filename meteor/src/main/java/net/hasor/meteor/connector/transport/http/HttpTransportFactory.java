/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.hasor.meteor.connector.transport.TransportConfig;
import net.hasor.meteor.connector.transport.TransportFactory;
import net.hasor.meteor.connector.transport.TransportPlan;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.utils.ContextPathUtils;

public final class HttpTransportFactory implements TransportFactory<HttpExchange> {
    public void configRoutes(Map<String, Map<String, String>> routes) {
        Set<String> paths = new HashSet<>();
        for (Map.Entry<String, Map<String, String>> route : routes.entrySet()) {
            Map<String, String> options = route.getValue();
            String defaultPath = routes.size() == 1 ? "/" : "/" + route.getKey();
            String path = ContextPathUtils.normalize(options.getOrDefault("contextPath", defaultPath));
            options.put("contextPath", path);

            for (String mount : ContextPathUtils.contextPaths(options)) {
                if (!paths.add(mount)) {
                    throw new IllegalArgumentException("Duplicate HTTP directory mount: " + mount);
                }
            }
        }
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

    public TransportPlan<HttpExchange> prepare(TransportConfig config, ClassLoader loader) {
        return TransportPlan.withPipeline(config, loader, (pipeline, services) -> {
            return new HttpConnector(config, pipeline, services.classLoader());
        });
    }
}

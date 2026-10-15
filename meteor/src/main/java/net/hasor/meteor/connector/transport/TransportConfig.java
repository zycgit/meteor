/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.hasor.meteor.address.InterAddress;

/** Immutable endpoint settings exposed to a transport implementation. */
public final class TransportConfig {
    private final String                           name;
    private final InterAddress                     address;
    private final boolean                          bindEnabled;
    private final Map<String, String>              options;
    private final Map<String, Map<String, String>> routeOptions;

    public TransportConfig(String name, InterAddress address, boolean bindEnabled, Map<String, String> options, //
            Map<String, Map<String, String>> routeOptions) {
        this.name = Objects.requireNonNull(name, "name");
        this.address = Objects.requireNonNull(address, "address");
        this.bindEnabled = bindEnabled;
        this.options = Collections.unmodifiableMap(new LinkedHashMap<>(options));

        Map<String, Map<String, String>> routes = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> route : routeOptions.entrySet()) {
            Map<String, String> routeValue = new LinkedHashMap<>(route.getValue());
            routes.put(route.getKey(), Collections.unmodifiableMap(routeValue));
        }
        this.routeOptions = Collections.unmodifiableMap(routes);
    }

    public String name() {
        return this.name;
    }

    public InterAddress address() {
        return this.address;
    }

    public boolean bindEnabled() {
        return this.bindEnabled;
    }

    public Map<String, String> options() {
        return this.options;
    }

    public Map<String, Map<String, String>> routeOptions() {
        return this.routeOptions;
    }

    public String option(String key, String fallback) {
        return this.options.getOrDefault(key, fallback);
    }

    public int integer(String key, int fallback) {
        int value = Integer.parseInt(this.option(key, Integer.toString(fallback)));
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be positive");
        }

        return value;
    }

    public int connectTimeout() {
        return this.integer("connectTimeout", 3000);
    }
}

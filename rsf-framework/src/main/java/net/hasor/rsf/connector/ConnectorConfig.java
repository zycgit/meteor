/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector;
import java.util.*;
import net.hasor.rsf.address.InterAddress;

/** Immutable configuration for a named connector and its local listening address. */
public final class ConnectorConfig {
    private final String              name;
    private final String              listenType;
    private final InterAddress        address;
    private final Map<String, String> options;

    public ConnectorConfig(String name, InterAddress address, Map<String, String> options) {
        this.name = Objects.requireNonNull(name, "name");
        this.address = Objects.requireNonNull(address, "address");
        this.options = Collections.unmodifiableMap(new HashMap<>(options));
        String type = this.options.get("listenType");
        if (type == null || type.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing listenType for endpoint: " + name);
        }
        this.listenType = type.toLowerCase(Locale.ROOT);
    }

    public String name() {
        return this.name;
    }

    /** Local listening address. Remote destinations are supplied to connect separately. */
    public InterAddress address() {
        return this.address;
    }

    /** Copies this configuration for another operation address, keeping its name and options. */
    public ConnectorConfig withAddress(InterAddress address) {
        return new ConnectorConfig(this.name, address, this.options);
    }

    /** Transport type used to select a factory and start the requested operation. */
    public String listenType() {
        return this.listenType;
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

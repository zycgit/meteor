/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.hasor.rsf.address.InterAddress;

/** Immutable configuration for a named connector and one bind or connect operation. */
public final class ConnectorConfig {
    private final String              name;
    private final InterAddress        address;
    private final Map<String, String> options;

    public ConnectorConfig(String name, InterAddress address, Map<String, String> options) {
        this.name = Objects.requireNonNull(name, "name");
        this.address = Objects.requireNonNull(address, "address");
        this.options = Collections.unmodifiableMap(new HashMap<>(options));
    }

    public String name() {
        return this.name;
    }

    /** Local listening address for bind; remote destination for connect. */
    public InterAddress address() {
        return this.address;
    }

    /** Copies this configuration for another operation address, keeping its name and options. */
    public ConnectorConfig withAddress(InterAddress address) {
        return new ConnectorConfig(this.name, address, this.options);
    }

    /** Transport type used to select a factory and start the requested operation. */
    public String listenType() {
        String type = this.options.get("listenType");
        if (type == null || type.trim().isEmpty()) {
            throw new IllegalStateException("Missing listenType for endpoint: " + this.name);
        }
        return type;
    }

    public String option(String key, String fallback) {
        return this.options.getOrDefault(key, fallback);
    }

    public int integer(String key, int fallback) {
        int value = Integer.parseInt(option(key, Integer.toString(fallback)));
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be positive");
        }

        return value;
    }

    public int connectTimeout() {
        return integer("connectTimeout", 3000);
    }
}

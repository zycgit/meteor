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
    private final String                      name;
    private final String                      listenType;
    private final boolean                     bindEnabled;
    private final InterAddress                address;
    private final Map<String, String>         options;
    private final Map<String, ProtocolConfig> protocols;

    public ConnectorConfig(String name, InterAddress address, Map<String, String> options, Collection<ProtocolConfig> protocols, boolean bindEnabled) {
        this.name = Objects.requireNonNull(name, "name");
        this.bindEnabled = bindEnabled;
        this.address = Objects.requireNonNull(address, "address");
        this.options = Collections.unmodifiableMap(new HashMap<>(options));
        String type = this.options.get("listenType");
        if (type == null || type.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing listenType for endpoint: " + name);
        }
        this.listenType = type.toLowerCase(Locale.ROOT);

        Map<String, ProtocolConfig> routes = new LinkedHashMap<>();
        for (ProtocolConfig protocol : protocols) {
            if (routes.putIfAbsent(protocol.scheme(), protocol) != null) {
                throw new IllegalArgumentException("Duplicate protocol scheme on endpoint: " + protocol.scheme());
            }
        }

        if (routes.isEmpty()) {
            throw new IllegalArgumentException("Endpoint requires at least one protocol");
        }
        this.protocols = Collections.unmodifiableMap(routes);
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
        return new ConnectorConfig(this.name, address, this.options, this.protocols.values(), this.bindEnabled);
    }

    /** Whether this endpoint has an explicitly configured listener. */
    public boolean bindEnabled() {
        return this.bindEnabled;
    }

    public Map<String, String> options() {
        return this.options;
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

    public Collection<ProtocolConfig> protocols() {
        return this.protocols.values();
    }

    public ProtocolConfig protocol(String scheme) {
        return this.protocols.get(scheme.toLowerCase(Locale.ROOT));
    }

    /** Effective options for a protocol, retaining endpoint resource limits. */
    public ProtocolConfig forProtocol(ProtocolConfig protocol) {
        Map<String, String> merged = new LinkedHashMap<>(this.options);
        merged.putAll(protocol.options());
        return new ProtocolConfig(protocol.name(), protocol.scheme(), protocol.protocol(), merged);
    }

    public int connectTimeout() {
        return this.integer("connectTimeout", 3000);
    }
}

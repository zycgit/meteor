/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.*;
import net.hasor.meteor.address.InterAddress;

/** 固定的连接端点及协议挂载，协议参数在启动前完成准备。 */
public final class ConnectorConfig {
    private final String                      name;
    private final boolean                     bindEnabled;
    private final InterAddress                address;
    private final Map<String, String>         options;
    private final Map<String, ProtocolConfig> protocols;

    public ConnectorConfig(String name, InterAddress address, Map<String, String> options, //
            Collection<ProtocolConfig> protocols, boolean bindEnabled) {
        this.name = Objects.requireNonNull(name, "name");
        this.bindEnabled = bindEnabled;
        this.address = Objects.requireNonNull(address, "address");
        this.options = Collections.unmodifiableMap(new HashMap<>(options));

        Map<String, ProtocolConfig> routes = new LinkedHashMap<>();
        for (ProtocolConfig protocol : protocols) {
            ProtocolConfig previous = routes.putIfAbsent(protocol.name(), protocol.snapshot());
            if (previous != null) {
                throw new IllegalArgumentException(//
                        "Duplicate protocol on endpoint " + name + ": " + protocol.name());
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

    /** Whether this endpoint has an explicitly configured listener. */
    public boolean bindEnabled() {
        return this.bindEnabled;
    }

    public Map<String, String> options() {
        return this.options;
    }

    /** Transport type used to select a factory and start the requested operation. */
    public String transportType() {
        return this.address.getSchema();
    }

    public Collection<ProtocolConfig> protocols() {
        return this.protocols.values();
    }

    /** Resolve a protocol by its registered name. */
    public ProtocolConfig protocol(String name) {
        return name == null ? null : this.protocols.get(name.toLowerCase(Locale.ROOT));
    }

}
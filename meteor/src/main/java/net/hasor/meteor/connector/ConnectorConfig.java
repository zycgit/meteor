/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.*;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.address.InterAddress;

/** 固定的连接端点及协议挂载，协议参数在启动前完成准备。 */
public final class ConnectorConfig {
    private final String                      name;
    private final String                      listenType;
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
        String type = this.options.get("listenType");
        if (type == null || type.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing listenType for endpoint: " + name);
        }
        this.listenType = type.toLowerCase(Locale.ROOT);

        Map<String, ProtocolConfig> routes = new LinkedHashMap<>();
        Set<String> logicalNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        int ordinal = 0;
        for (ProtocolConfig protocol : protocols) {
            if (!logicalNames.add(protocol.name())) {
                throw new IllegalArgumentException(//
                        "Duplicate logical protocol on endpoint " + name + ": " + protocol.name());
            }
            String mountId = this.name + "#" + ordinal++;
            routes.put(mountId, protocol.mounted(mountId));
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
    public String listenType() {
        return this.listenType;
    }

    String option(String key, String fallback) {
        return this.options.getOrDefault(key, fallback);
    }

    public Collection<ProtocolConfig> protocols() {
        return this.protocols.values();
    }

    /** Endpoint-local protocol mounts keyed by their opaque runtime route identity. */
    public Map<String, ProtocolConfig> mounts() {
        return this.protocols;
    }

    public ProtocolConfig mount(String mountId) {
        return this.protocols.get(mountId);
    }

    /** Resolve an exact mount first, then a logical protocol and finally its published scheme alias. */
    public ProtocolConfig protocol(String selector) {
        ProtocolConfig mounted = this.mount(selector);
        if (mounted != null) {
            return mounted;
        }

        ProtocolConfig selected = this.select(selector, true);
        return selected != null ? selected : this.select(selector, false);
    }

    private ProtocolConfig select(String selector, boolean logicalName) {
        ProtocolConfig selected = null;
        for (ProtocolConfig protocol : this.protocols.values()) {
            boolean matches = logicalName ? StringUtils.equalsIgnoreCase(protocol.name(), selector) : //
                    StringUtils.equalsIgnoreCase(protocol.scheme(), selector);
            if (!matches) {
                continue;
            }
            if (selected != null) {
                throw new IllegalArgumentException("Ambiguous protocol mount on endpoint: " + selector);
            }
            selected = protocol;
        }
        return selected;
    }

}

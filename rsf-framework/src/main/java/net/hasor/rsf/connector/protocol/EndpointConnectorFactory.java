/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.protocol;
import java.util.*;
import net.hasor.cobble.StringUtils;
import net.hasor.rsf.connector.*;
import net.hasor.rsf.connector.transport.NetworkConnectorFactory;
import net.hasor.rsf.connector.transport.NetworkRoute;

/** Composes independently discovered transports and protocols. No concrete implementation is referenced here. */
public final class EndpointConnectorFactory implements RsfConnectorFactory {
    private final Map<String, NetworkConnectorFactory<?>> transports = new LinkedHashMap<>();
    private final Map<String, ProtocolFactory<?>>         protocols  = new LinkedHashMap<>();

    public Collection<String> listenTypes(ClassLoader loader) {
        Map<String, NetworkConnectorFactory<?>> networks = new LinkedHashMap<>();
        for (NetworkConnectorFactory<?> factory : ServiceLoader.load(NetworkConnectorFactory.class, loader)) {
            put(networks, factory.name(), factory, "transport");
        }

        Map<String, ProtocolFactory<?>> codecs = new LinkedHashMap<>();
        for (ProtocolFactory<?> factory : ServiceLoader.load(ProtocolFactory.class, loader)) {
            put(codecs, factory.name(), factory, "protocol");
        }

        this.transports.clear();
        this.transports.putAll(networks);
        this.protocols.clear();
        this.protocols.putAll(codecs);
        return networks.keySet();
    }

    private static <T> void put(Map<String, T> registry, String name, T factory, String kind) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Missing " + kind + " SPI name");
        }

        if (registry.putIfAbsent(name.toLowerCase(Locale.ROOT), factory) != null) {
            throw new IllegalStateException("Duplicate " + kind + " SPI name: " + name);
        }
    }

    public RsfConnector create(ConnectorConfig config, ConnectorManager manager) {
        NetworkConnectorFactory<?> transport = this.transports.get(config.listenType());
        if (transport == null) {
            throw new IllegalArgumentException("Unknown transport: " + config.listenType());
        } else {
            return this.assemble(config, manager, transport);
        }
    }

    private <M> RsfConnector assemble(ConnectorConfig config, ConnectorManager manager, NetworkConnectorFactory<M> transport) {
        Map<String, ProtocolFactory<M>> mounted = new LinkedHashMap<>();
        Map<String, NetworkRoute<M>> routes = new LinkedHashMap<>();
        for (ProtocolConfig mount : config.protocols()) {
            ProtocolFactory<M> protocol = this.resolve(mount, transport);
            ProtocolConfig effective = config.forProtocol(mount);
            mounted.put(mount.scheme(), protocol);
            routes.put(mount.scheme(), new NetworkRoute<>(effective.options(), message -> protocol.probe(effective, message)));
        }

        Map<String, NetworkRoute<M>> prepared = Map.copyOf(transport.prepareRoutes(routes));
        return new EndpointConnector<>(config, manager, transport, Map.copyOf(mounted), prepared);
    }

    @SuppressWarnings("unchecked")
    private <M> ProtocolFactory<M> resolve(ProtocolConfig config, NetworkConnectorFactory<M> transport) {
        ProtocolFactory<?> protocol = this.protocols.get(config.protocol());
        if (protocol == null) {
            throw new IllegalArgumentException("Unknown protocol: " + config.protocol());
        }
        if (!StringUtils.equalsIgnoreCase(transport.name(), protocol.transport())) {
            throw new IllegalArgumentException("Protocol " + protocol.name() + " requires transport " + protocol.transport() + ", endpoint uses " + transport.name());
        }
        if (protocol.messageType() != transport.messageType()) {
            throw new IllegalArgumentException("Incompatible transport message type for protocol: " + protocol.name());
        }

        // The SPI message contracts were checked before crossing the generic registry boundary.
        return (ProtocolFactory<M>) protocol;
    }
}
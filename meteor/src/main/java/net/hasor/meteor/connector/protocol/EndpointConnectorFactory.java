/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.protocol;
import java.util.*;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.connector.transport.NetworkConnectorFactory;
import net.hasor.meteor.connector.transport.NetworkRoute;

/** Composes independently discovered transports and protocols. No concrete implementation is referenced here. */
public final class EndpointConnectorFactory implements MetConnectorFactory {
    private final Map<String, NetworkConnectorFactory<?>> transports = new LinkedHashMap<>();
    private final Map<String, ProtocolFactory<?>>         protocols  = new LinkedHashMap<>();
    private       ClassLoader                             loader;

    public Collection<String> listenTypes(ClassLoader loader) {
        if (this.loader == loader) {
            return Collections.unmodifiableSet(this.transports.keySet());
        }
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
        this.loader = loader;
        return Collections.unmodifiableSet(this.transports.keySet());
    }

    /** Resolve a user selection to the immutable identity declared by its SPI provider. */
    public ProtocolConfig protocol(String name, Map<String, String> options) {
        ProtocolFactory<?> factory = this.protocols.get(name.toLowerCase(Locale.ROOT));
        if (factory == null) {
            throw new IllegalArgumentException("Unknown protocol: " + name);
        }
        return new ProtocolConfig(factory.name(), factory.scheme(), factory.name(), options);
    }

    /** Validate and normalize an endpoint without constructing network resources. */
    public void prepare(ConnectorConfig config) {
        NetworkConnectorFactory<?> transport = this.transports.get(config.listenType());
        if (transport == null) {
            throw new IllegalArgumentException("Unknown transport: " + config.listenType());
        }
        this.prepare(config, transport);
    }

    private <M> void prepare(ConnectorConfig config, NetworkConnectorFactory<M> transport) {
        // 参数优先级：协议显式配置 > 端点公共参数 > 协议默认值。
        Map<String, Map<String, String>> routes = new LinkedHashMap<>();
        for (ProtocolConfig mount : config.protocols()) {
            ProtocolFactory<M> protocol = this.resolve(mount, transport);
            Map<String, String> options = new LinkedHashMap<>(config.options());
            options.putAll(mount.options());
            options.putIfAbsent("serializeType", protocol.defaultSerializeType());
            routes.put(mount.scheme(), options);
        }

        // 传输层只整理参数；校验完成后更新原来的协议配置。
        transport.configRoutes(routes);

        for (ProtocolConfig mount : config.protocols()) {
            mount.setOptions(routes.get(mount.scheme()));
        }
    }

    private static <T> void put(Map<String, T> registry, String name, T factory, String kind) {
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("Missing " + kind + " SPI name");
        }

        if (registry.putIfAbsent(name.toLowerCase(Locale.ROOT), factory) != null) {
            throw new IllegalStateException("Duplicate " + kind + " SPI name: " + name);
        }
    }

    public MetConnector create(ConnectorConfig config, ConnectorManager manager) {
        NetworkConnectorFactory<?> transport = this.transports.get(config.listenType());
        if (transport == null) {
            throw new IllegalArgumentException("Unknown transport: " + config.listenType());
        } else {
            return this.assemble(config, manager, transport);
        }
    }

    private <M> MetConnector assemble(ConnectorConfig config, ConnectorManager manager, NetworkConnectorFactory<M> transport) {
        Map<String, ProtocolFactory<M>> mounted = new LinkedHashMap<>();
        Map<String, NetworkRoute<M>> routes = new LinkedHashMap<>();
        for (ProtocolConfig mount : config.protocols()) {
            ProtocolFactory<M> protocol = this.resolve(mount, transport);
            mounted.put(mount.scheme(), protocol);
            NetworkRoute<M> route = new NetworkRoute<>(mount.options(), message -> protocol.probe(mount, message));
            routes.put(mount.scheme(), route);
        }

        return new EndpointConnector<>(config, manager, transport, mounted, Collections.unmodifiableMap(routes));
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

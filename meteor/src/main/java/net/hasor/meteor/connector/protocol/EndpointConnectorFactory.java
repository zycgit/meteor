/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.protocol;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.connector.transport.NetworkConnectorFactory;
import net.hasor.meteor.connector.transport.NetworkRoute;
import net.hasor.meteor.connector.transport.RouteMatch;
import net.hasor.meteor.domain.MetServiceType;

/** Composes independently discovered transports and protocols. No concrete implementation is referenced here. */
public final class EndpointConnectorFactory implements MetConnectorFactory {
    private final Map<String, NetworkConnectorFactory<?>> transports    = new LinkedHashMap<>();
    private final Map<String, ProtocolFactory<?>>         protocols     = new LinkedHashMap<>();
    private final Map<String, ContextRoutes>              contextRoutes = new ConcurrentHashMap<>();
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

    /** 处理服务发布校验，按协议选择调用已挂载的扩展，不创建网络资源。 */
    public void validateService(Collection<ConnectorConfig> endpoints, MetBindInfo<?> service) {
        this.validateContexts(endpoints, service);
        for (ConnectorConfig endpoint : endpoints) {
            for (ProtocolConfig mount : endpoint.protocols()) {
                if (mount.accepts(service.getBindProtocols())) {
                    this.protocols.get(mount.protocol()).validateService(mount, service);
                }
            }
        }
    }

    /** 完成全部校验后发布路由，登记失败由容器移除事件撤销。 */
    public synchronized void registerService(MetContext context, MetBindInfo<?> service) {
        // 并发移除可能先于登记回调到达，不能重新加入已回收服务的地址。
        if (context.getServiceInfo(service.getBindID()) != service) {
            return;
        }

        Collection<ConnectorConfig> endpoints = context.getSettings().getConnectorConfigs();
        this.validateService(endpoints, service);
        if (service.isShadow() || service.getServiceType() != MetServiceType.Provider) {
            return;
        }

        Map<ContextRoutes, List<ContextRoutes.Route>> additions = new LinkedHashMap<>();
        for (ConnectorConfig endpoint : endpoints) {
            if (!StringUtils.equalsIgnoreCase(endpoint.listenType(), "http")) {
                continue;
            }

            List<ContextRoutes.Route> entries = new ArrayList<>();
            for (ProtocolConfig protocol : endpoint.protocols()) {
                if (!protocol.accepts(service.getBindProtocols())) {
                    continue;
                }
                for (String path : protocol.exactPaths(service)) {
                    ContextPaths.Mount mount = ContextPaths.exact(path);
                    entries.add(new ContextRoutes.Route(protocol.scheme(), mount, protocol.options()));
                }
            }

            if (!entries.isEmpty()) {
                ContextRoutes routes = this.contextRoutes(endpoint);
                routes.validate(entries);
                additions.put(routes, entries);
            }
        }

        for (Map.Entry<ContextRoutes, List<ContextRoutes.Route>> entry : additions.entrySet()) {
            entry.getKey().register(service, entry.getValue());
        }
    }

    public synchronized void removeService(MetBindInfo<?> service) {
        for (ContextRoutes routes : this.contextRoutes.values()) {
            routes.remove(service);
        }
    }

    private ContextRoutes contextRoutes(ConnectorConfig endpoint) {
        if (!StringUtils.equalsIgnoreCase(endpoint.listenType(), "http")) {
            return null;
        }

        return this.contextRoutes.computeIfAbsent(endpoint.name(), name -> {
            List<ContextRoutes.Route> prefixes = new ArrayList<>();
            for (ProtocolConfig protocol : endpoint.protocols()) {
                for (ContextPaths.Mount mount : protocol.contexts().mounts()) {
                    prefixes.add(new ContextRoutes.Route(protocol.scheme(), mount, protocol.options()));
                }
            }
            return new ContextRoutes(prefixes);
        });
    }

    private void validateContexts(Collection<ConnectorConfig> endpoints, MetBindInfo<?> service) {
        Set<String> selected = service.getBindContextPaths().keySet();
        if (selected.isEmpty()) {
            return;
        }

        // 服务选择使用协议名或 scheme，与 protocol(...) 的选择规则一致。
        Map<String, ProtocolConfig> configured = new HashMap<>();
        for (ConnectorConfig endpoint : endpoints) {
            for (ProtocolConfig protocol : endpoint.protocols()) {
                configured.put(protocol.name().toLowerCase(Locale.ROOT), protocol);
                configured.put(protocol.scheme(), protocol);
            }
        }

        Set<ProtocolConfig> validated = new HashSet<>();
        for (String name : selected) {
            ProtocolConfig protocol = configured.get(name);
            if (protocol == null) {
                throw new IllegalArgumentException("Context selected for unknown protocol: " + name);
            }
            if (!protocol.accepts(service.getBindProtocols())) {
                throw new IllegalArgumentException("Context selected for unbound protocol: " + name);
            }
            if (!validated.add(protocol)) {
                continue;
            }

            if (!protocol.exactPaths(service).isEmpty()) {
                String transport = this.protocols.get(protocol.protocol()).transport();
                if (!StringUtils.equalsIgnoreCase(transport, "http")) {
                    throw new IllegalArgumentException("Exact service paths require HTTP transport: " + name);
                }
            } else {
                List<String> paths = protocol.contextPaths(service);
                if (!protocol.contextPaths().containsAll(paths)) {
                    throw new IllegalArgumentException("Context is not configured for protocol " + name + ": " + paths);
                }
            }
        }
    }

    private <M> void prepare(ConnectorConfig config, NetworkConnectorFactory<M> transport) {
        // 参数优先级：协议显式配置 > 端点公共参数 > 协议默认值。
        Map<String, Map<String, String>> routes = new LinkedHashMap<>();
        for (ProtocolConfig mount : config.protocols()) {
            ProtocolFactory<M> protocol = this.resolve(mount, transport);
            Map<String, String> options = new LinkedHashMap<>(config.options());
            options.putAll(mount.options());
            boolean hasContext = options.containsKey("contextPath") || options.containsKey("contextPaths");
            if (hasContext && !StringUtils.equalsIgnoreCase(transport.name(), "http")) {
                throw new IllegalArgumentException("Transport does not support HTTP contexts: " + transport.name());
            }

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
            Function<M, RouteMatch> probe = message -> protocol.probe(mount, message);
            NetworkRoute<M> route = new NetworkRoute<>(mount.options(), mount.contexts(), probe);
            routes.put(mount.scheme(), route);
        }

        ContextRoutes contexts = this.contextRoutes(config);
        return new EndpointConnector<>(config, manager, transport, mounted, Collections.unmodifiableMap(routes), contexts);
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

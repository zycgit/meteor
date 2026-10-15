/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.connector.transport.*;
import net.hasor.meteor.domain.MetServiceType;

/** Composes independently discovered transports and protocols. No concrete implementation is referenced here. */
public final class ConnectorAssembler {
    private final Map<String, NetworkConnectorFactory<?>> transports    = new LinkedHashMap<>();
    private final Map<String, ProtocolFactory<?>>         protocols     = new LinkedHashMap<>();
    private final Map<String, ContextRoutes>              contextRoutes = new ConcurrentHashMap<>();
    private       ClassLoader                             loader;

    public Collection<String> listenTypes(ClassLoader loader) {
        if (this.loader == loader) {
            return this.transports.keySet();
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
        return this.transports.keySet();
    }

    /** Resolve a logical protocol provider and bind it to an explicitly published address scheme. */
    public ProtocolConfig protocol(String name, String scheme, Map<String, String> options) {
        ProtocolFactory<?> factory = this.protocols.get(name.toLowerCase(Locale.ROOT));
        if (factory == null) {
            throw new IllegalArgumentException("Unknown protocol: " + name);
        }
        String publishedScheme = scheme == null ? factory.name() : scheme;
        return new ProtocolConfig(factory.name(), publishedScheme, factory.name(), options);
    }

    /** Validate and normalize an endpoint without constructing network resources. */
    public void prepare(ConnectorConfig config) {
        this.prepareAll(List.of(config));
    }

    /** Validate all endpoints against one logical protocol namespace. */
    public void prepareAll(Collection<ConnectorConfig> configs) {
        this.configureLogicalNames(configs);
        for (ConnectorConfig config : configs) {
            NetworkConnectorFactory<?> transport = this.transports.get(config.listenType());
            if (transport == null) {
                throw new IllegalArgumentException("Unknown transport: " + config.listenType());
            }
            this.prepare(config, transport);
        }
    }

    /** 处理服务发布校验，按协议选择调用已挂载的扩展，不创建网络资源。 */
    void validateService(Collection<ConnectorConfig> endpoints, MetBindInfo<?> service) {
        this.configureLogicalNames(endpoints);
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

        Map<ContextRoutes, List<ContextRoute>> additions = new LinkedHashMap<>();
        for (ConnectorConfig endpoint : endpoints) {
            if (!StringUtils.equalsIgnoreCase(endpoint.listenType(), "http")) {
                continue;
            }

            List<ContextRoute> entries = new ArrayList<>();
            for (ProtocolConfig protocol : endpoint.protocols()) {
                if (!protocol.accepts(service.getBindProtocols())) {
                    continue;
                }

                for (String path : protocol.exactPaths(service)) {
                    ContextPaths.Mount mount = ContextPaths.exact(path);
                    entries.add(new ContextRoute(protocol.mountId(), mount, protocol.options()));
                }
            }

            if (!entries.isEmpty()) {
                ContextRoutes routes = this.contextRoutes(endpoint);
                routes.validate(entries);
                additions.put(routes, entries);
            }
        }

        for (Map.Entry<ContextRoutes, List<ContextRoute>> entry : additions.entrySet()) {
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
            List<ContextRoute> prefixes = new ArrayList<>();
            for (ProtocolConfig protocol : endpoint.protocols()) {
                for (ContextPaths.Mount mount : protocol.contexts().mounts()) {
                    prefixes.add(new ContextRoute(protocol.mountId(), mount, protocol.options()));
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

        Set<ProtocolConfig> validated = new HashSet<>();
        for (String name : selected) {
            List<ProtocolConfig> configured = this.selectProtocols(endpoints, name);
            if (configured.isEmpty()) {
                throw new IllegalArgumentException("Context selected for unknown protocol: " + name);
            }
            for (ProtocolConfig protocol : configured) {
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
                        String message = "Context is not configured for protocol " + name + ": " + paths;
                        throw new IllegalArgumentException(message);
                    }
                }
            }
        }
    }

    private List<ProtocolConfig> selectProtocols(Collection<ConnectorConfig> endpoints, String selector) {
        List<ProtocolConfig> available = new ArrayList<>();
        for (ConnectorConfig endpoint : endpoints) {
            available.addAll(endpoint.protocols());
        }

        List<ProtocolConfig> named = new ArrayList<>();
        for (ProtocolConfig protocol : available) {
            if (StringUtils.equalsIgnoreCase(protocol.name(), selector)) {
                named.add(protocol);
            }
        }
        if (!named.isEmpty()) {
            return named;
        }

        List<ProtocolConfig> aliases = new ArrayList<>();
        Set<String> logicalNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (ProtocolConfig protocol : available) {
            if (StringUtils.equalsIgnoreCase(protocol.scheme(), selector)) {
                aliases.add(protocol);
                logicalNames.add(protocol.name());
            }
        }
        if (logicalNames.size() > 1) {
            throw new IllegalArgumentException("Context protocol alias is ambiguous: " + selector);
        }
        return aliases;
    }

    private void configureLogicalNames(Collection<ConnectorConfig> endpoints) {
        Set<String> logicalNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (ConnectorConfig endpoint : endpoints) {
            for (ProtocolConfig protocol : endpoint.protocols()) {
                logicalNames.add(protocol.name());
            }
        }
        for (ConnectorConfig endpoint : endpoints) {
            for (ProtocolConfig protocol : endpoint.protocols()) {
                protocol.setLogicalNames(logicalNames);
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
            if (!hasContext && StringUtils.equalsIgnoreCase(transport.name(), "http")) {
                String defaultPath = config.protocols().size() == 1 ? "/" : "/" + mount.name();
                options.put("contextPath", defaultPath);
            }

            options.putIfAbsent("serializeType", protocol.defaultSerializeType());
            routes.put(mount.mountId(), options);
        }

        // 传输层只整理参数；校验完成后更新原来的协议配置。
        transport.configRoutes(routes);

        for (ProtocolConfig mount : config.protocols()) {
            mount.setOptions(routes.get(mount.mountId()));
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

    ConnectorEndpoint<?> create(ConnectorConfig config, ConnectorManager manager) {
        NetworkConnectorFactory<?> transport = this.transports.get(config.listenType());
        if (transport == null) {
            throw new IllegalArgumentException("Unknown transport: " + config.listenType());
        } else {
            return this.assemble(config, manager, transport);
        }
    }

    private <M> ConnectorEndpoint<M> assemble(ConnectorConfig config, ConnectorManager manager, //
            NetworkConnectorFactory<M> transport) {
        Map<String, ProtocolMount<M>> mounts = new LinkedHashMap<>();
        Map<String, Map<String, String>> routeOptions = new LinkedHashMap<>();
        for (ProtocolConfig mount : config.protocols()) {
            ProtocolFactory<M> protocol = this.resolve(mount, transport);
            Function<M, RouteMatch> probe = message -> protocol.probe(mount, message);
            NetworkRoute<M> route = new NetworkRoute<>(mount.options(), mount.contexts(), probe);
            mounts.put(mount.mountId(), new ProtocolMount<>(mount, protocol, route));
            routeOptions.put(mount.mountId(), mount.options());
        }

        ContextRoutes contexts = this.contextRoutes(config);
        Map<String, ProtocolMount<M>> readyMounts = Collections.unmodifiableMap(mounts);
        TransportConfig transportConfig = new TransportConfig(config.name(), config.address(), config.bindEnabled(), //
                config.options(), routeOptions);
        return new ConnectorEndpoint<>(config, transportConfig, manager, transport, readyMounts, contexts);
    }

    @SuppressWarnings("unchecked")
    private <M> ProtocolFactory<M> resolve(ProtocolConfig config, NetworkConnectorFactory<M> transport) {
        ProtocolFactory<?> protocol = this.protocols.get(config.protocol());
        if (protocol == null) {
            throw new IllegalArgumentException("Unknown protocol: " + config.protocol());
        }
        if (!StringUtils.equalsIgnoreCase(transport.name(), protocol.transport())) {
            String message = "Protocol " + protocol.name() + " requires transport " + protocol.transport();
            message += ", endpoint uses " + transport.name();
            throw new IllegalArgumentException(message);
        }
        if (protocol.messageType() != transport.messageType()) {
            throw new IllegalArgumentException("Incompatible transport message type for protocol: " + protocol.name());
        }

        // The SPI message contracts were checked before crossing the generic registry boundary.
        return (ProtocolFactory<M>) protocol;
    }

}

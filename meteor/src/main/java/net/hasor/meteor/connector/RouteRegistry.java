/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.*;
import java.util.function.Function;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.domain.MetServiceType;

/** Owns dynamic service routes after startup plans have been activated. */
final class RouteRegistry {
    private final MetContext                 context;
    private final Map<String, RouteEndpoint> endpoints = new LinkedHashMap<>();

    RouteRegistry(MetContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    synchronized <M> Function<String, RouteSelection> addEndpoint(ConnectorConfig config, //
            Collection<RouteProtocol<M>> protocols) {
        RouteEndpoint endpoint = new RouteEndpoint(config, protocols);
        if (this.endpoints.putIfAbsent(config.name(), endpoint) != null) {
            throw new IllegalArgumentException("Duplicate route endpoint: " + config.name());
        } else {
            return endpoint::selectExact;
        }
    }

    /** Validate every endpoint first and publish all exact routes as one change. */
    synchronized void registerService(MetBindInfo<?> service) {
        // 并发移除可能先于登记回调到达，不能重新加入已回收服务的地址。
        if (this.context.getServiceInfo(service.getBindID()) != service) {
            return;
        }

        // 第一阶段为每个端点完成协议校验、冲突检查，并生成下一版只读快照。
        this.validateContexts(service);
        List<RouteUpdate> updates = new ArrayList<>();
        for (RouteEndpoint endpoint : this.endpoints.values()) {
            updates.add(endpoint.prepare(service));
        }

        // 第二阶段只替换已准备好的快照，不再执行可能失败的业务校验。
        for (RouteUpdate update : updates) {
            update.apply();
        }
    }

    synchronized void removeService(MetBindInfo<?> service) {
        for (RouteEndpoint endpoint : this.endpoints.values()) {
            endpoint.remove(service);
        }
    }

    synchronized void clear() {
        for (RouteEndpoint endpoint : this.endpoints.values()) {
            endpoint.clear();
        }
        this.endpoints.clear();
    }

    private void validateContexts(MetBindInfo<?> service) {
        Set<String> selected = service.getBindContextPaths().keySet();
        if (selected.isEmpty()) {
            return;
        }

        Set<ProtocolConfig> validated = new HashSet<>();
        for (String name : selected) {
            List<ProtocolConfig> configured = this.selectProtocols(name);
            if (configured.isEmpty()) {
                throw new IllegalArgumentException("Context selected for unknown protocol: " + name);
            }
            for (ProtocolConfig protocol : configured) {
                this.validateContext(service, name, protocol, validated);
            }
        }
    }

    private void validateContext(MetBindInfo<?> service, String name, ProtocolConfig protocol, //
            Set<ProtocolConfig> validated) {
        if (!protocol.accepts(service.getBindProtocols())) {
            throw new IllegalArgumentException("Context selected for unbound protocol: " + name);
        }
        if (!validated.add(protocol)) {
            return;
        }

        if (!protocol.exactPaths(service).isEmpty()) {
            String transport = this.transport(protocol);
            if (!StringUtils.equalsIgnoreCase(transport, "http")) {
                throw new IllegalArgumentException("Exact service paths require HTTP transport: " + name);
            }
            return;
        }

        List<String> paths = protocol.contextPaths(service);
        if (!protocol.contextPaths().containsAll(paths)) {
            String message = "Context is not configured for protocol " + name + ": " + paths;
            throw new IllegalArgumentException(message);
        }
    }

    private List<ProtocolConfig> selectProtocols(String selector) {
        List<ProtocolConfig> available = new ArrayList<>();
        for (RouteEndpoint endpoint : this.endpoints.values()) {
            available.addAll(endpoint.config.protocols());
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

    private String transport(ProtocolConfig config) {
        for (RouteEndpoint endpoint : this.endpoints.values()) {
            RouteProtocol<?> protocol = endpoint.protocols.get(config.mountId());
            if (protocol != null && protocol.config() == config) {
                return protocol.factory().transport();
            }
        }
        throw new IllegalArgumentException("Protocol route is not active: " + config.name());
    }

    private static void addRoute(Map<String, RouteSelection> selected, RouteSelection route) {
        RouteSelection previous = selected.putIfAbsent(route.mount().path(), route);
        if (previous != null && !StringUtils.equals(previous.mountId(), route.mountId())) {
            throw new IllegalArgumentException("Conflicting exact service path: " + route.mount().path());
        }
    }

    private static final class RouteUpdate {
        private final RouteEndpoint               endpoint;
        private final MetBindInfo<?>              service;
        private final List<RouteSelection>        routes;
        private final Map<String, RouteSelection> exact;

        private RouteUpdate(RouteEndpoint endpoint, MetBindInfo<?> service, List<RouteSelection> routes, Map<String, RouteSelection> exact) {
            this.endpoint = endpoint;
            this.service = service;
            this.routes = routes;
            this.exact = exact;
        }

        private void apply() {
            this.endpoint.register(this.service, this.routes, this.exact);
        }
    }

    private static final class RouteEndpoint {
        private final    ConnectorConfig                           config;
        private final    Map<String, RouteProtocol<?>>             protocols;
        private final    Map<MetBindInfo<?>, List<RouteSelection>> registrations = new IdentityHashMap<>();
        private volatile Map<String, RouteSelection>               exact         = Collections.emptyMap();

        private <M> RouteEndpoint(ConnectorConfig config, Collection<RouteProtocol<M>> protocols) {
            this.config = config;
            Map<String, RouteProtocol<?>> mounted = new LinkedHashMap<>();
            for (RouteProtocol<M> protocol : protocols) {
                RouteProtocol<?> previous = mounted.putIfAbsent(protocol.mountId(), protocol);
                if (previous != null) {
                    throw new IllegalArgumentException("Duplicate protocol mount: " + protocol.mountId());
                }
            }
            this.protocols = Collections.unmodifiableMap(mounted);
        }

        private RouteUpdate prepare(MetBindInfo<?> service) {
            for (RouteProtocol<?> protocol : this.protocols.values()) {
                ProtocolConfig config = protocol.config();
                if (config.accepts(service.getBindProtocols())) {
                    protocol.factory().validateService(config, service);
                }
            }

            List<RouteSelection> additions = new ArrayList<>();
            boolean provider = !service.isShadow() && service.getServiceType() == MetServiceType.Provider;
            if (provider && StringUtils.equalsIgnoreCase(this.config.listenType(), "http")) {
                for (RouteProtocol<?> protocol : this.protocols.values()) {
                    this.addExactRoutes(service, protocol.config(), additions);
                }
            }

            List<RouteSelection> routes = List.copyOf(additions);
            Map<String, RouteSelection> exact = this.snapshot(service, routes);
            return new RouteUpdate(this, service, routes, exact);
        }

        private void addExactRoutes(MetBindInfo<?> service, ProtocolConfig protocol, //
                List<RouteSelection> additions) {
            if (!protocol.accepts(service.getBindProtocols())) {
                return;
            }
            for (String path : protocol.exactPaths(service)) {
                RouteMount mount = RouteMount.exact(path);
                additions.add(new RouteSelection(protocol.mountId(), mount, protocol.options()));
            }
        }

        private Map<String, RouteSelection> snapshot(MetBindInfo<?> replaced, //
                Collection<RouteSelection> additions) {
            Map<String, RouteSelection> selected = new LinkedHashMap<>();
            for (Map.Entry<MetBindInfo<?>, List<RouteSelection>> registration : this.registrations.entrySet()) {
                if (registration.getKey() == replaced) {
                    continue;
                }
                for (RouteSelection route : registration.getValue()) {
                    addRoute(selected, route);
                }
            }
            for (RouteSelection route : additions) {
                addRoute(selected, route);
            }
            return Collections.unmodifiableMap(selected);
        }

        private void register(MetBindInfo<?> service, List<RouteSelection> routes, Map<String, RouteSelection> selected) {
            if (routes.isEmpty()) {
                this.registrations.remove(service);
            } else {
                this.registrations.put(service, routes);
            }
            this.exact = selected;
        }

        private void remove(MetBindInfo<?> service) {
            if (!this.registrations.containsKey(service)) {
                return;
            }

            Map<String, RouteSelection> selected = this.snapshot(service, List.of());
            this.registrations.remove(service);
            this.exact = selected;
        }

        private void clear() {
            this.registrations.clear();
            this.exact = Collections.emptyMap();
        }

        private RouteSelection selectExact(String uri) {
            Map<String, RouteSelection> routes = this.exact;
            if (routes.isEmpty()) {
                return null;
            }
            int query = uri.indexOf('?');
            String path = query < 0 ? uri : uri.substring(0, query);
            return routes.get(path);
        }
    }
}

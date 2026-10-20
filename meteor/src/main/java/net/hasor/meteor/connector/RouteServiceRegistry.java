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
import net.hasor.cobble.logging.Logger;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.domain.*;
import net.hasor.meteor.domain.warp.MetBindInfoWrap;

/** Prepares endpoint protocol mounts, then maintains routes from service publication events. */
final class RouteServiceRegistry {
    private static final Logger                     logger       = Logger.getLogger(RouteServiceRegistry.class);
    private final        Set<MetBindInfo<?>>        publications = Collections.newSetFromMap(new IdentityHashMap<>());
    private final        MetContext                 context;
    private final        Map<String, RouteEndpoint> endpoints    = new LinkedHashMap<>();

    RouteServiceRegistry(MetContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /** Prepare every configured endpoint before runtime endpoint construction. */
    synchronized void prepare(Collection<PlanEndpoint<?>> plans) {
        Map<String, RouteEndpoint> prepared = new LinkedHashMap<>();
        for (PlanEndpoint<?> plan : plans) {
            RouteEndpoint endpoint = new RouteEndpoint(plan);
            prepared.put(plan.config().name(), endpoint);
        }

        this.endpoints.clear();
        this.endpoints.putAll(prepared);
    }

    /** Bind once to a prepared endpoint; route lookups read its current service paths directly. */
    synchronized Function<String, RouteSelection> resolver(String endpointName) {
        RouteEndpoint endpoint = this.endpoints.get(endpointName);
        return endpoint::selectExact;
    }

    /** Validate every endpoint first and publish all exact routes as one change. */
    synchronized void registerService(MetBindInfo<?> service) {
        // 并发移除可能先于登记回调到达，不能重新加入已回收服务的地址。
        if (this.context.getServiceInfo(service.getBindID()) != service) {
            return;
        }

        // 第一阶段为每个端点完成协议校验、冲突检查，并生成下一版只读快照。
        ServiceDomain<?> domain = MetBindInfoWrap.unwrap(service);
        this.validateContexts(service, domain);
        Map<String, ProtocolMeta> definitions = new LinkedHashMap<>();
        for (Map.Entry<String, ProtocolMeta> entry : domain.getProtocolMetas().entrySet()) {
            ContextPath declared = entry.getValue().getContextPath();
            if (declared != null) {
                definitions.put(entry.getKey(), new ProtocolMeta(service, declared, Map.of()));
            }
        }

        List<RouteUpdate> updates = new ArrayList<>();
        for (RouteEndpoint endpoint : this.endpoints.values()) {
            updates.add(endpoint.prepare(service, this.context, definitions));
        }

        // 第二阶段整体提交协议声明与各端点发布结果，再发布已准备好的精确路由快照。
        domain.replaceProtocolPublication(definitions);
        this.publications.add(service);
        for (RouteUpdate update : updates) {
            update.apply();
        }
    }

    synchronized void removeService(MetBindInfo<?> service) {
        for (RouteEndpoint endpoint : this.endpoints.values()) {
            endpoint.remove(service);
        }

        this.publications.remove(service);
        MetBindInfoWrap.unwrap(service).replaceProtocolPublication(Map.of());
    }

    synchronized void clear() {
        for (MetBindInfo<?> service : this.publications) {
            MetBindInfoWrap.unwrap(service).replaceProtocolPublication(Map.of());
        }

        this.publications.clear();
        for (RouteEndpoint endpoint : this.endpoints.values()) {
            endpoint.clear();
        }
        this.endpoints.clear();
    }

    private void validateContexts(MetBindInfo<?> service, ServiceDomain<?> domain) {
        for (Map.Entry<String, ProtocolMeta> entry : domain.getProtocolMetas().entrySet()) {
            ContextPath selected = entry.getValue().getContextPath();
            if (selected == null) {
                continue;
            }

            String name = entry.getKey();
            List<ProtocolMountBinding<?>> configured = this.selectProtocols(name);
            if (configured.isEmpty()) {
                throw new IllegalArgumentException("Context selected for unknown protocol: " + name);
            }

            for (ProtocolMountBinding<?> protocol : configured) {
                this.validateContext(service, name, selected, protocol);
            }
        }
    }

    private void validateContext(MetBindInfo<?> service, String name, ContextPath selected, //
            ProtocolMountBinding<?> protocol) {
        RouteMountIndex routes = protocol.routes();
        if (!routes.accepts(service.getBindProtocols())) {
            throw new IllegalArgumentException("Context selected for unbound protocol: " + name);
        }

        // 此时发布结果尚未提交，只检查原始路径声明，不读取上一次端点发布结果。
        if (selected.isExact()) {
            String transport = protocol.provider().transportType();
            if (!StringUtils.equalsIgnoreCase(transport, "http")) {
                throw new IllegalArgumentException("Exact service paths require HTTP transport: " + name);
            }
            return;
        }

        Set<String> paths = selected.getPaths();
        if (!routes.contextPaths().containsAll(paths)) {
            String message = "Context is not configured for protocol " + name + ": " + paths;
            throw new IllegalArgumentException(message);
        }
    }

    private List<ProtocolMountBinding<?>> selectProtocols(String selector) {
        List<ProtocolMountBinding<?>> selected = new ArrayList<>();
        for (RouteEndpoint endpoint : this.endpoints.values()) {
            for (ProtocolMountBinding<?> protocol : endpoint.protocols) {
                if (StringUtils.equalsIgnoreCase(protocol.config().name(), selector)) {
                    selected.add(protocol);
                }
            }
        }
        return selected;
    }

    private static void addRoute(Map<String, RouteSelection> selected, RouteSelection route) {
        RouteSelection previous = selected.putIfAbsent(route.mount().path(), route);
        if (previous != null && !StringUtils.equals(previous.protocolName(), route.protocolName())) {
            throw new IllegalArgumentException("Conflicting exact service path: " + route.mount().path());
        }
    }

    private static final class RouteUpdate {
        private final RouteEndpoint               endpoint;
        private final MetBindInfo<?>              service;
        private final List<RouteSelection>        routes;
        private final Map<String, RouteSelection> exact;

        private RouteUpdate(RouteEndpoint endpoint, MetBindInfo<?> service, //
                List<RouteSelection> routes, Map<String, RouteSelection> exact) {
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
        private final    ConnectorConfig                               config;
        private final    Collection<? extends ProtocolMountBinding<?>> protocols;
        private final    Map<MetBindInfo<?>, List<RouteSelection>>     registrations = new IdentityHashMap<>();
        private volatile Map<String, RouteSelection>                   exact         = Collections.emptyMap();

        private RouteEndpoint(PlanEndpoint<?> plan) {
            this.config = plan.config();
            // 启动计划已经固定协议名与挂载关系，直接借用其只读集合。
            this.protocols = plan.protocolBindings().values();
        }

        private RouteUpdate prepare(MetBindInfo<?> service, MetContext context, //
                Map<String, ProtocolMeta> definitions) {
            List<RouteSelection> additions = new ArrayList<>();
            boolean provider = !service.isShadow() && service.getServiceType() == MetServiceType.Provider;
            boolean exactRoutes = provider && StringUtils.equalsIgnoreCase(this.config.transportType(), "http");
            for (ProtocolMountBinding<?> protocol : this.protocols) {
                if (!protocol.routes().accepts(service.getBindProtocols())) {
                    continue;
                }

                // 草稿仅携带本轮声明与已准备端点，协议实现不写入服务中的运行结果。
                ProtocolConfig config = protocol.config();
                ProtocolMeta meta = definitions.get(config.name());
                if (meta == null) {
                    meta = new ProtocolMeta(service, null, Map.of());
                }

                ProtocolBinding prepared = protocol.provider().validate(config, meta, context);
                if (prepared == null) {
                    String message = "Protocol " + config.name() + " rejected service " + service.getBindID();
                    logger.warn(message + " at endpoint " + this.config.name());
                    continue;
                }

                // 每个端点独立补齐有效路径，原始声明仍保留在协议分组中。
                ContextPath paths = meta.getContextPath();
                if (paths == null && protocol.routes().contextPath() != null) {
                    paths = new ContextPath(List.of(protocol.routes().contextPath()), false);
                }
                prepared = prepared.withContextPaths(paths);
                Map<String, ProtocolBinding> endpoints = new LinkedHashMap<>(meta.getEndpoints());
                endpoints.put(this.config.name(), prepared);
                definitions.put(config.name(), new ProtocolMeta(service, meta.getContextPath(), endpoints));
                if (exactRoutes) {
                    this.addExactRoutes(config, prepared, additions);
                }
            }

            List<RouteSelection> routes = List.copyOf(additions);
            Map<String, RouteSelection> exact = this.snapshot(service, routes);
            return new RouteUpdate(this, service, routes, exact);
        }

        private void addExactRoutes(ProtocolConfig config, ProtocolBinding binding, List<RouteSelection> additions) {
            ContextPath selected = binding.getContextPath();
            if (selected == null || !selected.isExact()) {
                return;
            }

            for (String path : selected.getPaths()) {
                RouteMount mount = RouteMount.exact(path);
                additions.add(new RouteSelection(config.name(), mount, binding.getOptions()));
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

        private void register(MetBindInfo<?> service, List<RouteSelection> routes, //
                Map<String, RouteSelection> selected) {
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
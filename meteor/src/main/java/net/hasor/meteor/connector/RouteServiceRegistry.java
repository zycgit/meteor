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
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.domain.warp.MetBindInfoWrap;

/** Prepares endpoint protocol mounts, then maintains routes from service publication events. */
final class RouteServiceRegistry {
    private static final Logger                                         logger       = Logger.getLogger(RouteServiceRegistry.class);
    private final        MetContext                                     context;
    private final        Map<String, RouteEndpoint>                     endpoints    = new LinkedHashMap<>();
    private final        Map<MetBindInfo<?>, Map<String, ProtocolMeta>> publications = new IdentityHashMap<>();

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
        Map<String, ProtocolMeta> metas = new LinkedHashMap<>();
        for (Map.Entry<String, ProtocolMeta> entry : domain.getProtocolMetas().entrySet()) {
            ProtocolMeta previous = entry.getValue();
            ContextPath declared = previous.getContextPath();
            if (declared != null || previous.getBindResource() != null) {
                Object resource = previous.getBindResource();
                ProtocolMeta meta = new ProtocolMeta(service, declared, resource, new LinkedHashMap<>());
                metas.put(entry.getKey(), meta);
            }
        }

        // 各端点直接向本轮候选元信息收集绑定，准备期间不修改已发布对象。
        boolean provider = !service.isShadow() && service.getServiceType() == MetServiceType.Provider;
        for (RouteEndpoint endpoint : this.endpoints.values()) {
            endpoint.prepare(service, this.context, metas);
        }

        // 所有端点的协议准备完成后，再统一构建候选索引并检查冲突。
        Map<String, ProtocolMeta> inbound = provider ? metas : Map.of();
        Map<RouteEndpoint, Map<String, RouteSelection>> candidates = this.prepareExactIndexes(service, inbound);

        // 第二阶段统一提交本轮完整元信息和精确路由快照。
        domain.replaceProtocolPublication(metas);
        this.publications.put(service, provider ? domain.getProtocolMetas() : Map.of());
        this.applyExactIndexes(candidates);
    }

    synchronized void removeService(MetBindInfo<?> service) {
        // 先按已提交快照准备撤销，其他服务共享的路径仍参与索引重建。
        Map<RouteEndpoint, Map<String, RouteSelection>> candidates = this.prepareExactIndexes(service, Map.of());

        this.publications.remove(service);
        MetBindInfoWrap.unwrap(service).replaceProtocolPublication(Map.of());
        this.applyExactIndexes(candidates);
    }

    synchronized void clear() {
        for (MetBindInfo<?> service : this.publications.keySet()) {
            MetBindInfoWrap.unwrap(service).replaceProtocolPublication(Map.of());
        }

        this.publications.clear();
        for (RouteEndpoint endpoint : this.endpoints.values()) {
            endpoint.exact = Collections.emptyMap();
        }
        this.endpoints.clear();
    }

    /** 收集各端点的候选索引，只跳过 null，空 Map 仍需提交。 */
    private Map<RouteEndpoint, Map<String, RouteSelection>> prepareExactIndexes(MetBindInfo<?> replaced, //
            Map<String, ProtocolMeta> replacement) {
        Map<RouteEndpoint, Map<String, RouteSelection>> candidates = new LinkedHashMap<>();
        for (RouteEndpoint endpoint : this.endpoints.values()) {
            Map<String, RouteSelection> exact = this.prepareExactIndex(endpoint, replaced, replacement);
            if (exact != null) {
                candidates.put(endpoint, exact);
            }
        }
        return candidates;
    }

    /** 准备全部成功后替换索引，服务元信息和发布快照由对应生命周期入口提交。 */
    private void applyExactIndexes(Map<RouteEndpoint, Map<String, RouteSelection>> candidates) {
        for (Map.Entry<RouteEndpoint, Map<String, RouteSelection>> entry : candidates.entrySet()) {
            entry.getKey().exact = entry.getValue();
        }
    }

    /** null 表示无需更新，空 Map 表示清空旧索引。 */
    private Map<String, RouteSelection> prepareExactIndex(RouteEndpoint endpoint, MetBindInfo<?> replaced, //
            Map<String, ProtocolMeta> replacement) {
        if (!StringUtils.equalsIgnoreCase(endpoint.config.transportType(), "http")) {
            return null;
        }

        // 本轮候选只属于当前端点；没有新增或旧精确路由时保持现有索引。
        Map<String, RouteSelection> selected = new LinkedHashMap<>();
        endpoint.addExactRoutes(replacement, selected);
        Map<String, ProtocolMeta> previous = this.publications.getOrDefault(replaced, Map.of());
        if (selected.isEmpty() && !endpoint.hasExactRoutes(previous)) {
            return null;
        }

        // 其他服务只读取上次提交的协议结果，不读取正在变更的服务声明。
        for (Map.Entry<MetBindInfo<?>, Map<String, ProtocolMeta>> entry : this.publications.entrySet()) {
            if (entry.getKey() != replaced) {
                endpoint.addExactRoutes(entry.getValue(), selected);
            }
        }
        return Collections.unmodifiableMap(selected);
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

    private static final class RouteEndpoint {
        private final    ConnectorConfig                               config;
        private final    Collection<? extends ProtocolMountBinding<?>> protocols;
        private volatile Map<String, RouteSelection>                   exact = Collections.emptyMap();

        private RouteEndpoint(PlanEndpoint<?> plan) {
            this.config = plan.config();
            // 启动计划已经固定协议名与挂载关系，直接借用其只读集合。
            this.protocols = plan.protocolBindings().values();
        }

        private void prepare(MetBindInfo<?> service, MetContext context, Map<String, ProtocolMeta> metas) {
            for (ProtocolMountBinding<?> protocol : this.protocols) {
                if (!protocol.routes().accepts(service.getBindProtocols())) {
                    continue;
                }

                // 一个协议只创建一份本轮元信息，各端点共用原始声明。
                ProtocolConfig config = protocol.config();
                String name = config.name();
                ProtocolMeta meta = metas.get(name);
                if (meta == null) {
                    meta = new ProtocolMeta(service, null, null, new LinkedHashMap<>());
                    metas.put(name, meta);
                }

                // 协议一次准备完整定义、描述和路径，不修改已提交绑定或共享声明。
                ProtocolBinding prepared = protocol.provider().prepareBinding(config, context, meta);
                if (prepared == null) {
                    String message = "Protocol " + name + " rejected service " + service.getBindID();
                    logger.warn(message + " at endpoint " + this.config.name());
                    continue;
                }

                // 绑定按连接器名称加入本轮候选，所有端点准备成功后再统一发布。
                meta.getEndpoints().put(this.config.name(), prepared);
            }
        }

        private boolean hasExactRoutes(Map<String, ProtocolMeta> metas) {
            for (ProtocolMeta meta : metas.values()) {
                if (this.exactPaths(meta) != null) {
                    return true;
                }
            }
            return false;
        }

        private void addExactRoutes(Map<String, ProtocolMeta> metas, Map<String, RouteSelection> selected) {
            for (ProtocolMountBinding<?> protocol : this.protocols) {
                ProtocolConfig config = protocol.config();
                ProtocolMeta meta = metas.get(config.name());
                if (meta == null) {
                    continue;
                }

                ContextPath paths = this.exactPaths(meta);
                if (paths == null) {
                    continue;
                }

                for (String path : paths.getPaths()) {
                    // 端点配置在启动时固定，同一路径和协议可复用现有选择对象。
                    RouteSelection route = this.exact.get(path);
                    if (route == null || !StringUtils.equals(route.protocolName(), config.name())) {
                        route = new RouteSelection(config.name(), RouteMount.exact(path), config.options());
                    }
                    addRoute(selected, route);
                }
            }
        }

        private ContextPath exactPaths(ProtocolMeta meta) {
            ProtocolBinding binding = meta.getEndpoint(this.config.name());
            if (binding == null) {
                return null;
            }

            ContextPath paths = binding.getContextPath();
            return paths != null && paths.isExact() ? paths : null;
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

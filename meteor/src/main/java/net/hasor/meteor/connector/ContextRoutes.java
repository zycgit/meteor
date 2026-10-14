/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.connector;
import java.util.*;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;

/** 端点共享的路径索引；协议目录固定，服务精确入口随登记和移除更新。 */
public final class ContextRoutes {
    private final    List<Route>                      prefixes;
    private final    Map<MetBindInfo<?>, List<Route>> registrations = new IdentityHashMap<>();
    private volatile Map<String, Route>               exact         = Collections.emptyMap();

    public ContextRoutes(Collection<Route> prefixes) {
        List<Route> ordered = new ArrayList<>(prefixes);
        ordered.sort(Comparator.comparingInt((Route route) -> route.mount().path().length()).reversed());
        this.prefixes = Collections.unmodifiableList(ordered);
    }

    /** 登记前检查精确入口冲突；同一协议可在一个入口发布多个服务。 */
    public void validate(Collection<Route> routes) {
        Map<String, Route> selected = new HashMap<>(this.exact);
        for (Route route : routes) {
            add(selected, route);
        }
    }

    /** 发布完整快照，读请求无需加锁或遍历服务。 */
    public synchronized void register(MetBindInfo<?> service, List<Route> routes) {
        if (routes.isEmpty()) {
            return;
        }
        Map<String, Route> selected = new HashMap<>(this.exact);
        for (Route route : routes) {
            add(selected, route);
        }
        this.registrations.put(service, List.copyOf(routes));
        this.exact = Collections.unmodifiableMap(selected);
    }

    /** 按本次登记对象撤销，注册失败回滚不会删除另一个服务的入口。 */
    public synchronized void remove(MetBindInfo<?> service) {
        if (this.registrations.remove(service) == null) {
            return;
        }
        Map<String, Route> selected = new HashMap<>();
        for (List<Route> routes : this.registrations.values()) {
            for (Route route : routes) {
                add(selected, route);
            }
        }
        this.exact = Collections.unmodifiableMap(selected);
    }

    private static void add(Map<String, Route> selected, Route route) {
        Route previous = selected.putIfAbsent(route.mount().path(), route);
        if (previous != null && !StringUtils.equals(previous.protocol(), route.protocol())) {
            throw new IllegalArgumentException("Conflicting exact service path: " + route.mount().path());
        }
    }

    /** 精确路径优先；未命中才查最长目录前缀，查询参数不参与比较。 */
    public Route match(String uri) {
        int query = uri.indexOf('?');
        int end = query < 0 ? uri.length() : query;
        Map<String, Route> exact = this.exact;
        if (!exact.isEmpty()) {
            Route selected = exact.get(query < 0 ? uri : uri.substring(0, end));
            if (selected != null) {
                return selected;
            }
        }
        for (Route route : this.prefixes) {
            if (route.mount().matches(uri, end)) {
                return route;
            }
        }
        return null;
    }

    /** 请求选定后持有的不可变路由，不随后续服务登记变化。 */
        public record Route(String protocol, ContextPaths.Mount mount, Map<String, String> options) {
    }
}

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
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.domain.ContextPath;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.domain.ProtocolBinding;
import net.hasor.meteor.domain.ProtocolMeta;
import net.hasor.meteor.domain.warp.MetBindInfoWrap;
import net.hasor.meteor.utils.ContextPathUtils;

/** Immutable protocol context mounts used while routing requests. */
public final class RouteMountIndex {
    private final String                  name;
    private final String                  endpointName;
    private final String                  defaultContextPath;
    private final Map<String, RouteMount> mounts;
    private final List<RouteMount>        prefixes;
    private final List<RouteMount>        entries;
    private final List<String>            paths;

    public RouteMountIndex(String endpointName, ProtocolConfig config) {
        this.name = config.name();
        this.endpointName = endpointName;
        Map<String, String> options = config.options();
        String defaultPath = options.get("contextPath");
        this.defaultContextPath = defaultPath == null ? null : ContextPathUtils.normalize(defaultPath);

        // 编译并保存只读目录挂载，运行期不再解析协议选项。
        Map<String, RouteMount> mounts = new LinkedHashMap<>();
        List<RouteMount> prefixes = new ArrayList<>();
        for (String path : ContextPathUtils.contextPaths(options)) {
            RouteMount mount = new RouteMount(path, false);
            mounts.put(path, mount);
            prefixes.add(mount);
        }

        prefixes.sort(Comparator.comparingInt((RouteMount mount) -> mount.path().length()).reversed());
        this.mounts = Collections.unmodifiableMap(mounts);
        this.prefixes = Collections.unmodifiableList(prefixes);
        this.entries = List.copyOf(mounts.values());
        this.paths = List.copyOf(mounts.keySet());
    }

    /** 协议的默认挂载点；非 HTTP 协议没有 context 时返回 null。 */
    public String contextPath() {
        return this.defaultContextPath;
    }

    /** 全部已声明的挂载点，默认挂载点在首位。 */
    public List<String> contextPaths() {
        return this.paths;
    }

    /** 服务的完整地址声明；它不属于协议配置，也不隐含目录挂载。 */
    public Set<String> exactPaths(MetBindInfo<?> service) {
        ContextPath selected = this.selectedContexts(service);
        if (selected == null || !selected.isExact()) {
            return Collections.emptySet();
        } else {
            return selected.getPaths();
        }
    }

    /** 服务未显式选择时使用默认目录；精确路径声明会替代目录选择。 */
    public List<String> contextPaths(MetBindInfo<?> service) {
        if (!this.accepts(service)) {
            return List.of();
        }

        ContextPath selected = this.selectedContexts(service);
        if (selected != null) {
            return List.copyOf(selected.getPaths());
        } else {
            String defaultPath = this.contextPath();
            return defaultPath == null ? List.of() : List.of(defaultPath);
        }
    }

    /** 出站使用服务选择的精确地址，或所选默认目录、首个显式目录。 */
    public String contextPath(MetBindInfo<?> service) {
        if (!this.accepts(service)) {
            return null;
        } else {
            return this.contextPath(this.selectedContexts(service));
        }
    }

    private String contextPath(ContextPath selected) {
        String defaultPath = this.contextPath();
        if (selected == null) {
            return defaultPath;
        }

        Set<String> paths = selected.getPaths();
        if (!selected.isExact() && paths.contains(defaultPath)) {
            return defaultPath;
        } else {
            return paths.iterator().next();
        }
    }

    /** 按消费者自己的声明生成请求地址，精确模式不回退到目录前缀。 */
    public String requestPath(MetBindInfo<?> service, String relativePath) {
        if (!this.accepts(service)) {
            throw new IllegalArgumentException("Service does not expose protocol: " + this.name);
        }

        String fullPath = "/" + relativePath;
        ContextPath selected = this.selectedContexts(service);
        if (selected != null && selected.isExact()) {
            if (selected.getPaths().contains(fullPath)) {
                return fullPath;
            } else {
                throw new IllegalArgumentException("Service does not expose exact request path: " + fullPath);
            }
        }

        RouteMount mount = this.get(this.contextPath(selected));
        if (mount == null) {
            throw new IllegalArgumentException("No service mount exposes request path: " + fullPath);
        } else {
            return mount.prefix() + relativePath;
        }
    }

    /** 同时校验路径和匹配策略，精确入口不会暴露同路径上的目录服务。 */
    public boolean accepts(MetBindInfo<?> service, RouteMount mount) {
        if (mount == null || !this.accepts(service)) {
            return false;
        }
        if (!mount.exact() && this.get(mount.path()) == null) {
            return false;
        }

        ContextPath selected = this.selectedContexts(service);
        if (selected == null) {
            return !mount.exact() && StringUtils.equals(this.contextPath(), mount.path());
        } else {
            return selected.isExact() == mount.exact() && selected.getPaths().contains(mount.path());
        }
    }

    private ContextPath selectedContexts(MetBindInfo<?> service) {
        if (service.getServiceType() == MetServiceType.Provider) {
            ProtocolBinding binding = this.binding(service);
            return binding == null ? null : binding.getContextPath();
        } else {
            ProtocolMeta meta = MetBindInfoWrap.unwrap(service).getProtocolMeta(this.name);
            return meta == null ? null : meta.getContextPath();
        }
    }

    /** 当前协议在此连接器上的发布结果；该端点未接受服务时返回 null。 */
    public ProtocolBinding binding(MetBindInfo<?> service) {
        ProtocolMeta meta = MetBindInfoWrap.unwrap(service).getProtocolMeta(this.name);
        return meta == null ? null : meta.getEndpoint(this.endpointName);
    }

    /** 提供者只在协议实际接受的端点可达；消费者按自己的协议声明选择出站通道。 */
    public boolean accepts(MetBindInfo<?> service) {
        if (service.getServiceType() == MetServiceType.Provider) {
            return this.binding(service) != null;
        } else {
            return this.accepts(service.getBindProtocols());
        }
    }

    /** Empty selection exposes the service through every configured protocol endpoint. */
    public boolean accepts(Set<String> selected) {
        if (selected == null || selected.isEmpty()) {
            return true;
        }

        for (String value : selected) {
            if (StringUtils.equalsIgnoreCase(value, this.name)) {
                return true;
            }
        }
        return false;
    }

    /** Return all mounts in declaration order, with the default mount first. */
    public List<RouteMount> mounts() {
        return this.entries;
    }

    /** Find a declared mount by its normalized path. */
    private RouteMount get(String path) {
        return this.mounts.get(path);
    }

    /** Match the longest directory prefix; query parameters do not participate. */
    public RouteMount match(String uri) {
        int query = uri.indexOf('?');
        int end = query < 0 ? uri.length() : query;
        for (RouteMount mount : this.prefixes) {
            if (mount.matches(uri, end)) {
                return mount;
            }
        }
        return null;
    }
}

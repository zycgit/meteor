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
import net.hasor.meteor.utils.ContextPathUtils;

/** One protocol mount: logical protocol identity, published address scheme and SPI provider name. */
public final class ProtocolConfig {
    private final String              name;
    private final String              scheme;
    private final String              factoryName;
    private       String              mountId;
    private       Map<String, String> options;
    private       RouteMounts         contexts;
    private       Set<String>         logicalNames;

    ProtocolConfig(String name, String scheme, String factoryName, Map<String, String> options) {
        this.name = Objects.requireNonNull(name, "name");
        this.scheme = Objects.requireNonNull(scheme, "scheme").toLowerCase(Locale.ROOT);
        this.factoryName = Objects.requireNonNull(factoryName, "factoryName").toLowerCase(Locale.ROOT);
        this.logicalNames = Set.of(this.name.toLowerCase(Locale.ROOT));
        this.setOptions(options);
        if (this.scheme.trim().isEmpty()) {
            throw new IllegalArgumentException("Protocol scheme must not be blank");
        }
    }

    /** Creates an unresolved protocol selection; SPI resolution is deferred until connector initialization. */
    public static ProtocolConfig newConfig(String name, String scheme, Map<String, String> options) {
        String protocolName = normalizeIdentity(name, "Protocol name");
        String publishedScheme = protocolName;
        if (!StringUtils.isBlank(scheme)) {
            publishedScheme = normalizeIdentity(scheme, "Protocol scheme");
        }

        return new ProtocolConfig(protocolName, publishedScheme, protocolName, options);
    }

    /** Logical protocol identifier used by service declarations. */
    public String name() {
        return this.name;
    }

    /** Opaque runtime route identity assigned by the owning endpoint. */
    public String mountId() {
        if (this.mountId == null) {
            throw new IllegalStateException("Protocol is not mounted on an endpoint");
        }
        return this.mountId;
    }

    public String scheme() {
        return this.scheme;
    }

    public String factoryName() {
        return this.factoryName;
    }

    /** Create the endpoint-owned mount snapshot without sharing mutable preparation state. */
    ProtocolConfig mounted(String mountId) {
        ProtocolConfig mounted = new ProtocolConfig(this.name, this.scheme, this.factoryName, this.options);
        mounted.mountId = Objects.requireNonNull(mountId, "mountId");
        return mounted;
    }

    /** Configure the logical-name namespace shared by every mount in one runtime. */
    void setLogicalNames(Set<String> logicalNames) {
        Set<String> normalized = new HashSet<>();
        for (String name : logicalNames) {
            normalized.add(name.toLowerCase(Locale.ROOT));
        }
        this.logicalNames = Collections.unmodifiableSet(normalized);
    }

    public Map<String, String> options() {
        return this.options;
    }

    /** 构建阶段更新参数；复制输入以隔离用户配置和其他上下文。 */
    void setOptions(Map<String, String> options) {
        Map<String, String> configured = new LinkedHashMap<>(options);
        String path = configured.get("contextPath");
        if (path != null) {
            configured.put("contextPath", ContextPathUtils.normalize(path));
        }

        this.contexts = new RouteMounts(configured);
        this.options = Collections.unmodifiableMap(configured);
    }

    /** 共享已编译的只读挂载表。 */
    public RouteMounts contexts() {
        return this.contexts;
    }

    /** 协议的默认挂载点；非 HTTP 协议没有 context 时返回 null。 */
    public String contextPath() {
        return this.options.get("contextPath");
    }

    /** 全部已声明的挂载点，默认挂载点在首位。 */
    public List<String> contextPaths() {
        return this.contexts.paths();
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
        if (!this.accepts(service.getBindProtocols())) {
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
        if (!this.accepts(service.getBindProtocols())) {
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
        if (!this.accepts(service.getBindProtocols())) {
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

        RouteMount mount = this.contexts.get(this.contextPath(selected));
        if (mount == null) {
            throw new IllegalArgumentException("No service mount exposes request path: " + fullPath);
        } else {
            return mount.prefix() + relativePath;
        }
    }

    /** 同时校验路径和匹配策略，精确入口不会暴露同路径上的目录服务。 */
    public boolean accepts(MetBindInfo<?> service, RouteMount mount) {
        if (mount == null || !this.accepts(service.getBindProtocols())) {
            return false;
        }
        if (!mount.exact() && this.contexts.get(mount.path()) == null) {
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
        // 已配置的逻辑名优先于同名的公开地址别名。
        for (Map.Entry<String, ContextPath> entry : service.getBindContextPaths().entrySet()) {
            if (this.isLogicalSelector(entry.getKey()) && StringUtils.equalsIgnoreCase(entry.getKey(), this.name)) {
                return entry.getValue();
            }
        }
        for (Map.Entry<String, ContextPath> entry : service.getBindContextPaths().entrySet()) {
            boolean alias = !this.isLogicalSelector(entry.getKey());
            if (alias && StringUtils.equalsIgnoreCase(entry.getKey(), this.scheme)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** 仅匹配协议目录；精确匹配由端点共享的服务路由表负责。 */
    public RouteMount matchContext(String uri) {
        return this.contexts.match(uri);
    }

    /** Empty selection exposes the service through every configured protocol endpoint. */
    public boolean accepts(Set<String> selected) {
        if (selected == null || selected.isEmpty()) {
            return true;
        }

        for (String value : selected) {
            boolean matches = this.isLogicalSelector(value) ? //
                    StringUtils.equalsIgnoreCase(value, this.name) : //
                    StringUtils.equalsIgnoreCase(value, this.scheme);
            if (matches) {
                return true;
            }
        }
        return false;
    }

    private boolean isLogicalSelector(String value) {
        return value != null && this.logicalNames.contains(value.toLowerCase(Locale.ROOT));
    }

    public String option(String key, String fallback) {
        return this.options.getOrDefault(key, fallback);
    }

    public int integer(String key, int fallback) {
        int value = Integer.parseInt(this.option(key, Integer.toString(fallback)));
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be positive");
        } else {
            return value;
        }
    }

    private static String normalizeIdentity(String value, String description) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException(description + " must not be blank");
        } else {
            return value.toLowerCase(Locale.ROOT);
        }
    }
}

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

/** 协议身份固定；参数在构建阶段补齐，发布给连接器后只读使用。 */
public final class ProtocolConfig {
    private final String              name;
    private final String              scheme;
    private final String              protocol;
    private       Map<String, String> options;
    private       ContextPaths        contexts;

    public ProtocolConfig(String name, String scheme, String protocol, Map<String, String> options) {
        this.name = Objects.requireNonNull(name, "name");
        this.scheme = Objects.requireNonNull(scheme, "scheme").toLowerCase(Locale.ROOT);
        this.protocol = Objects.requireNonNull(protocol, "protocol").toLowerCase(Locale.ROOT);
        this.setOptions(options);
        if (this.scheme.trim().isEmpty()) {
            throw new IllegalArgumentException("Protocol scheme must not be blank");
        }
    }

    /** Public protocol identifier; defaults to its address scheme. */
    public String name() {
        return this.name;
    }

    public String scheme() {
        return this.scheme;
    }

    public String protocol() {
        return this.protocol;
    }

    public Map<String, String> options() {
        return this.options;
    }

    /** 构建阶段更新参数；复制输入以隔离用户配置和其他上下文。 */
    public void setOptions(Map<String, String> options) {
        Map<String, String> configured = new LinkedHashMap<>(options);
        String path = configured.get("contextPath");
        if (path != null) {
            configured.put("contextPath", ContextPathUtils.normalize(path));
        }

        this.contexts = new ContextPaths(configured);
        this.options = Collections.unmodifiableMap(configured);
    }

    /** 共享已编译的只读挂载表。 */
    public ContextPaths contexts() {
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
        }
        return selected.getPaths();
    }

    /** 服务未显式选择时使用默认目录；精确路径声明会替代目录选择。 */
    public List<String> contextPaths(MetBindInfo<?> service) {
        if (!this.accepts(service.getBindProtocols())) {
            return List.of();
        }
        ContextPath selected = this.selectedContexts(service);
        if (selected != null) {
            return List.copyOf(selected.getPaths());
        }
        String defaultPath = this.contextPath();
        return defaultPath == null ? List.of() : List.of(defaultPath);
    }

    /** 出站使用服务选择的精确地址，或所选默认目录、首个显式目录。 */
    public String contextPath(MetBindInfo<?> service) {
        if (!this.accepts(service.getBindProtocols())) {
            return null;
        }
        return this.contextPath(this.selectedContexts(service));
    }

    private String contextPath(ContextPath selected) {
        String defaultPath = this.contextPath();
        if (selected == null) {
            return defaultPath;
        }
        Set<String> paths = selected.getPaths();
        if (!selected.isExact() && paths.contains(defaultPath)) {
            return defaultPath;
        }
        return paths.iterator().next();
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
            }
            throw new IllegalArgumentException("Service does not expose exact request path: " + fullPath);
        }
        ContextPaths.Mount mount = this.contexts.get(this.contextPath(selected));
        if (mount == null) {
            throw new IllegalArgumentException("No service mount exposes request path: " + fullPath);
        }
        return mount.prefix() + relativePath;
    }

    /** 同时校验路径和匹配策略，精确入口不会暴露同路径上的目录服务。 */
    public boolean accepts(MetBindInfo<?> service, ContextPaths.Mount mount) {
        if (mount == null || !this.accepts(service.getBindProtocols())) {
            return false;
        }
        if (!mount.exact() && this.contexts.get(mount.path()) == null) {
            return false;
        }
        ContextPath selected = this.selectedContexts(service);
        if (selected == null) {
            return !mount.exact() && StringUtils.equals(this.contextPath(), mount.path());
        }
        return selected.isExact() == mount.exact() && selected.getPaths().contains(mount.path());
    }

    private ContextPath selectedContexts(MetBindInfo<?> service) {
        ContextPath selected = null;
        for (Map.Entry<String, ContextPath> entry : service.getBindContextPaths().entrySet()) {
            boolean matchesName = StringUtils.equalsIgnoreCase(entry.getKey(), this.name);
            boolean matchesScheme = StringUtils.equalsIgnoreCase(entry.getKey(), this.scheme);
            if (matchesName || matchesScheme) {
                selected = entry.getValue();
            }
        }
        return selected;
    }

    /** 仅匹配协议目录；精确匹配由端点共享的服务路由表负责。 */
    public ContextPaths.Mount matchContext(String uri) {
        return this.contexts.match(uri);
    }

    /** Empty selection exposes the service through every configured protocol endpoint. */
    public boolean accepts(Set<String> selected) {
        if (selected == null || selected.isEmpty()) {
            return true;
        }

        for (String value : selected) {
            boolean named = StringUtils.equalsIgnoreCase(value, this.name);
            boolean scheme = StringUtils.equalsIgnoreCase(value, this.scheme);
            if (named || scheme) {
                return true;
            }
        }
        return false;
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
}

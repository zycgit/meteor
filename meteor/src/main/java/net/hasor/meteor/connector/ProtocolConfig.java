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
import net.hasor.meteor.domain.ProtocolBinding;

/** One protocol mount: protocol/provider identity and published address scheme. */
public final class ProtocolConfig {
    private final String              name;
    private final String              scheme;
    private       Map<String, String> options;

    ProtocolConfig(String name, String scheme, Map<String, String> options) {
        this.name = Objects.requireNonNull(name, "name").toLowerCase(Locale.ROOT);
        this.scheme = Objects.requireNonNull(scheme, "scheme").toLowerCase(Locale.ROOT);
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

        return new ProtocolConfig(protocolName, publishedScheme, options);
    }

    /** Protocol identifier used by service declarations and SPI provider lookup. */
    public String name() {
        return this.name;
    }

    public String scheme() {
        return this.scheme;
    }

    /** 使用当前端点的协议参数构建发布结果；有效路径由路由组装阶段补齐。 */
    public ProtocolBinding build(Object definition) {
        return new ProtocolBinding(this.scheme, this.options, null, definition);
    }

    /** Create the endpoint-owned mount snapshot without sharing mutable preparation state. */
    ProtocolConfig snapshot() {
        return new ProtocolConfig(this.name, this.scheme, this.options);
    }

    public Map<String, String> options() {
        return this.options;
    }

    /** 构建阶段更新参数；复制输入以隔离用户配置和其他上下文。 */
    void setOptions(Map<String, String> options) {
        this.options = Collections.unmodifiableMap(new LinkedHashMap<>(options));
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

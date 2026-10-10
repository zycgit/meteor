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

/** 协议身份固定；参数在构建阶段补齐，发布给连接器后只读使用。 */
public final class ProtocolConfig {
    private final String              name;
    private final String              scheme;
    private final String              protocol;
    private       Map<String, String> options;

    public ProtocolConfig(String scheme, String protocol, Map<String, String> options) {
        this(scheme, scheme, protocol, options);
    }

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
        this.options = Collections.unmodifiableMap(new LinkedHashMap<>(options));
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
        }
        return value;
    }
}
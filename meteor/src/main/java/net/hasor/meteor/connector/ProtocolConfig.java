/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.*;

/** One application protocol mounted on a network endpoint. */
public record ProtocolConfig(String name, String scheme, String protocol, Map<String, String> options) {
    public ProtocolConfig(String scheme, String protocol, Map<String, String> options) {
        this(scheme, scheme, protocol, options);
    }

    public ProtocolConfig(String name, String scheme, String protocol, Map<String, String> options) {
        this.name = Objects.requireNonNull(name, "name");
        this.scheme = Objects.requireNonNull(scheme, "scheme").toLowerCase(Locale.ROOT);
        this.protocol = Objects.requireNonNull(protocol, "protocol").toLowerCase(Locale.ROOT);
        this.options = Collections.unmodifiableMap(new LinkedHashMap<>(options));
        if (this.scheme.trim().isEmpty()) {
            throw new IllegalArgumentException("Protocol scheme must not be blank");
        }
    }

    /** Public protocol identifier; defaults to its address scheme. */
    @Override
    public String name() {
        return this.name;
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
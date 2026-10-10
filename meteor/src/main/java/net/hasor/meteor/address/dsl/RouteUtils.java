/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address.dsl;
import java.util.Collection;
import net.hasor.cobble.StringUtils;

/** DSL 提供的无副作用工具，表达式通过 utils 调用。 */
public final class RouteUtils {
    RouteUtils() {
    }

    public boolean equals(String left, String right) {
        return StringUtils.equals(left, right);
    }

    public boolean equalsIgnoreCase(String left, String right) {
        return StringUtils.equalsIgnoreCase(left, right);
    }

    public boolean isBlank(String value) {
        return StringUtils.isBlank(value);
    }

    public String defaultIfBlank(String value, String fallback) {
        return StringUtils.defaultIfBlank(value, fallback);
    }

    public boolean contains(Collection<?> values, Object value) {
        return values != null && values.contains(value);
    }

    public int hash(String value) {
        return value == null ? 0 : value.hashCode();
    }
}
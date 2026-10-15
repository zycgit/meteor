/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import net.hasor.meteor.utils.ContextPathUtils;

/** One normalized protocol context mount. */
public final class RouteMount {
    private final String  path;
    private final boolean exact;
    private final String  prefix;

    RouteMount(String path, boolean exact) {
        this.path = path;
        this.exact = exact;
        this.prefix = exact || path.length() == 1 ? "/" : path + "/";
    }

    /** Create an exact service path used by protocol-specific service routes. */
    public static RouteMount exact(String path) {
        return new RouteMount(ContextPathUtils.exactPath(path), true);
    }

    public String path() {
        return this.path;
    }

    public boolean exact() {
        return this.exact;
    }

    /** Directory prefix including its trailing slash. */
    public String prefix() {
        return this.prefix;
    }

    /** Compare against the path portion of a URI without allocating a substring. */
    boolean matches(String uri, int end) {
        int length = this.path.length();
        if (end < length || !uri.startsWith(this.path)) {
            return false;
        }
        if (this.exact) {
            return end == length;
        }
        return end == length || length == 1 || uri.charAt(length) == '/';
    }
}

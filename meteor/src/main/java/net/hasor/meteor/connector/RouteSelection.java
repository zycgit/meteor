/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.Map;
import java.util.Objects;

/** Immutable protocol mount selected for one transport request. */
public final class RouteSelection {
    private final String              protocolName;
    private final RouteMount          mount;
    private final Map<String, String> options;

    RouteSelection(String protocolName, RouteMount mount, Map<String, String> options) {
        this.protocolName = Objects.requireNonNull(protocolName, "protocolName");
        this.mount = Objects.requireNonNull(mount, "mount");
        this.options = Map.copyOf(options);
    }

    public String protocolName() {
        return this.protocolName;
    }

    public RouteMount mount() {
        return this.mount;
    }

    public Map<String, String> options() {
        return this.options;
    }

    /** Match the path portion of a URI; query parameters do not participate. */
    boolean matches(String uri, int end) {
        return this.mount.matches(uri, end);
    }
}

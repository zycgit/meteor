/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.Map;

/** Thin test access for registry snapshot fixtures with synthetic protocol identities. */
public final class RegistryProtocolTestAccess {
    private RegistryProtocolTestAccess() {
    }

    public static ProtocolConfig config(String name, String scheme, String protocol, Map<String, String> options) {
        return new ProtocolConfig(name, scheme, protocol, options);
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.LinkedHashMap;
import java.util.Map;
import net.hasor.meteor.connector.transport.TransportConfig;

/** Builds the immutable transport view used by direct transport tests. */
public final class TransportTestConfigs {
    private TransportTestConfigs() {
    }

    public static TransportConfig transport(ConnectorConfig config) {
        Map<String, Map<String, String>> routeOptions = new LinkedHashMap<>();
        for (ProtocolConfig protocol : config.protocols()) {
            routeOptions.put(protocol.mountId(), protocol.options());
        }

        return new TransportConfig(config.name(), config.address(), config.bindEnabled(), config.options(), //
                routeOptions);
    }
}

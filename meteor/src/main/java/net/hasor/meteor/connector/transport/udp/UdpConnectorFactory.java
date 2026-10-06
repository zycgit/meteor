/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.udp;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.transport.NetworkConnector;
import net.hasor.meteor.connector.transport.NetworkConnectorFactory;

public final class UdpConnectorFactory implements NetworkConnectorFactory<byte[]> {
    public boolean sharedRoutes() {
        return true;
    }

    public String name() {
        return "udp";
    }

    public Class<byte[]> messageType() {
        return byte[].class;
    }

    public NetworkConnector<byte[]> create(ConnectorConfig config, ClassLoader loader) {
        return new UdpConnector(config, loader);
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.tcp;
import java.util.Map;
import net.hasor.meteor.connector.transport.TransportConfig;
import net.hasor.meteor.connector.transport.TransportFactory;
import net.hasor.meteor.connector.transport.TransportPlan;

public final class TcpTransportFactory implements TransportFactory<byte[]> {
    public boolean sharedRoutes() {
        return false;
    }

    public void configRoutes(Map<String, Map<String, String>> routes) {
    }

    public String name() {
        return "tcp";
    }

    public Class<byte[]> messageType() {
        return byte[].class;
    }

    public TransportPlan<byte[]> prepare(TransportConfig config, ClassLoader loader) {
        return TransportPlan.withPipeline(config, loader, (pipeline, services) -> {
            return new TcpConnector(config, pipeline, services.classLoader());
        });
    }
}

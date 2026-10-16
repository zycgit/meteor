/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http;
import net.hasor.meteor.connector.transport.NetworkConnector;
import net.hasor.meteor.connector.transport.TransportConfig;
import net.hasor.meteor.connector.transport.TransportPlan;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import static net.hasor.meteor.connector.transport.TransportTestServices.newRuntimeServices;

public final class HttpConnectorTestAccess {
    private HttpConnectorTestAccess() {
    }

    public static NetworkConnector<HttpExchange> newConnector(TransportConfig config, ClassLoader loader) {
        TransportPlan<HttpExchange> plan = new HttpTransportFactory().prepare(config, loader);
        return plan.create(newRuntimeServices(loader));
    }
}

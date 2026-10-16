/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport;
import net.hasor.meteor.connector.transport.http.HttpTransportFactory;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;

/** Supplies borrowed runtime services to direct transport unit tests. */
public final class TransportTestServices {
    private TransportTestServices() {
    }

    public static TransportRuntimeServices newRuntimeServices(ClassLoader classLoader) {
        return new TransportRuntimeServices(classLoader, (task, delay) -> () -> true);
    }

    public static NetworkConnector<HttpExchange> newHttpConnector(TransportConfig config, ClassLoader loader) {
        TransportPlan<HttpExchange> plan = new HttpTransportFactory().prepare(config, loader);
        return plan.create(newRuntimeServices(loader));
    }
}

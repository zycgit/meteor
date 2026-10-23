/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http;
import net.hasor.meteor.connector.ProtocolProvider;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;

/** HTTP protocol SPI; requests select protocol mounts by their URI path. */
public interface HttpProtocolProvider extends ProtocolProvider<HttpExchange> {
    @Override
    default String transportType() {
        return "http";
    }

    @Override
    default Class<HttpExchange> messageType() {
        return HttpExchange.class;
    }
}
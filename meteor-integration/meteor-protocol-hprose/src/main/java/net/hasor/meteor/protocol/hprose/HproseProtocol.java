/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose;
import java.util.function.BiConsumer;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.protocol.ProtocolContext;
import net.hasor.meteor.connector.protocol.ProtocolFactory;
import net.hasor.meteor.connector.protocol.ProtocolSession;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.RouteMatch;
import net.hasor.meteor.connector.transport.http.HttpExchange;
import net.hasor.meteor.domain.payload.Payload;

/** Hprose SPI provider using the same session contract as every other protocol. */
public final class HproseProtocol implements ProtocolFactory<HttpExchange> {
    @Override
    public String scheme() {
        return this.name();
    }

    @Override
    public RouteMatch probe(ProtocolConfig config, HttpExchange message) {
        return RouteMatch.REJECT;
    }

    public String name() {
        return "hprose";
    }

    public String transport() {
        return "http";
    }

    public Class<HttpExchange> messageType() {
        return HttpExchange.class;
    }

    public ProtocolSession<HttpExchange> create(ProtocolConfig config, ProtocolContext context, NetworkChannel<HttpExchange> connection, BiConsumer<Long, Payload> messages) {
        return new HproseSession(config, context, connection, messages);
    }
}

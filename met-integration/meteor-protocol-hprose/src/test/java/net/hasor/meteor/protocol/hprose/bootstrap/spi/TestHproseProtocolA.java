/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.bootstrap.spi;
import java.util.function.BiConsumer;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.protocol.ProtocolContext;
import net.hasor.meteor.connector.protocol.ProtocolFactory;
import net.hasor.meteor.connector.protocol.ProtocolSession;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.RouteMatch;
import net.hasor.meteor.connector.transport.http.HttpExchange;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.protocol.hprose.HproseProtocol;

/** A separate SPI identity exercising shared HTTP routing with a real protocol implementation. */
public class TestHproseProtocolA implements ProtocolFactory<HttpExchange> {
    @Override
    public String scheme() {
        return this.name();
    }

    @Override
    public RouteMatch probe(ProtocolConfig config, HttpExchange message) {
        return RouteMatch.REJECT;
    }

    public String name() {
        return "hprosea";
    }

    public String transport() {
        return "http";
    }

    public Class<HttpExchange> messageType() {
        return HttpExchange.class;
    }

    public ProtocolSession<HttpExchange> create(ProtocolConfig config, ProtocolContext context, NetworkChannel<HttpExchange> channel, BiConsumer<Long, Payload> messages) {
        return new HproseProtocol().create(config, context, channel, messages);
    }
}

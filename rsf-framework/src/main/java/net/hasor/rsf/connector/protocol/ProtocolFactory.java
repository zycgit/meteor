/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.protocol;
import java.util.function.BiConsumer;
import net.hasor.rsf.connector.ProtocolConfig;
import net.hasor.rsf.connector.transport.NetworkChannel;
import net.hasor.rsf.connector.transport.RouteMatch;
import net.hasor.rsf.domain.payload.Payload;

/** Protocol SPI. Transport requirements and protocol execution use one contract. */
public interface ProtocolFactory<M> {
    String name();

    String transport();

    Class<M> messageType();

    /** Inspect data without consuming it when the transport needs content-based routing. */
    default RouteMatch probe(ProtocolConfig config, M message) {
        return RouteMatch.REJECT;
    }

    ProtocolSession<M> create(ProtocolConfig config, ProtocolContext context, NetworkChannel<M> connection, BiConsumer<Long, Payload> messages);
}

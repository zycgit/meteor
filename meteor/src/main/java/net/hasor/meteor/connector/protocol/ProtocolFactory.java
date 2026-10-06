/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.protocol;
import java.util.function.BiConsumer;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.RouteMatch;
import net.hasor.meteor.domain.payload.Payload;

/** Protocol SPI. Transport requirements and protocol execution use one contract. */
public interface ProtocolFactory<M> {
    String name();

    /** Address scheme owned by this protocol, independent of user configuration. */
    String scheme();

    String transport();

    Class<M> messageType();

    /** Inspect data without consuming it when the transport needs content-based routing. */
    RouteMatch probe(ProtocolConfig config, M message);

    ProtocolSession<M> create(ProtocolConfig config, ProtocolContext context, NetworkChannel<M> connection, BiConsumer<Long, Payload> messages);
}

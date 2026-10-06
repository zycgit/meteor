/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.protocol.rsf;
import java.util.function.BiConsumer;
import net.hasor.rsf.connector.ProtocolConfig;
import net.hasor.rsf.connector.protocol.ProtocolContext;
import net.hasor.rsf.connector.protocol.ProtocolFactory;
import net.hasor.rsf.connector.protocol.ProtocolSession;
import net.hasor.rsf.connector.transport.NetworkChannel;
import net.hasor.rsf.connector.transport.RouteMatch;
import net.hasor.rsf.domain.payload.Payload;

/**
 * RSF/1 message adapter, including its own request/response correlation.
 */
public final class RsfProtocol implements ProtocolFactory<byte[]> {
    @Override
    public String scheme() {
        return this.name();
    }

    public String name() {
        return "rsf";
    }

    public String transport() {
        return "tcp";
    }

    public Class<byte[]> messageType() {
        return byte[].class;
    }

    @Override
    public RouteMatch probe(ProtocolConfig config, byte[] prefix) {
        if (prefix.length == 0) {
            return RouteMatch.NEED_MORE;
        } else {
            return (prefix[0] & 0xff) == 0xb1 ? RouteMatch.MATCH : RouteMatch.REJECT;
        }
    }

    @Override
    public ProtocolSession<byte[]> create(ProtocolConfig config, ProtocolContext manager, NetworkChannel<byte[]> connection, BiConsumer<Long, Payload> messages) {
        return new RsfSession(config, manager, connection, messages);
    }
}

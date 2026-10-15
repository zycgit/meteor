/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.protocol.rsf;
import java.util.function.BiConsumer;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.domain.payload.Payload;

/**
 * RSF/1 message adapter, including its own request/response correlation.
 */
public final class RsfProtocol implements ProtocolFactory<byte[]> {
    public String name() {
        return "rsf";
    }

    @Override
    public String defaultSerializeType() {
        return "Java";
    }

    public String transportType() {
        return "tcp";
    }

    @Override
    public void validateService(ProtocolConfig config, MetBindInfo<?> service) {
    }

    public Class<byte[]> messageType() {
        return byte[].class;
    }

    @Override
    public RouteProbeResult probe(ProtocolConfig config, byte[] prefix) {
        if (prefix.length == 0) {
            return RouteProbeResult.NEED_MORE;
        } else {
            int frameType = prefix[0] & 0xff;
            boolean request = frameType == 0x91;
            boolean message = frameType == 0xa1;
            boolean response = frameType == 0xb1;
            return request || message || response ? RouteProbeResult.MATCH : RouteProbeResult.REJECT;
        }
    }

    @Override
    public ProtocolSession<byte[]> createSession(ProtocolConfig config, ProtocolSessionContext manager, //
            NetworkChannel<byte[]> connection, BiConsumer<Long, Payload> messages) {
        return new RsfSession(config, manager, connection, messages);
    }
}

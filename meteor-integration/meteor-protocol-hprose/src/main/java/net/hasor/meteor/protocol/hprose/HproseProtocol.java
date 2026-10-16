/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ObjLongConsumer;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.protocol.hprose.codec.HproseHttpCodec;
import net.hasor.meteor.protocol.hprose.session.HproseSession;

/** Hprose SPI provider using the same session contract as every other protocol. */
public final class HproseProtocol implements ProtocolFactory<HttpExchange> {
    private final Map<ProtocolConfig, HproseHttpCodec> codecs = new ConcurrentHashMap<>();

    @Override
    public RouteProbeResult probe(ProtocolConfig config, HttpExchange message) {
        return RouteProbeResult.REJECT;
    }

    @Override
    public String name() {
        return "hprose";
    }

    @Override
    public String defaultSerializeType() {
        return "Hprose";
    }

    @Override
    public String transportType() {
        return "http";
    }

    @Override
    public void validateService(ProtocolConfig config, MetBindInfo<?> service) {
    }

    @Override
    public Class<HttpExchange> messageType() {
        return HttpExchange.class;
    }

    @Override
    public ProtocolSession<HttpExchange> createSession(ProtocolConfig config, ProtocolSessionContext context, //
            NetworkChannel<HttpExchange> channel, ObjLongConsumer<Payload> messages) {
        HproseHttpCodec codec = this.codecs.computeIfAbsent(config, current -> {
            return new HproseHttpCodec(current, context.metContext());
        });
        return new HproseSession(context, channel, messages, codec);
    }
}

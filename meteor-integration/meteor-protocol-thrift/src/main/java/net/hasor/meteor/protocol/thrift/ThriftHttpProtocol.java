/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift;
import java.util.function.ObjLongConsumer;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.domain.ProtocolBinding;
import net.hasor.meteor.domain.ProtocolMeta;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.protocol.thrift.codec.ThriftCodec;
import net.hasor.meteor.protocol.thrift.session.ThriftHttpSession;

/** Thrift HTTP 入口；挂载目录和 HTTP/1、HTTP/2、安全传输均沿用内核。 */
public final class ThriftHttpProtocol implements ProtocolProvider<HttpExchange> {
    @Override
    public String name() {
        return "thrift-http";
    }

    @Override
    public String transportType() {
        return "http";
    }

    @Override
    public String defaultSerialize() {
        return "thrift";
    }

    @Override
    public Class<HttpExchange> messageType() {
        return HttpExchange.class;
    }

    @Override
    public ProtocolBinding validate(ProtocolConfig config, ProtocolMeta meta, MetContext context) {
        ThriftServiceResource definition = ThriftCodec.validateService(config, meta.getService());
        return definition == null ? null : config.build(definition);
    }

    @Override
    public RouteProbeResult probe(ProtocolConfig config, HttpExchange message) {
        return RouteProbeResult.REJECT;
    }

    @Override
    public ProtocolSession<HttpExchange> createSession(ProtocolConfig config, ProtocolSessionContext context, //
            NetworkChannel<HttpExchange> channel, ObjLongConsumer<Payload> messages) {
        return new ThriftHttpSession(config, context, channel, messages);
    }
}

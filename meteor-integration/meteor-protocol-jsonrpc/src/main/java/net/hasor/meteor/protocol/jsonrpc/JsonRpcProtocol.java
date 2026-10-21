/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.jsonrpc;
import java.util.function.ObjLongConsumer;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.domain.ProtocolBinding;
import net.hasor.meteor.domain.ProtocolMeta;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.protocol.jsonrpc.codec.JsonRpcCodec;
import net.hasor.meteor.protocol.jsonrpc.session.JsonRpcSession;

/** JSON-RPC 2.0 uses HTTP or an upgraded WebSocket on the same transport SPI. */
public final class JsonRpcProtocol implements ProtocolProvider<HttpExchange> {
    @Override
    public String name() {
        return "jsonrpc";
    }

    @Override
    public String transportType() {
        return "http";
    }

    @Override
    public String defaultSerialize() {
        return "Json";
    }

    @Override
    public Class<HttpExchange> messageType() {
        return HttpExchange.class;
    }

    @Override
    public ProtocolBinding validate(ProtocolConfig config, ProtocolMeta meta, MetContext context) {
        return JsonRpcCodec.validateService(meta.getService()) ? config.build(null) : null;
    }

    @Override
    public RouteProbeResult probe(ProtocolConfig config, HttpExchange message) {
        return RouteProbeResult.REJECT;
    }

    @Override
    public ProtocolSession<HttpExchange> createSession(ProtocolConfig config, ProtocolSessionContext context, //
            NetworkChannel<HttpExchange> channel, ObjLongConsumer<Payload> messages) {
        return new JsonRpcSession(config, context, channel, messages);
    }
}

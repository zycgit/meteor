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
import net.hasor.meteor.connector.ProtocolProvider;
import net.hasor.meteor.connector.ProtocolSession;
import net.hasor.meteor.connector.ProtocolSessionContext;
import net.hasor.meteor.connector.RouteProbeResult;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.domain.ProtocolBinding;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.protocol.jsonrpc.codec.JsonRpcServiceBinding;
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
    public ProtocolBinding validate(ProtocolConfig config, MetContext context, ProtocolMeta meta) {
        JsonRpcServiceBinding methods = JsonRpcServiceBinding.prepare(meta.getService());
        return methods == null ? null : meta.build(config, methods);
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

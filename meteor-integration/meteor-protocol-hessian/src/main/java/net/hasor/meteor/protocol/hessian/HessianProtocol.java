/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hessian;
import java.util.function.ObjLongConsumer;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.domain.ProtocolBinding;
import net.hasor.meteor.domain.ProtocolMeta;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.protocol.hessian.codec.HessianCodec;
import net.hasor.meteor.protocol.hessian.session.HessianSession;

/** Hessian RPC 协议入口，按 HTTP 挂载点发布普通 Java 服务。 */
public final class HessianProtocol implements ProtocolProvider<HttpExchange> {
    @Override
    public String name() {
        return "hessian";
    }

    @Override
    public String transportType() {
        return "http";
    }

    @Override
    public String defaultSerialize() {
        return "Hessian";
    }

    @Override
    public Class<HttpExchange> messageType() {
        return HttpExchange.class;
    }

    @Override
    public ProtocolBinding validate(ProtocolConfig config, ProtocolMeta meta, MetContext context) {
        return HessianCodec.validateService(meta.getService()) ? config.build(null) : null;
    }

    @Override
    public RouteProbeResult probe(ProtocolConfig config, HttpExchange message) {
        return RouteProbeResult.REJECT;
    }

    @Override
    public ProtocolSession<HttpExchange> createSession(ProtocolConfig config, ProtocolSessionContext context,//
            NetworkChannel<HttpExchange> channel, ObjLongConsumer<Payload> messages) {
        return new HessianSession(config, context, channel, messages);
    }
}

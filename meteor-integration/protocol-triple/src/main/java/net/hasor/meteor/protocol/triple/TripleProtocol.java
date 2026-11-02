/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.triple;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ObjLongConsumer;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.connector.ProtocolSession;
import net.hasor.meteor.connector.ProtocolSessionContext;
import net.hasor.meteor.connector.RouteMountIndex;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.HttpProtocolProvider;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.domain.ProtocolBinding;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.protocol.triple.codec.TripleHttpCodec;
import net.hasor.meteor.protocol.triple.session.TripleMountRuntime;
import net.hasor.meteor.protocol.triple.session.TripleSession;

/** Triple SPI 入口；按挂载点组装编码规则和调用会话。 */
public final class TripleProtocol implements HttpProtocolProvider {
    private final Map<ProtocolConfig, TripleMountRuntime> runtimes = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "triple";
    }

    @Override
    public String defaultSerialize() {
        return "Hessian2";
    }

    @Override
    public ProtocolBinding prepareBinding(ProtocolConfig config, MetContext context, ProtocolMeta meta) {
        return TripleHttpCodec.prepareBinding(config, meta);
    }

    @Override
    public ProtocolSession<HttpExchange> createSession(ProtocolConfig config, ProtocolSessionContext context, //
            NetworkChannel<HttpExchange> channel, ObjLongConsumer<Payload> messages) {
        RouteMountIndex routes = context.routes();
        TripleMountRuntime runtime = this.runtimes.computeIfAbsent(config, selected -> {
            return new TripleMountRuntime(selected, routes);
        });
        runtime.retain(context.metContext());
        try {
            return new TripleSession(context, channel, messages, runtime);
        } catch (RuntimeException | Error failure) {
            runtime.release();
            throw failure;
        }
    }
}

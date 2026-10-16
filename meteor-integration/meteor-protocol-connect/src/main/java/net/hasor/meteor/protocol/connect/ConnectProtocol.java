/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.connect;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ObjLongConsumer;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.protocol.connect.codec.ConnectCors;
import net.hasor.meteor.protocol.connect.codec.ConnectHttpCodec;
import net.hasor.meteor.protocol.connect.session.ConnectMountRuntime;
import net.hasor.meteor.protocol.connect.session.ConnectSession;

/** Connect 协议的 SPI 入口，按挂载点组装消息转换与调用会话。 */
public final class ConnectProtocol implements ProtocolFactory<HttpExchange> {
    private final Map<ProtocolConfig, ConnectMountRuntime> runtimes = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "connect";
    }

    @Override
    public String transportType() {
        return "http";
    }

    @Override
    public String defaultSerializeType() {
        return "Protobuf";
    }

    @Override
    public Class<HttpExchange> messageType() {
        return HttpExchange.class;
    }

    @Override
    public void validateService(ProtocolConfig config, MetBindInfo<?> service) {
        new ConnectCors(config);
        ConnectHttpCodec.validateService(config, service);
        this.runtimes.computeIfAbsent(config, ConnectMountRuntime::new);
    }

    @Override
    public RouteProbeResult probe(ProtocolConfig config, HttpExchange message) {
        return RouteProbeResult.REJECT;
    }

    @Override
    public ProtocolSession<HttpExchange> createSession(ProtocolConfig config, ProtocolSessionContext context, //
            NetworkChannel<HttpExchange> channel, ObjLongConsumer<Payload> messages) {
        ConnectMountRuntime runtime = this.runtimes.computeIfAbsent(config, ConnectMountRuntime::new);
        runtime.retain(context.metContext());
        try {
            return new ConnectSession(context, channel, messages, runtime);
        } catch (RuntimeException | Error failure) {
            runtime.release();
            throw failure;
        }
    }
}

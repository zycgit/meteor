/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.scenario.rpc.websocket.example;
import java.util.function.ObjLongConsumer;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.domain.payload.Payload;

/** 用户可以自定义应用协议；WebSocket 适配器本身不规定 RPC 信封。 */
public final class JsonWebSocketProtocol implements ProtocolFactory<HttpExchange> {
    @Override
    public String name() {
        return "jsonws";
    }

    @Override
    public String transportType() {
        return "http";
    }

    @Override
    public String defaultSerializeType() {
        return "Json";
    }

    @Override
    public Class<HttpExchange> messageType() {
        return HttpExchange.class;
    }

    @Override
    public void validateService(ProtocolConfig config, MetBindInfo<?> service) {
        // 示例接受普通发布接口，方法签名在每个请求到达时解析。
    }

    @Override
    public RouteProbeResult probe(ProtocolConfig config, HttpExchange exchange) {
        return RouteProbeResult.REJECT;
    }

    @Override
    public ProtocolSession<HttpExchange> createSession(ProtocolConfig config, ProtocolSessionContext context, //
            NetworkChannel<HttpExchange> channel, ObjLongConsumer<Payload> messages) {
        return new JsonWebSocketSession(config, context, channel, messages);
    }
}

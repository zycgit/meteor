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
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import net.hasor.meteor.protocol.connect.codec.ConnectCors;
import net.hasor.meteor.protocol.connect.codec.ConnectHttpCodec;
import net.hasor.meteor.protocol.connect.session.ConnectMountRuntime;
import net.hasor.meteor.protocol.connect.session.ConnectSession;
import net.hasor.meteor.protocol.grpc.binding.GrpcServiceBinding;

/** Connect 协议的 SPI 入口，按挂载点组装消息转换与调用会话。 */
public final class ConnectProtocol implements HttpProtocolProvider {
    private final Map<ProtocolConfig, ConnectMountRuntime> runtimes = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "connect";
    }

    @Override
    public String defaultSerialize() {
        return "Protobuf";
    }

    @Override
    public ProtocolBinding prepareBinding(ProtocolConfig config, MetContext context, ProtocolMeta meta) {
        new ConnectCors(config);
        GrpcServiceBinding definition = ConnectHttpCodec.prepareServiceDefinition(config, meta);
        if (definition == null) {
            return null;
        }

        // 静态定义和契约描述使用同一次端点准备结果。
        ObjectNode description = meta.createServiceDescription(config, this.defaultSerialize());
        definition.describeContract(description);
        return meta.build(config, definition, description);
    }

    @Override
    public ProtocolSession<HttpExchange> createSession(ProtocolConfig config, ProtocolSessionContext context, //
            NetworkChannel<HttpExchange> channel, ObjLongConsumer<Payload> messages) {
        RouteMountIndex routes = context.routes();
        ConnectMountRuntime runtime = this.runtimes.computeIfAbsent(config, key -> {
            return new ConnectMountRuntime(key, routes);
        });
        runtime.retain(context.metContext());
        try {
            return new ConnectSession(context, channel, messages, runtime);
        } catch (RuntimeException | Error failure) {
            runtime.release();
            throw failure;
        }
    }
}

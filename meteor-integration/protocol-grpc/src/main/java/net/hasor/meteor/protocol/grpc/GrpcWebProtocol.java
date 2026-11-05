/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ObjLongConsumer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.connector.ProtocolSession;
import net.hasor.meteor.connector.ProtocolServiceRouting;
import net.hasor.meteor.connector.ProtocolSessionContext;
import net.hasor.meteor.connector.RouteMountIndex;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.HttpProtocolProvider;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.domain.ProtocolBinding;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.protocol.grpc.binding.GrpcServiceBinding;
import net.hasor.meteor.protocol.grpc.codec.GrpcWebCors;
import net.hasor.meteor.protocol.grpc.session.GrpcMountRuntime;
import net.hasor.meteor.protocol.grpc.session.GrpcWebSession;

/** gRPC-Web 的 HTTP 挂载入口，复用 gRPC 服务映射与调用生命周期。 */
public final class GrpcWebProtocol implements HttpProtocolProvider, ProtocolServiceRouting {
    private final Map<ProtocolConfig, GrpcMountRuntime> runtimes = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "grpc-web";
    }

    @Override
    public String defaultSerialize() {
        return "Protobuf";
    }

    @Override
    public List<String> serviceKey(MetBindInfo<?> service, ProtocolBinding binding) {
        return List.of(((GrpcServiceBinding) binding.getDefinition()).serviceName());
    }

    @Override
    public ProtocolBinding prepareBinding(ProtocolConfig config, MetContext context, ProtocolMeta meta) {
        GrpcMountRuntime.validateConfiguration(config);
        new GrpcWebCors(config);
        GrpcServiceBinding definition = GrpcServiceBinding.prepare(config, meta, this.defaultSerialize());
        if (definition == null) {
            return null;
        }
        for (Method method : definition.methods()) {
            if (definition.shape(method).streamingRequest()) {
                return null;
            }
        }
        // 从同一份端点定义导出契约，一次创建完整发布绑定。
        ObjectNode description = meta.createServiceDescription(config, this.defaultSerialize());
        definition.describeContract(description);
        return meta.build(config, definition, description);
    }

    @Override
    public ProtocolSession<HttpExchange> createSession(ProtocolConfig config, ProtocolSessionContext context, //
            NetworkChannel<HttpExchange> connection, ObjLongConsumer<Payload> messages) {
        RouteMountIndex routes = context.routes();
        GrpcMountRuntime runtime = this.runtimes.computeIfAbsent(config, key -> new GrpcMountRuntime(key, routes));
        runtime.retain(context.metContext());
        try {
            return new GrpcWebSession(config, context, connection, messages, runtime);
        } catch (RuntimeException | Error failure) {
            runtime.release();
            throw failure;
        }
    }
}

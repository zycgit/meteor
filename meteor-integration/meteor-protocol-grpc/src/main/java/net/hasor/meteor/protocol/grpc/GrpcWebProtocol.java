/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ObjLongConsumer;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetMessage;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.protocol.grpc.codec.GrpcMethodShape;
import net.hasor.meteor.protocol.grpc.codec.GrpcWebCors;
import net.hasor.meteor.protocol.grpc.session.GrpcMountRuntime;
import net.hasor.meteor.protocol.grpc.session.GrpcWebSession;

/** gRPC-Web 的 HTTP 挂载入口，复用 gRPC 服务映射与调用生命周期。 */
public final class GrpcWebProtocol implements ProtocolFactory<HttpExchange> {
    private final Map<ProtocolConfig, GrpcMountRuntime> runtimes = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "grpc-web";
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
    public RouteProbeResult probe(ProtocolConfig config, HttpExchange message) {
        return RouteProbeResult.REJECT;
    }

    @Override
    public void validateService(ProtocolConfig config, MetBindInfo<?> service) {
        // 在发布入口拒绝浏览器协议不能表达的消息与请求流。
        if (service.isMessage()) {
            throw new IllegalArgumentException("gRPC-Web does not support message services");
        }
        for (Method method : MetRuntimeUtils.getServiceMethods(service.getBindType())) {
            if (method.isBridge() || method.isSynthetic()) {
                continue;
            }
            if (method.isAnnotationPresent(MetMessage.class)) {
                throw new IllegalArgumentException("gRPC-Web does not support message methods");
            }
            if (new GrpcMethodShape(method).streamingRequest()) {
                throw new IllegalArgumentException("gRPC-Web does not support client or bidirectional streaming");
            }
        }

        String coding = service.getSerializeType();
        if (StringUtils.isBlank(coding)) {
            coding = config.option("serializeType", this.defaultSerializeType());
        }
        boolean provider = service.getServiceType() == MetServiceType.Provider;
        boolean protobuf = StringUtils.equalsIgnoreCase(coding, "Protobuf");
        if (provider && protobuf && !(service.getBindResource() instanceof GrpcProtoResource)) {
            throw new IllegalArgumentException("Protobuf service must bind proto file.");
        }
        new GrpcWebCors(config);
        this.runtimes.computeIfAbsent(config, GrpcMountRuntime::new);
    }

    @Override
    public ProtocolSession<HttpExchange> createSession(ProtocolConfig config, ProtocolSessionContext context, //
            NetworkChannel<HttpExchange> connection, ObjLongConsumer<Payload> messages) {
        GrpcMountRuntime runtime = this.runtimes.computeIfAbsent(config, GrpcMountRuntime::new);
        runtime.retain(context.metContext());
        try {
            return new GrpcWebSession(config, context, connection, messages, runtime);
        } catch (RuntimeException | Error failure) {
            runtime.release();
            throw failure;
        }
    }
}

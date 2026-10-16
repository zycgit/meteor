/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.session;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ObjLongConsumer;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;

/** Grpc SPI provider using the same session contract as every other protocol. */
public final class GrpcProtocol implements ProtocolFactory<HttpExchange> {
    private final Map<ProtocolConfig, GrpcMountRuntime> runtimes = new ConcurrentHashMap<>();

    @Override
    public RouteProbeResult probe(ProtocolConfig config, HttpExchange message) {
        return RouteProbeResult.REJECT;
    }

    public String name() {
        return "grpc";
    }

    @Override
    public String defaultSerializeType() {
        return "Protobuf";
    }

    public String transportType() {
        return "http";
    }

    public Class<HttpExchange> messageType() {
        return HttpExchange.class;
    }

    @Override
    public void validateService(ProtocolConfig config, MetBindInfo<?> service) {
        this.runtime(config);
        if (service.getServiceType() != MetServiceType.Provider) {
            return;
        }

        // 服务显式选择优先，其次使用挂载配置与协议默认值。
        String coding = service.getSerializeType();
        if (StringUtils.isBlank(coding)) {
            coding = config.option("serializeType", this.defaultSerializeType());
        }

        if (!StringUtils.equalsIgnoreCase(coding, "Protobuf")) {
            return;
        }

        if (!(service.getBindResource() instanceof GrpcProtoResource)) {
            throw new IllegalArgumentException("Protobuf service must bind proto file.");
        }
    }

    public ProtocolSession<HttpExchange> createSession(ProtocolConfig config, ProtocolSessionContext context, //
            NetworkChannel<HttpExchange> connection, ObjLongConsumer<Payload> messages) {
        GrpcMountRuntime runtime = this.runtime(config).retain(context.metContext());
        try {
            return new GrpcSession(context, connection, messages, runtime);
        } catch (RuntimeException | Error failure) {
            runtime.release();
            throw failure;
        }
    }

    private GrpcMountRuntime runtime(ProtocolConfig config) {
        return this.runtimes.computeIfAbsent(config, GrpcMountRuntime::new);
    }
}

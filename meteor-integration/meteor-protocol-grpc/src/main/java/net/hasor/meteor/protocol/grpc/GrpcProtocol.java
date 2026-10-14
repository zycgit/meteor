/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.protocol.ProtocolContext;
import net.hasor.meteor.connector.protocol.ProtocolFactory;
import net.hasor.meteor.connector.protocol.ProtocolSession;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.RouteMatch;
import net.hasor.meteor.connector.transport.http.HttpExchange;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.domain.payload.Payload;

/** Grpc SPI provider using the same session contract as every other protocol. */
public final class GrpcProtocol implements ProtocolFactory<HttpExchange> {
    private final Map<ProtocolConfig, GrpcClientPolicy> policies = new ConcurrentHashMap<>();

    @Override
    public String scheme() {
        return this.name();
    }

    @Override
    public RouteMatch probe(ProtocolConfig config, HttpExchange message) {
        return RouteMatch.REJECT;
    }

    public String name() {
        return "grpc";
    }

    @Override
    public String defaultSerializeType() {
        return "Protobuf";
    }

    public String transport() {
        return "http";
    }

    public Class<HttpExchange> messageType() {
        return HttpExchange.class;
    }

    @Override
    public void validateService(ProtocolConfig config, MetBindInfo<?> service) {
        this.policies.computeIfAbsent(config, GrpcClientPolicy::new);
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

    public ProtocolSession<HttpExchange> create(ProtocolConfig config, ProtocolContext context, NetworkChannel<HttpExchange> connection, BiConsumer<Long, Payload> messages) {
        GrpcClientPolicy policy = this.policies.computeIfAbsent(config, GrpcClientPolicy::new);
        return new GrpcSession(config, context, connection, messages, policy);
    }
}

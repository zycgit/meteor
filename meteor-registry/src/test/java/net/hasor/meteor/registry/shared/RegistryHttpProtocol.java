/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.shared;
import java.util.function.BiConsumer;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.protocol.ProtocolContext;
import net.hasor.meteor.connector.protocol.ProtocolFactory;
import net.hasor.meteor.connector.protocol.ProtocolSession;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.RouteMatch;
import net.hasor.meteor.connector.transport.http.HttpExchange;
import net.hasor.meteor.domain.payload.Payload;

/** 注册目录场景使用的 HTTP 挂载；只声明协议元信息，不提供业务报文编解码。 */
public final class RegistryHttpProtocol implements ProtocolFactory<HttpExchange> {
    @Override
    public String name() {
        return "registry-http";
    }

    @Override
    public String scheme() {
        return this.name();
    }

    @Override
    public String transport() {
        return "http";
    }

    @Override
    public String defaultSerializeType() {
        return "Java";
    }

    @Override
    public Class<HttpExchange> messageType() {
        return HttpExchange.class;
    }

    @Override
    public void validateService(ProtocolConfig config, MetBindInfo<?> service) {
    }

    @Override
    public RouteMatch probe(ProtocolConfig config, HttpExchange message) {
        return RouteMatch.REJECT;
    }

    @Override
    public ProtocolSession<HttpExchange> create(ProtocolConfig config, ProtocolContext context, //
            NetworkChannel<HttpExchange> connection, BiConsumer<Long, Payload> messages) {
        throw new UnsupportedOperationException("Registry metadata fixture does not exchange business messages");
    }
}

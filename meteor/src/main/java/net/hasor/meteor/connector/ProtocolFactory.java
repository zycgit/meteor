/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.function.BiConsumer;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.domain.payload.Payload;

/** Protocol SPI. Transport requirements and protocol execution use one contract. */
public interface ProtocolFactory<M> {
    String name();

    String transport();

    /** Default serializer for calls sent through this protocol. */
    String defaultSerializeType();

    Class<M> messageType();

    /** 服务发布时校验匹配当前挂载的声明，检查失败将撤销本次登记。 */
    void validateService(ProtocolConfig config, MetBindInfo<?> service);

    /** Inspect data without consuming it when the transport needs content-based routing. */
    RouteMatch probe(ProtocolConfig config, M message);

    ProtocolSession<M> create(ProtocolConfig config, ProtocolContext context, //
            NetworkChannel<M> connection, BiConsumer<Long, Payload> messages);
}

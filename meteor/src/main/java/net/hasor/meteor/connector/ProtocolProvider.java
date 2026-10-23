/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.function.ObjLongConsumer;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.domain.ProtocolBinding;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.domain.payload.Payload;

/** Protocol SPI for metadata, service binding preparation and session creation. */
public interface ProtocolProvider<M> {
    String name();

    String transportType();

    /** Default serializer for calls sent through this protocol. */
    String defaultSerialize();

    Class<M> messageType();

    /** 校验服务并准备定义与描述，通过 meta.build 创建完整绑定；null 表示拒绝，异常撤销本次登记。 */
    ProtocolBinding prepareBinding(ProtocolConfig config, MetContext context, ProtocolMeta meta);

    ProtocolSession<M> createSession(ProtocolConfig config, ProtocolSessionContext context, //
            NetworkChannel<M> connection, ObjLongConsumer<Payload> messages);
}

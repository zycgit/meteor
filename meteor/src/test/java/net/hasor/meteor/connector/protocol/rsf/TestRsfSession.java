/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.protocol.rsf;
import java.util.function.BiConsumer;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.protocol.ProtocolContext;
import net.hasor.meteor.connector.protocol.ProtocolSession;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.domain.payload.Payload;

/** 仅在测试源码中暴露包内会话的正式协议契约。 */
public final class TestRsfSession {
    private TestRsfSession() {
    }

    public static ProtocolSession<byte[]> create( //
            ProtocolConfig config, ProtocolContext context, //
            NetworkChannel<byte[]> connection, BiConsumer<Long, Payload> messages) {
        return new RsfSession(config, context, connection, messages);
    }
}

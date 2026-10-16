/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.protocol.rsf;
import java.util.function.ObjLongConsumer;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.ProtocolSession;
import net.hasor.meteor.connector.ProtocolSessionContext;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.domain.payload.Payload;

/** 仅在测试源码中暴露包内会话的正式协议契约。 */
public final class TestRsfSession {
    private TestRsfSession() {
    }

    public static ProtocolSession<byte[]> createSession( //
            ProtocolConfig config, ProtocolSessionContext context, //
            NetworkChannel<byte[]> connection, ObjLongConsumer<Payload> messages) {
        return new RsfSession(config, context, connection, messages);
    }
}

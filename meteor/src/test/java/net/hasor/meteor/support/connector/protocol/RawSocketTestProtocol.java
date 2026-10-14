/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.support.connector.protocol;
import java.util.function.BiConsumer;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.protocol.ProtocolContext;
import net.hasor.meteor.connector.protocol.ProtocolFactory;
import net.hasor.meteor.connector.protocol.ProtocolSession;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.RouteMatch;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;

/** 只写原始字节的测试协议，用于观察真实 TCP 写入与关闭。 */
public class RawSocketTestProtocol implements ProtocolFactory<byte[]> {
    public static final int WRITE_SIZE = 8 * 1024 * 1024;

    @Override
    public String name() {
        return "rawprotocol";
    }

    @Override
    public String scheme() {
        return this.name();
    }

    @Override
    public String transport() {
        return "tcp";
    }

    @Override
    public Class<byte[]> messageType() {
        return byte[].class;
    }

    @Override
    public RouteMatch probe(ProtocolConfig config, byte[] message) {
        return RouteMatch.REJECT;
    }

    @Override
    public String defaultSerializeType() {
        return "Java";
    }

    @Override
    public void validateService(ProtocolConfig config, MetBindInfo<?> service) {
    }

    @Override
    public ProtocolSession<byte[]> create( //
            ProtocolConfig config, //
            ProtocolContext context, //
            NetworkChannel<byte[]> connection, //
            BiConsumer<Long, Payload> messages) {
        return new ProtocolSession<byte[]>() {
            private final BasicFuture<Void> ready = new BasicFuture<>();

            @Override
            public Future<Void> ready() {
                return this.ready;
            }

            @Override
            public void connected() {
                this.ready.completed(null);
            }

            @Override
            public void receive(byte[] bytes) {
            }

            @Override
            public Future<Void> send(Payload message) {
                // 普通请求写一个字节，非零请求号用于制造尚未排空的大写入。
                RequestPayload request = (RequestPayload) message;
                int size = 1;
                if (request.getRequestID() != 0) {
                    size = WRITE_SIZE;
                }
                byte[] bytes = new byte[size];
                bytes[0] = 37;
                return connection.write(bytes);
            }

            @Override
            public void prepareDrain() {
            }

            @Override
            public void closed(Throwable cause) {
                this.ready.failed(cause);
            }
        };
    }
}

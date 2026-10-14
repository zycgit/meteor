/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.support.connector.protocol;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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
import net.hasor.meteor.domain.payload.ResponsePayload;

/** 报文类型、请求号和 UTF-8 正文组成的 UDP 测试对端协议。 */
public final class UdpTestProtocol implements ProtocolFactory<byte[]> {
    @Override
    public String name() {
        return "testprotocol";
    }

    @Override
    public String scheme() {
        return this.name();
    }

    @Override
    public String transport() {
        return "udp";
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
            public void receive(byte[] datagram) throws Exception {
                // 每个 UDP 报文必须包含完整头部，不能拼接或缓存残缺数据。
                if (datagram.length < 9) {
                    throw new IOException("Incomplete datagram");
                }
                ByteBuffer bytes = ByteBuffer.wrap(datagram);
                int type = bytes.get();
                long id = bytes.getLong();
                if (type != 1 && type != 2) {
                    throw new IOException("Unknown datagram type");
                }

                // 解码正文后，按报文方向向连接器投递独立消息。
                String value = new String(datagram, 9, datagram.length - 9, StandardCharsets.UTF_8);
                Payload payload;
                if (type == 1) {
                    payload = request(id, value);
                } else {
                    payload = response(id, value);
                }
                messages.accept(id, payload);
            }

            @Override
            public Future<Void> send(Payload payload) {
                // 一次协议发送只提交一个完整 UDP 报文。
                byte[] encoded;
                if (payload instanceof RequestPayload request) {
                    encoded = packet(1, request.getRequestID(), request.getTargetMethod());
                } else {
                    ResponsePayload response = (ResponsePayload) payload;
                    String returnValue = String.valueOf(response.getReturnData());
                    encoded = packet(2, response.getRequestID(), returnValue);
                }
                return connection.write(encoded);
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

    public static byte[] packet(int type, long id, String value) {
        byte[] text = value.getBytes(StandardCharsets.UTF_8);
        ByteBuffer datagram = ByteBuffer.allocate(9 + text.length);
        datagram.put((byte) type);
        datagram.putLong(id);
        datagram.put(text);
        return datagram.array();
    }

    public static RequestPayload request(long id, String value) {
        RequestPayload request = new RequestPayload();
        request.setRequestID(id);
        request.setTargetMethod(value);
        return request;
    }

    public static ResponsePayload response(long id, String value) {
        ResponsePayload response = new ResponsePayload();
        response.setRequestID(id);
        response.setReturnData(value);
        return response;
    }
}

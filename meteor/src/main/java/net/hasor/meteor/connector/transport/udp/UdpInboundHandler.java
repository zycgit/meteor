/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.udp;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.meteor.connector.transport.InboundMessage;

/** Copies engine-owned buffers into application events. */
final class UdpInboundHandler implements ProtoHandler<ByteBuf, Object> {
    private final String route;

    public UdpInboundHandler(String route) {
        this.route = route;
    }

    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> source, ProtoSndQueue<Object> target) {
        while (source.hasMore() && target.hasSlot()) {
            ByteBuf buffer = source.takeMessage();
            try {
                byte[] bytes = new byte[buffer.readableBytes()];
                buffer.readBytes(bytes);
                target.offerMessage(new InboundMessage<>(this.route, bytes));
            } finally {
                buffer.release();
            }
        }
        return ProtoStatus.Next;
    }
}

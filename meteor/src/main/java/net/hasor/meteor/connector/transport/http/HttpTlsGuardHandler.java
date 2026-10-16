/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http;
import java.io.IOException;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.codec.ssl.SslContext;

/** Rejects an HTTPS endpoint before HTTP data can pass through a plaintext protocol stack. */
final class HttpTlsGuardHandler implements ProtoHandler<ByteBuf, ByteBuf> {
    @Override
    public void onActive(ProtoContext context) throws IOException {
        if (context.context(SslContext.class) == null) {
            throw new IOException("HTTPS requires a TLS transport pipeline");
        }
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> source, ProtoSndQueue<ByteBuf> target) {
        while (source.hasMore() && target.hasSlot()) {
            target.offerMessage(source.takeMessage());
        }
        return ProtoStatus.Next;
    }
}

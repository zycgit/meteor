/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.udp;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.SoChannel;
import net.hasor.neta.channel.SoEvent;
import net.hasor.neta.codec.ssl.*;

/** Configures the JSSE DTLS engine with a datagram-safe packet limit. */
final class DtlsDuplex extends SslDuplex {
    private final SslConfig config;
    private final int       packetSize;

    DtlsDuplex(SslConfig config, int packetSize) {
        super(config);
        this.config = config;
        this.packetSize = packetSize;
    }

    @Override
    public void onInit(String name, int rcvSize, int sndSize, ProtoContext context) throws Throwable {
        if (this.config.getProvider() != SslProvider.JSSE) {
            throw new UnsupportedOperationException(this.config.getProvider() + " Unsupported.");
        }

        SoChannel<?> channel = context.getChannel();
        DtlsSslContext ssl = new DtlsSslContext(channel, name, context, this.config, //
                channel.isClient(), this.packetSize);
        context.context(SslContext.class, ssl);
    }

    @Override
    public boolean onEvent(ProtoContext context, SoEvent event, boolean isRcv) throws Throwable {
        boolean propagate = super.onEvent(context, event, isRcv);
        if (isRcv && event.getData() instanceof SslCloseNotifyEvent) {
            // UDP cannot half-close output; reply with close_notify through the full close flow.
            context.getChannel().close();
        }
        return propagate;
    }
}

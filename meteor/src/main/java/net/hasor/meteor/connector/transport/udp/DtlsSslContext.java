/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.udp;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.net.ssl.*;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.SoChannel;
import net.hasor.neta.codec.ssl.JdkSslContext;
import net.hasor.neta.codec.ssl.SslConfig;

/** Applies the endpoint's DTLS datagram limit to each newly created JSSE engine. */
final class DtlsSslContext extends JdkSslContext {
    private static final ByteBuffer EMPTY           = ByteBuffer.allocate(0);
    private static final int        MAX_WRAP_ROUNDS = 32;
    private final        int        packetSize;
    private volatile     SSLEngine  engine;

    DtlsSslContext(SoChannel<?> channel, String name, ProtoContext context, //
            SslConfig config, boolean clientMode, int packetSize) throws Exception {
        super(channel, name, context, config, clientMode);
        this.packetSize = packetSize;
    }

    @Override
    protected SSLEngine configSslEngine(SSLContext context, SSLEngine engine) {
        SSLEngine configured = super.configSslEngine(context, engine);
        SSLParameters parameters = configured.getSSLParameters();
        parameters.setMaximumPacketSize(this.packetSize);
        configured.setSSLParameters(parameters);
        this.engine = configured;
        return configured;
    }

    List<Future<?>> retransmitFlight() throws SSLException {
        NetChannel channel = (NetChannel) this.protoCtx.getChannel();
        List<byte[]> datagrams;
        synchronized (channel) {
            datagrams = this.createRetransmission();
        }

        // Sending while holding NetChannel's pipeline lock only queues the packet. Release it first
        // so ProtoContext can start the UDP write task immediately.
        List<Future<?>> writes = new ArrayList<>(datagrams.size());
        for (byte[] datagram : datagrams) {
            writes.add(this.protoCtx.sendEncoded(ByteBuf.wrap(datagram)));
        }
        return writes;
    }

    private List<byte[]> createRetransmission() throws SSLException {
        SSLEngine current = this.engine;
        if (current == null || this.isReady()) {
            return Collections.emptyList();
        }

        SSLEngineResult.HandshakeStatus status = current.getHandshakeStatus();
        boolean awaitingPeer = status == SSLEngineResult.HandshakeStatus.NEED_UNWRAP || //
                status == SSLEngineResult.HandshakeStatus.NEED_UNWRAP_AGAIN;
        if (!awaitingPeer) {
            return Collections.emptyList();
        }

        // JSSE DTLS retransmits its current flight when wrap(empty) is called while awaiting input.
        List<byte[]> datagrams = new ArrayList<>();
        for (int round = 0; round < MAX_WRAP_ROUNDS; round++) {
            int enginePacketSize = current.getSession().getPacketBufferSize();
            ByteBuffer packet = ByteBuffer.allocate(Math.max(this.packetSize, enginePacketSize));
            SSLEngineResult result = current.wrap(EMPTY.duplicate(), packet);
            if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                throw new SSLException("DTLS retransmission exceeds the configured packet size");
            }
            if (result.getStatus() == SSLEngineResult.Status.CLOSED) {
                throw new SSLException("DTLS engine closed during handshake retransmission");
            }

            if (result.bytesProduced() > 0) {
                packet.flip();
                byte[] datagram = new byte[packet.remaining()];
                packet.get(datagram);
                datagrams.add(datagram);
            }

            status = result.getHandshakeStatus();
            if (status == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                this.runTasks(current);
                status = current.getHandshakeStatus();
            }
            if (status != SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                return datagrams;
            }
        }

        throw new SSLException("DTLS retransmission did not reach an input boundary");
    }

    private void runTasks(SSLEngine current) {
        Runnable task;
        while ((task = current.getDelegatedTask()) != null) {
            task.run();
        }
    }
}

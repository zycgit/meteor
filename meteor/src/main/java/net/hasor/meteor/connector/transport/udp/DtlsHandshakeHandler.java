/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.udp;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.connector.transport.TransportRuntimeServices;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.SoEvent;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.codec.ssl.SslHandshakeEvent;
import net.hasor.neta.codec.ssl.SslHandshakePhaseEnum;

/** Releases queued application datagrams only after the initial DTLS handshake completes. */
final class DtlsHandshakeHandler implements ProtoHandler<ByteBuf, ByteBuf> {
    private final UdpChannel               channel;
    private final TransportRuntimeServices runtimeServices;
    private final long                     retryMillis;
    private final long                     timeoutNanos;
    private       DtlsSslContext           ssl;
    private       Cancellable              scheduled;
    private       long                     deadline;
    private       boolean                  active;

    DtlsHandshakeHandler(UdpChannel channel, TransportRuntimeServices runtimeServices, long retryMillis, //
            long timeoutMillis) {
        this.channel = channel;
        this.runtimeServices = runtimeServices;
        this.retryMillis = retryMillis;
        this.timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    }

    @Override
    public boolean onEvent(ProtoContext context, SoEvent event) {
        if (event.getData() instanceof SslHandshakeEvent handshake) {
            boolean initial = handshake.isInitial();
            if (initial && handshake.getPhase() == SslHandshakePhaseEnum.START) {
                this.start((DtlsSslContext) handshake.getContext());
            } else if (initial && handshake.getPhase() == SslHandshakePhaseEnum.COMPLETE) {
                this.finish();
                this.channel.securityReady();
            }
        }
        return true;
    }

    @Override
    public void onClose(ProtoContext context) {
        this.finish();
    }

    @Override
    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<ByteBuf> source, ProtoSndQueue<ByteBuf> target) {
        while (source.hasMore() && target.hasSlot()) {
            target.offerMessage(source.takeMessage());
        }
        return ProtoStatus.Next;
    }

    private synchronized void start(DtlsSslContext ssl) {
        if (this.active) {
            return;
        }

        this.ssl = ssl;
        this.deadline = System.nanoTime() + this.timeoutNanos;
        this.active = true;
        this.scheduleNext();
    }

    private synchronized void finish() {
        this.active = false;
        if (this.scheduled != null) {
            this.scheduled.cancel();
            this.scheduled = null;
        }
    }

    private synchronized void scheduleNext() {
        if (!this.active) {
            return;
        }

        long remainingNanos = this.deadline - System.nanoTime();
        long remainingMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
        long delay = Math.min(this.retryMillis, remainingMillis);
        this.scheduled = this.runtimeServices.schedule(this::dispatchRetry, delay);
    }

    private void dispatchRetry() {
        try {
            this.channel.execute(this::retry);
        } catch (Throwable failure) {
            this.fail(failure);
        }
    }

    private void retry() {
        boolean expired;
        synchronized (this) {
            if (!this.active) {
                return;
            }
            this.scheduled = null;
            expired = System.nanoTime() >= this.deadline;
        }
        if (expired) {
            this.fail(new TimeoutException("DTLS handshake timed out"));
            return;
        }

        try {
            List<Future<?>> writes = this.ssl.retransmitFlight();
            for (Future<?> write : writes) {
                write.onFailed(done -> this.fail(done.getCause()));
            }
        } catch (Throwable failure) {
            this.fail(failure);
            return;
        }

        this.scheduleNext();
    }

    private void fail(Throwable failure) {
        Cancellable pending;
        synchronized (this) {
            if (!this.active) {
                return;
            }
            this.active = false;
            pending = this.scheduled;
            this.scheduled = null;
        }
        if (pending != null) {
            pending.cancel();
        }
        this.channel.securityFailed(failure);
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.transport.udp;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.function.Function;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.ProtoBuildContext;
import net.hasor.neta.channel.ProtoInitializer;
import net.hasor.neta.channel.SubscribeMode;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.connector.transport.*;

/**
 * A UDP peer view: one write is one datagram; no RPC state, retransmission or stream assembly.
 */
public final class UdpConnection implements NetworkChannel<byte[]> {
    private final    NetChannel        socket;
    private final    InterAddress      remote;
    private final    Executor          executor;
    private final    int               maxDatagramSize;
    private final    BasicFuture<Void> closed = new BasicFuture<>();
    private          int               writes;
    private          boolean           draining;
    private volatile Throwable         failure;

    private UdpConnection(NetChannel socket, InterAddress remote, Executor executor, int maxDatagramSize) {
        this.socket = socket;
        this.remote = remote;
        this.executor = executor;
        this.maxDatagramSize = maxDatagramSize;
    }

    /**
     * Called by the endpoint assembler after transport stages have been added.
     */
    public static UdpConnection attach(ProtoBuildContext stack, InterAddress remote, Executor executor, int maxDatagramSize, ChannelFactory<byte[]> factory) throws Exception {
        if (maxDatagramSize < 1 || maxDatagramSize > 65507) {
            throw new IllegalArgumentException("maxDatagramSize must be between 1 and 65507");
        }

        NetChannel socket = (NetChannel) stack.getChannel();
        UdpConnection connection = new UdpConnection(socket, remote, executor, maxDatagramSize);
        ChannelListener<byte[]> receiver = factory.create(connection);
        stack.addLastDecoder("lifecycle", new ConnectionEvents(() -> {
            connection.execute(() -> {
                try {
                    receiver.connected();
                } catch (Throwable failure) {
                    connection.fail(failure);
                }
            });
        }, () -> connection.execute(() -> {
            try {
                receiver.closed(connection.failure());
            } finally {
                connection.terminated();
            }
        }), connection::fail));

        Function<String, ProtoInitializer> branch = route -> {
            return ctx -> ctx.addLastDecoder("messages", new UdpInboundHandler(route));
        };

        if (receiver instanceof RoutedReceiver) {
            ((RoutedReceiver<byte[]>) receiver).configure(stack, branch);
        } else {
            branch.apply("").config(stack);
        }

        socket.subscribe(event -> event.isInbound() || !event.isSuccess(), SubscribeMode.SYNC, event -> {
            if (!event.isSuccess()) {
                connection.execute(() -> connection.fail(event.getError()));
                return;
            }

            InboundMessage<byte[]> message = (InboundMessage<byte[]>) event.getData();
            connection.execute(() -> {
                try {
                    if (message.message().length > maxDatagramSize) {
                        throw new IOException("Received datagram exceeds " + maxDatagramSize);
                    }
                    if (receiver instanceof RoutedReceiver) {
                        ((RoutedReceiver<byte[]>) receiver).receive(message.route(), message.message());
                    } else {
                        receiver.receive(message.message());
                    }
                } catch (Throwable failure) {
                    connection.fail(failure);
                }
            });
        });
        return connection;
    }

    @Override
    public InterAddress getLocal() {
        InetSocketAddress address = (InetSocketAddress) this.socket.getLocalAddr();
        if (address == null) {
            return null;
        } else {
            return new InterAddress(this.remote.getSchema(), address.getAddress().getHostAddress(), address.getPort(), this.remote.getFormUnit());
        }
    }

    @Override
    public InterAddress getRemote() {
        return this.remote;
    }

    @Override
    public boolean isOpen() {
        return !this.socket.isClose();
    }

    @Override
    public void execute(Runnable task) {
        this.executor.execute(task);
    }

    @Override
    public Future<Void> write(byte[] bytes) {
        BasicFuture<Void> result = new BasicFuture<>();
        if (bytes.length == 0 || bytes.length > this.maxDatagramSize) {
            result.failed(new IllegalArgumentException("Datagram size must be between 1 and " + this.maxDatagramSize));
            return result;
        }

        synchronized (this) {
            if (this.draining || !this.isOpen()) {
                result.failed(new IllegalStateException("UDP peer is closing"));
                return result;
            }
            this.writes++;
        }

        byte[] owned = bytes.clone();
        try {
            this.execute(() -> this.write(owned, result));
        } catch (Throwable error) {
            result.failed(error);
            this.writeFinished();
        }
        return result;
    }

    private void write(byte[] bytes, BasicFuture<Void> result) {
        if (result.isCancelled()) {
            this.writeFinished();
            return;
        }

        try {
            this.socket.sendData(ByteBuf.wrap(bytes)).onFinal(done -> {
                Throwable error = done.isCancelled() ? new CancellationException("Socket write cancelled") : done.getCause();
                if (error == null) {
                    result.completed(null);
                } else {
                    result.failed(error);
                }
                this.writeFinished();
            });
        } catch (Throwable error) {
            result.failed(error);
            this.writeFinished();
        }
    }

    private void writeFinished() {
        synchronized (this) {
            this.writes--;
        }
        this.checkDrain();
    }

    @Override
    public Future<Void> drainAndClose() {
        synchronized (this) {
            this.draining = true;
        }

        this.checkDrain();
        return this.closed;
    }

    private void checkDrain() {
        synchronized (this) {
            if (!this.draining || this.writes != 0 || this.closed.isDone()) {
                return;
            }
        }

        // Write completion may run inside Neta's pipeline. Drain closes on the receiver executor.
        this.execute(this::close);
    }

    @Override
    public Future<Void> close() {
        this.socket.closeNow();
        return this.closed;
    }

    private void fail(Throwable error) {
        this.failure = error;
        this.close();
    }

    private Throwable failure() {
        return this.failure == null ? new IOException("UDP connection closed") : this.failure;
    }

    private void terminated() {
        this.closed.completed(null);
    }
}

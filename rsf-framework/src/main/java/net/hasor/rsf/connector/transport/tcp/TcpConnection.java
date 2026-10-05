/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.transport.tcp;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.function.Function;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.connector.transport.*;

/**
 * A byte connection: no RPC messages, request correlation or logical stream state.
 */
public final class TcpConnection implements NetworkChannel<byte[]> {
    private final    NetChannel        socket;
    private final    InterAddress      remote;
    private final    Executor          executor;
    private final    BasicFuture<Void> closed = new BasicFuture<>();
    private          int               writes;
    private          boolean           draining;
    private volatile Throwable         failure;

    private TcpConnection(NetChannel socket, InterAddress remote, Executor executor) {
        this.socket = socket;
        this.remote = remote;
        this.executor = executor;
    }

    /**
     * Called by the endpoint assembler after transport stages have been added.
     */
    public static TcpConnection attach(ProtoBuildContext stack, InterAddress remote, Executor executor, ChannelFactory<byte[]> factory) throws Exception {
        NetChannel socket = (NetChannel) stack.getChannel();
        TcpConnection connection = new TcpConnection(socket, remote, executor);
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

        Function<String, ProtoInitializer> branch = route -> ctx -> ctx.addLastDecoder("messages", new TcpInboundHandler(route));
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
            ReadPause pause = socket.pauseRead();

            connection.execute(() -> {
                try {
                    if (receiver instanceof RoutedReceiver) {
                        ((RoutedReceiver<byte[]>) receiver).receive(message.route(), message.message());
                    } else {
                        receiver.receive(message.message());
                    }
                } catch (Throwable failure) {
                    connection.fail(failure);
                } finally {
                    socket.resumeRead(pause);
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
        synchronized (this) {
            if (this.draining || !this.isOpen()) {
                result.failed(new IllegalStateException("Byte connection is closing"));
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
        return this.failure == null ? new IOException("TCP connection closed") : this.failure;
    }

    private void terminated() {
        this.closed.completed(null);
    }
}

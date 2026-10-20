/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.udp;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.SocketAddressUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.NetChannel;

/**
 * A UDP peer view: one application write is one datagram; no RPC request state or stream assembly.
 */
final class UdpChannel implements NetworkChannel<byte[]> {
    private final    NetChannel          socket;
    private final    InterAddress        remote;
    private final    Executor            executor;
    private final    int                 maxDatagramSize;
    private final    boolean             orderedWrites;
    private final    Deque<PendingWrite> pendingWrites = new ArrayDeque<>();
    private final    BasicFuture<Void>   closed        = new BasicFuture<>();
    private          int                 writes;
    private          boolean             draining;
    private          boolean             writeActive;
    private          boolean             writeReady;
    private volatile Throwable           failure;

    UdpChannel(NetChannel socket, InterAddress remote, Executor executor, int maxDatagramSize, //
            boolean orderedWrites) {
        this.socket = socket;
        this.remote = remote;
        this.executor = executor;
        this.maxDatagramSize = maxDatagramSize;
        this.orderedWrites = orderedWrites;
        this.writeReady = !orderedWrites;
    }

    @Override
    public InterAddress getLocal() {
        return SocketAddressUtils.local(this.remote, this.socket.getLocalAddr());
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
        if (this.orderedWrites) {
            this.enqueueWrite(bytes, result);
            return;
        }

        this.send(bytes, result, null);
    }

    private void enqueueWrite(byte[] bytes, BasicFuture<Void> result) {
        boolean start;
        synchronized (this.pendingWrites) {
            this.pendingWrites.addLast(new PendingWrite(bytes, result));
            start = this.writeReady && !this.writeActive;
            if (start) {
                this.writeActive = true;
            }
        }
        if (start) {
            this.sendNext();
        }
    }

    void securityReady() {
        boolean start;
        synchronized (this.pendingWrites) {
            this.writeReady = true;
            start = !this.writeActive && !this.pendingWrites.isEmpty();
            if (start) {
                this.writeActive = true;
            }
        }
        if (start) {
            try {
                this.execute(this::sendNext);
            } catch (Throwable failure) {
                this.failPendingWrites(failure);
            }
        }
    }

    void securityFailed(Throwable failure) {
        this.failure = failure;
        this.failPendingWrites(failure);
        this.close();
    }

    private void sendNext() {
        while (true) {
            PendingWrite next;
            synchronized (this.pendingWrites) {
                next = this.pendingWrites.pollFirst();
                if (next == null) {
                    this.writeActive = false;
                    return;
                }
            }

            if (next.result.isCancelled()) {
                this.writeFinished();
                continue;
            }
            this.send(next.bytes, next.result, this::continueWrites);
            return;
        }
    }

    private void continueWrites() {
        try {
            this.execute(this::sendNext);
        } catch (Throwable failure) {
            this.failPendingWrites(failure);
        }
    }

    private void failPendingWrites(Throwable failure) {
        Deque<PendingWrite> rejected = new ArrayDeque<>();
        synchronized (this.pendingWrites) {
            rejected.addAll(this.pendingWrites);
            this.pendingWrites.clear();
            this.writeActive = false;
        }
        for (PendingWrite pending : rejected) {
            pending.result.failed(failure);
            this.writeFinished();
        }
    }

    private void send(byte[] bytes, BasicFuture<Void> result, Runnable completion) {
        if (result.isCancelled()) {
            this.writeFinished();
            if (completion != null) {
                completion.run();
            }
            return;
        }

        try {
            this.socket.sendData(ByteBuf.wrap(bytes)).onFinal(done -> {
                Throwable error = done.getCause();
                if (done.isCancelled()) {
                    error = new CancellationException("Socket write cancelled");
                }
                if (error == null) {
                    result.completed(null);
                } else {
                    result.failed(error);
                }
                this.writeFinished();
                if (completion != null) {
                    completion.run();
                }
            });
        } catch (Throwable error) {
            result.failed(error);
            this.writeFinished();
            if (completion != null) {
                completion.run();
            }
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
            if (!this.draining || this.writes != 0 || this.closed.isDone() || !this.isOpen()) {
                return;
            }
        }

        // Write completion may run inside Neta's pipeline. Graceful close emits protocol farewell data.
        this.execute(this::closeAfterDrain);
    }

    @Override
    public Future<Void> close() {
        this.socket.closeNow();
        return this.closed;
    }

    private void closeAfterDrain() {
        this.socket.close();
    }

    void fail(Throwable error) {
        this.failure = error;
        this.close();
    }

    Throwable failure() {
        return this.failure == null ? new IOException("UDP channel closed") : this.failure;
    }

    void terminated() {
        this.failPendingWrites(this.failure());
        this.closed.completed(null);
    }

    private static final class PendingWrite {
        private final byte[]            bytes;
        private final BasicFuture<Void> result;

        private PendingWrite(byte[] bytes, BasicFuture<Void> result) {
            this.bytes = bytes;
            this.result = result;
        }
    }
}

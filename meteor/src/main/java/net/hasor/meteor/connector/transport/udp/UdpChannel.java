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
import java.util.Collections;
import java.util.Deque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.function.Function;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.RouteTable;
import net.hasor.meteor.connector.transport.*;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.*;
import net.hasor.neta.channel.routing.PartitionKey;

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

    private UdpChannel(NetChannel socket, InterAddress remote, Executor executor, int maxDatagramSize, //
            boolean orderedWrites) {
        this.socket = socket;
        this.remote = remote;
        this.executor = executor;
        this.maxDatagramSize = maxDatagramSize;
        this.orderedWrites = orderedWrites;
        this.writeReady = !orderedWrites;
    }

    /**
     * Called after transport stages have been added.
     */
    static UdpChannel attach(ProtoBuildContext stack, InterAddress remote, Executor executor, int maxDatagramSize, //
            boolean orderedWrites, TransportRuntimeServices runtimeServices, long retryMillis, long timeoutMillis, //
            RouteTable<byte[]> routes, String initialMountId, //
            ChannelListenerFactory<byte[]> factory) throws Exception {
        if (maxDatagramSize < 1 || maxDatagramSize > 65507) {
            throw new IllegalArgumentException("maxDatagramSize must be between 1 and 65507");
        }

        NetChannel socket = (NetChannel) stack.getChannel();
        UdpChannel channel = new UdpChannel(socket, remote, executor, maxDatagramSize, orderedWrites);
        ChannelListenerRelay<byte[]> listenerRelay = new ChannelListenerRelay<>(channel::terminated);

        // 先安装物理连接生命周期，ChannelListenerFactory 此后取得的通道已经能够观察关闭。
        Runnable connected = () -> {
            channel.execute(() -> {
                try {
                    listenerRelay.connected();
                } catch (Throwable failure) {
                    channel.fail(failure);
                }
            });
        };

        Runnable closed = () -> {
            channel.execute(() -> listenerRelay.closed(channel.failure()));
        };
        stack.addLastDecoder("lifecycle", new ChannelLifecycleHandler(connected, closed, channel::fail));
        if (orderedWrites) {
            DtlsHandshakeHandler handshake = new DtlsHandshakeHandler(//
                    channel, runtimeServices, retryMillis, timeoutMillis);
            stack.addLastDecoder("dtls-ready", handshake);
        }

        Function<String, ProtoInitializer> branch = route -> {
            return ctx -> ctx.addLastDecoder("messages", new RouteByteHandler(route));
        };

        configureRouting(stack, branch, routes, initialMountId);

        // 网络事件只负责投递，业务收包仍在串行执行器中处理。
        socket.subscribe(event -> {
            return event.isInbound() || !event.isSuccess();
        }, SubscribeMode.SYNC, event -> {
            if (!event.isSuccess()) {
                channel.execute(() -> channel.fail(event.getError()));
                return;
            }

            RouteMessage<byte[]> message = (RouteMessage<byte[]>) event.getData();
            channel.execute(() -> {
                try {
                    if (message.message().length > maxDatagramSize) {
                        throw new IOException("Received datagram exceeds " + maxDatagramSize);
                    }
                    listenerRelay.receive(message.mountId(), message.message());
                } catch (Throwable failure) {
                    channel.fail(failure);
                }
            });
        });

        // 所有关闭、路由和收包入口就绪后再把通道交给上层，并在同一执行器中绑定监听器。
        try {
            ChannelListener<byte[]> listener = factory.createListener(channel);
            channel.execute(() -> {
                try {
                    listenerRelay.attach(listener);
                } catch (Throwable failure) {
                    channel.fail(failure);
                }
            });
        } catch (Exception | Error failure) {
            listenerRelay.attachmentFailed();
            channel.fail(failure);
            throw failure;
        }
        return channel;
    }

    private static void configureRouting(ProtoBuildContext context, //
            Function<String, ProtoInitializer> branch, RouteTable<byte[]> routes, String initialMountId) {
        // 没有协议路由的底层测试或扩展直接接收原始数据报。
        if (routes.definitions().isEmpty()) {
            branch.apply("").config(context);
            return;
        }

        // 主动连接已经选定协议挂载，回包必须沿用该挂载；监听连接仍按每个数据报探测。
        String initial = routes.resolveMountId(initialMountId);

        if (initial != null) {
            branch.apply(initial).config(context);
            return;
        }

        ProtoHelper.typed(ByteBuf.class, Object.class).nextPartition("route", (ctx, kind, message) -> {
            if (!ctx.isRcv() || !(message instanceof ByteBuf bytes)) {
                return null;
            } else {
                int length = bytes.readableBytes();
                String route = RouteByteUtils.select(Collections.singletonList(bytes), //
                        routes.definitions(), length, true);
                return PartitionKey.newKey(route);
            }
        }, partitions -> {
            partitions.byInitializer(ctx -> {
                branch.apply(PartitionKey.findKey(ctx).getKey()).config(ctx);
            });
        }).build().config(context);
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

    private void fail(Throwable error) {
        this.failure = error;
        this.close();
    }

    private Throwable failure() {
        return this.failure == null ? new IOException("UDP channel closed") : this.failure;
    }

    private void terminated() {
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

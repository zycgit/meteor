/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.tcp;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.transport.*;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.ReadPause;

/**
 * A byte connection: no RPC messages, request correlation or logical stream state.
 */
final class TcpChannel implements NetworkChannel<byte[]> {
    private final    NetChannel        socket;
    private final    InterAddress      remote;
    private final    Executor          executor;
    private final    BasicFuture<Void> closed = new BasicFuture<>();
    private          int               writes;
    private          boolean           draining;
    private volatile Throwable         failure;

    TcpChannel(NetChannel socket, InterAddress remote, Executor executor) {
        this.socket = socket;
        this.remote = remote;
        this.executor = executor;
    }

    void receive(ChannelListener<byte[]> receiver, RouteMessage<byte[]> message, ReadPause pause) {
        // 使用管线已经选定的路由交付消息；普通接收器只接收字节内容。
        try {
            if (receiver instanceof RouteReceiver) {
                ((RouteReceiver<byte[]>) receiver).receive(message.protocolName(), message.message());
            } else {
                receiver.receive(message.message());
            }
        } catch (Throwable failure) {
            this.fail(failure);
        } finally {
            // 消费成功或失败都释放本次读取暂停。
            this.socket.resumeRead(pause);
        }
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
        // 受理写入与排空标记互斥，保证开始排空后不再接收新写入。
        BasicFuture<Void> result = new BasicFuture<>();
        synchronized (this) {
            if (this.draining || !this.isOpen()) {
                result.failed(new IllegalStateException("Byte connection is closing"));
                return result;
            }
            this.writes++;
        }

        // 复制调用方数据，避免排队等待期间被修改；实际发送交给串行执行器。
        byte[] owned = bytes.clone();
        try {
            this.execute(() -> this.write(owned, result));
        } catch (Throwable error) {
            // 投递失败也要结束本次写入计数，避免排空一直等待。
            result.failed(error);
            this.writeFinished();
        }
        return result;
    }

    private void write(byte[] bytes, BasicFuture<Void> result) {
        // 排队期间已取消的写入不再发送，但仍需归还已受理的写入计数。
        if (result.isCancelled()) {
            this.writeFinished();
            return;
        }

        // 以底层发送完成为准通知调用方，并在成功、失败或取消后结束写入计数。
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

        // 最后一个已受理写入结束后，可能满足排空关闭条件。
        this.checkDrain();
    }

    @Override
    public Future<Void> drainAndClose() {
        // 先停止受理新写入，再等待已受理写入结束；没有待写数据时直接推进关闭。
        synchronized (this) {
            this.draining = true;
        }

        this.checkDrain();
        return this.closed;
    }

    private void checkDrain() {
        // 只有进入排空且所有已受理写入均已结束，才允许关闭底层连接。
        synchronized (this) {
            if (!this.draining || this.writes != 0 || this.closed.isDone()) {
                return;
            }
        }

        // 写入完成回调可能运行在 Neta 管线内，关闭操作转交串行执行器，避免重入。
        this.execute(this::close);
    }

    @Override
    public Future<Void> close() {
        this.socket.closeNow();
        return this.closed;
    }

    void fail(Throwable error) {
        this.failure = error;
        this.close();
    }

    Throwable failure() {
        return this.failure == null ? new IOException("TCP connection closed") : this.failure;
    }

    void terminated() {
        this.closed.completed(null);
    }
}

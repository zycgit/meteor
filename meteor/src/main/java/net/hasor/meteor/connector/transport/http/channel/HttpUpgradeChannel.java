/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http.channel;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.transport.ChannelListener;
import net.hasor.meteor.connector.transport.ChannelListenerFactory;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.neta.channel.NetChannel;
import net.hasor.neta.channel.ReadPause;

/** 一个升级后的连接或 stream，生命周期仍归原 HTTP 端点管理。 */
final class HttpUpgradeChannel<M> implements NetworkChannel<M>, ChannelListener<M> {
    private final    HttpChannel            owner;
    private final    NetChannel             socket;
    private final    Class<M>               messageType;
    private final    Function<M, Future<?>> writer;
    private final    Runnable               closeAction;
    private final    BasicFuture<Void>      closed  = new BasicFuture<>();
    private final    Deque<M>               pending = new ArrayDeque<>();
    private final    Set<BasicFuture<Void>> writes  = new HashSet<>();
    private          ChannelListener<M>     receiver;
    private volatile State                  state   = State.OPEN;

    private enum State {
        OPEN,
        DRAINING,
        CLOSED
    }

    HttpUpgradeChannel(HttpChannel owner, NetChannel socket, Class<M> messageType, //
            Function<M, Future<?>> writer, Runnable closeAction) {
        this.owner = owner;
        this.socket = socket;
        this.messageType = messageType;
        this.writer = writer;
        this.closeAction = closeAction;
    }

    Class<M> messageType() {
        return this.messageType;
    }

    void attach(ChannelListenerFactory<M> factory, BasicFuture<NetworkChannel<M>> ready) {
        this.execute(() -> {
            try {
                if (!this.isOpen() || ready.isCancelled()) {
                    throw new IOException("HTTP upgrade is closed");
                }
                this.receiver = factory.createListener(this);
                this.receiver.connected();
                if (!ready.completed(this)) {
                    this.close();
                    return;
                }
                while (!this.pending.isEmpty() && this.isOpen()) {
                    this.receiver.receive(this.pending.removeFirst());
                }
            } catch (Throwable failure) {
                ready.failed(failure);
                this.abort(failure);
            }
        });
    }

    @Override
    public InterAddress getLocal() {
        return this.owner.localAddress(this.socket);
    }

    @Override
    public InterAddress getRemote() {
        return this.owner.getRemote();
    }

    @Override
    public boolean isOpen() {
        return this.state != State.CLOSED && !this.socket.isClose();
    }

    @Override
    public synchronized Future<Void> write(M message) {
        BasicFuture<Void> sent = new BasicFuture<>();
        if (!this.owner.admitWrite(sent)) {
            return sent;
        }
        this.owner.executeWrite(sent, () -> {
            if (!this.isOpen() || this.state != State.OPEN) {
                sent.failed(new IOException("HTTP upgrade is closing"));
                return;
            }
            if (sent.isCancelled()) {
                return;
            }
            this.messageType.cast(message);
            this.writes.add(sent);
            // 物理发送完成前保留独立引用；取消公开 future 不能提前关闭正在写出的连接。
            Future<?> writing;
            try {
                writing = this.owner.writeUpgrade(() -> this.writer.apply(message));
            } catch (Throwable failure) {
                this.writes.remove(sent);
                sent.failed(failure);
                if (this.state == State.DRAINING && this.writes.isEmpty()) {
                    this.close();
                }
                return;
            }
            writing.onFinal(done -> this.execute(() -> {
                this.writes.remove(sent);
                Throwable failure = done.getCause();
                if (done.isCancelled()) {
                    failure = new CancellationException("Upgrade write cancelled");
                }
                this.owner.completeWrite(sent, null, failure);
                if (this.state == State.DRAINING && this.writes.isEmpty()) {
                    this.close();
                }
            }));
        });
        return sent;
    }

    @Override
    public void execute(Runnable task) {
        this.owner.execute(task);
    }

    @Override
    public Future<Void> close() {
        this.terminate(null, true);
        return this.closed;
    }

    void abort(Throwable cause) {
        this.terminate(cause, true);
    }

    @Override
    public synchronized Future<Void> drainAndClose() {
        if (this.state == State.CLOSED) {
            return this.closed;
        }
        this.execute(() -> {
            if (this.state == State.CLOSED) {
                return;
            }
            this.state = State.DRAINING;
            if (this.writes.isEmpty()) {
                this.close();
            }
        });
        return this.closed;
    }

    Future<Void> completion() {
        return this.closed;
    }

    @Override
    public void connected() {
        // 用户接收器注册时交付一次 connected，网络握手早于此阶段。
    }

    @Override
    public void receive(M message) {
        // 入队前暂停读取，避免复制后的消息在执行器队列里无限积累；H2 暂停作用于连接。
        ReadPause pause = this.socket.pauseRead();
        try {
            this.execute(() -> {
                try {
                    if (!this.isOpen()) {
                        return;
                    }
                    if (this.receiver != null) {
                        this.receiver.receive(message);
                    } else if (this.pending.size() < this.owner.config.integer("maxPendingRequests", 1024)) {
                        this.pending.addLast(message);
                    } else {
                        throw new IOException("HTTP upgrade receive queue full");
                    }
                } catch (Throwable failure) {
                    this.abort(failure);
                } finally {
                    this.socket.resumeRead(pause);
                }
            });
        } catch (RuntimeException | Error failure) {
            this.socket.resumeRead(pause);
            throw failure;
        }
    }

    @Override
    public void closed(Throwable cause) {
        this.terminate(cause, false);
    }

    private synchronized void terminate(Throwable cause, boolean closeTransport) {
        if (this.state == State.CLOSED) {
            return;
        }
        this.state = State.CLOSED;
        this.execute(() -> {
            Throwable terminalCause = cause;
            if (closeTransport) {
                try {
                    this.closeAction.run();
                } catch (Throwable failure) {
                    if (terminalCause == null) {
                        terminalCause = failure;
                    }
                }
            }
            this.pending.clear();
            Throwable writeFailure = terminalCause == null ? new IOException("HTTP upgrade closed") : terminalCause;
            for (BasicFuture<Void> write : this.writes) {
                write.failed(writeFailure);
            }
            this.writes.clear();
            try {
                if (this.receiver != null) {
                    this.receiver.closed(terminalCause);
                }
            } finally {
                if (terminalCause == null) {
                    this.closed.completed(null);
                } else {
                    this.closed.failed(terminalCause);
                }
            }
        });
    }
}

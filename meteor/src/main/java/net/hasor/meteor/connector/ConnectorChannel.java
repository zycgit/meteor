/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.transport.ChannelListener;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;

/** Connector-side channel facade and write lifecycle for one protocol session. */
final class ConnectorChannel<M> implements MetChannel, ChannelListener<M> {
    private final    long                      channelId;
    private final    ConnectorEndpoint<M>      endpoint;
    private final    BasicFuture<MetChannel>   ready     = new BasicFuture<>();
    private final    BasicFuture<MetChannel>   closed    = new BasicFuture<>();
    private final    Set<BasicFuture<?>>       submitted = new HashSet<>();
    private          boolean                   draining;
    private          ProtocolNetworkChannel<M> connection;
    private volatile ProtocolSession<M>        session;

    ConnectorChannel(ConnectorEndpoint<M> endpoint) {
        this.channelId = endpoint.manager().nextChannelId();
        this.endpoint = endpoint;
        this.ready.onCancel(done -> this.close());
    }

    Future<MetChannel> ready() {
        return this.ready;
    }

    void attach(ProtocolNetworkChannel<M> connection) {
        boolean closing;
        synchronized (this) {
            this.connection = connection;
            closing = this.draining;
        }

        // 取消可能先于底层通道到达；迟到的连接仍须释放。
        if (closing) {
            connection.close();
        }
    }

    void fail(Throwable failure) {
        this.ready.failed(failure);
        this.close();
    }

    private void completeReady(Future<Void> done) {
        if (done.isCancelled() || done.getCause() != null) {
            Throwable failure = done.getCause();
            if (done.isCancelled()) {
                failure = new CancellationException("Protocol handshake cancelled");
            }
            this.fail(failure);
        } else if (this.connection.isOpen()) {
            this.ready.completed(this);
        }
    }

    @Override
    public void connected() {
        synchronized (this) {
            if (this.draining) {
                return;
            }
        }

        try {
            // 网络激活后才由端点创建协议会话，不在逻辑通道构造时分配会话资源。
            if (this.connection.isOpen()) {
                this.session = this.endpoint.createSession(this.connection, this::publishMessage);
                this.session.connected();
                this.session.ready().onFinal(done -> {
                    this.connection.execute(() -> this.completeReady(done));
                });
            }
        } catch (RuntimeException | Error failure) {
            this.fail(failure);
        }
    }

    @Override
    public void receive(M message) throws Exception {
        ProtocolSession<M> current = this.session;
        if (current != null) {
            current.receive(message);
        }
    }

    @Override
    public void closed(Throwable error) {
        try {
            ProtocolSession<M> current = this.session;
            if (current != null) {
                current.closed(error);
            }
        } finally {
            this.ready.failed(error);
            this.unregisterChannel();
        }
    }

    void networkClosed(Throwable failure) {
        this.connection.terminate(failure);
    }

    @Override
    public String getProtocol() {
        return this.connection.getProtocol();
    }

    long channelId() {
        return this.channelId;
    }

    @Override
    public InterAddress getLocal() {
        return this.connection.getLocal();
    }

    @Override
    public InterAddress getRemote() {
        return this.connection.getRemote();
    }

    @Override
    public Future<MetChannel> sendData(Payload payload) {
        BasicFuture<MetChannel> sent = new BasicFuture<>();
        if (this.admitWrite(sent)) {
            this.executeWrite(sent, () -> {
                if (!sent.isCancelled()) {
                    if (payload instanceof RequestPayload request && StringUtils.isBlank(request.getSerializeType())) {
                        request.setSerializeType(this.connection.config().option("serializeType", null));
                    }
                    Future<Void> writing = this.session.send(payload);

                    sent.onCancel(done -> {
                        writing.cancel();
                    });

                    writing.onFinal(done -> {
                        if (done.isCancelled()) {
                            sent.cancel();
                        } else if (done.getCause() != null) {
                            sent.failed(done.getCause());
                        } else {
                            sent.completed(this);
                        }
                    });
                }
            });
        }
        return sent;
    }

    private void publishMessage(Payload payload, long id) {
        this.endpoint.publishMessage(this, id, payload);
    }

    Future<MetChannel> close() {
        ProtocolNetworkChannel<M> current;
        synchronized (this) {
            this.draining = true;
            current = this.connection;
        }

        if (current == null) {
            this.closed(new IOException("Connection closed before network attachment"));
        } else {
            current.close();
        }
        return this.closed;
    }

    private void unregisterChannel() {
        this.endpoint.unregisterChannel(this);
        ArrayList<BasicFuture<?>> pending;
        synchronized (this) {
            this.draining = true;
            pending = new ArrayList<>(this.submitted);
        }

        for (BasicFuture<?> result : pending) {
            result.failed(new IOException("Protocol session closed"));
        }
        this.closed.completed(this);
    }

    private boolean admitWrite(BasicFuture<?> sent) {
        synchronized (this) {
            if (!this.draining && !this.closed.isDone() && this.endpoint.acceptsWrites()) {
                this.submitted.add(sent);
                sent.onFinal(done -> {
                    synchronized (this) {
                        this.submitted.remove(sent);
                    }
                    this.checkDrain();
                });
                return true;
            }
        }

        sent.failed(new IllegalStateException("Connector or channel is closing; new writes are disabled"));
        return false;
    }

    Future<MetChannel> drainAndClose() {
        synchronized (this) {
            this.draining = true;
        }

        // 尚未创建会话的连接没有业务写入，可以直接结束连接尝试。
        if (this.session == null) {
            return this.close();
        }

        this.executeWrite(this.closed, () -> {
            this.session.prepareDrain();
            this.checkDrain();
        });

        return this.closed;
    }

    private void checkDrain() {
        synchronized (this) {
            if (!this.draining || this.closed.isDone() || !this.submitted.isEmpty()) {
                return;
            }
        }

        // Completion may run inside Neta's pipeline; close only on the ordered executor.
        this.executeWrite(this.closed, () -> {
            synchronized (this) {
                if (!this.submitted.isEmpty()) {
                    return;
                }
            }
            this.connection.drainAndClose();
        });
    }

    private void executeWrite(BasicFuture<?> result, Runnable action) {
        try {
            this.connection.execute(() -> {
                try {
                    action.run();
                } catch (Throwable failure) {
                    result.failed(failure);
                }
            });
        } catch (Throwable failure) {
            result.failed(failure);
        }
    }
}
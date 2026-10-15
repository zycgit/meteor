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
import java.util.concurrent.Executor;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.transport.ChannelListener;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;

/** Connector-side channel facade and write lifecycle for one protocol session. */
final class ConnectorChannel<M> implements MetChannel, ChannelListener<M> {
    private final long                      channelId;
    private final String                    protocol;
    private final String                    serializeType;
    private final Executor                  executor;
    private final ConnectorEndpoint<?>      owner;
    private final BasicFuture<MetChannel>   closed    = new BasicFuture<>();
    private final Set<BasicFuture<?>>       submitted = new HashSet<>();
    private       boolean                   draining;
    private final ProtocolNetworkChannel<M> connection;
    private final ProtocolSession<M>        session;
    private final BasicFuture<MetChannel>   connectResult;
    private       boolean                   connected;

    ConnectorChannel(ConnectorEndpoint<?> owner, long id, Executor executor, //
            ProtocolNetworkChannel<M> connection, ProtocolFactory<M> factory, //
            BasicFuture<MetChannel> connectResult, ProtocolConfig protocolConfig) {
        this.channelId = id;
        this.protocol = protocolConfig.name();
        this.owner = owner;
        this.executor = executor;
        this.connection = connection;
        this.connectResult = connectResult;
        this.serializeType = protocolConfig.option("serializeType", null);

        InterAddress local = owner.getBindAddress();
        InterAddress advertised = null;
        if (local != null) {
            String scheme = protocolConfig.scheme();
            advertised = new InterAddress(scheme, local.getHost(), local.getPort(), local.getFormUnit());
        }

        ConnectorManager manager = owner.manager();
        boolean outbound = connectResult != null;
        ProtocolSessionContext context = new ProtocolSessionContext(//
                manager.context(), manager::schedule, advertised, outbound);
        this.session = factory.createSession(protocolConfig, context, connection, this::publishMessage);
        this.session.ready().onFinal(done -> {
            this.connection.execute(() -> this.completeConnect(done));
        });
    }

    private void completeConnect(Future<Void> done) {
        if (done.isCancelled() || done.getCause() != null) {
            if (this.connectResult != null) {
                Throwable failure = done.getCause();
                if (done.isCancelled()) {
                    failure = new CancellationException("Protocol handshake cancelled");
                }
                this.connectResult.failed(failure);
            }

            this.close();
        } else if (this.connected && this.connection.isOpen() && this.connectResult != null) {
            if (!this.connectResult.isDone()) {
                if (!this.connectResult.completed(this)) {
                    this.close();
                }
            } else if (this.connectResult.isCancelled()) {
                this.close();
            }
        }
    }

    @Override
    public void connected() {
        this.registerChannel();
        if (this.connection.isOpen()) {
            this.connected = true;
            this.session.connected();
            if (this.session.ready().isDone()) {
                this.completeConnect(this.session.ready());
            }
        }
    }

    @Override
    public void receive(M message) throws Exception {
        this.session.receive(message);
    }

    @Override
    public void closed(Throwable error) {
        try {
            this.session.closed(error);
        } finally {
            if (this.connectResult != null) {
                this.connectResult.failed(error);
            }
            this.unregisterChannel();
        }
    }

    void networkClosed(Throwable failure) {
        this.connection.terminate(failure);
    }

    @Override
    public String getProtocol() {
        return this.protocol;
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
                        request.setSerializeType(this.serializeType);
                    }
                    Future<Void> writing = this.session.send(payload);

                    sent.onCancel(done -> {
                        writing.cancel();
                    });

                    writing.onFinal(done -> {
                        if (done.isCancelled()) {
                            sent.cancel();
                        } else {
                            this.completeWrite(sent, this, done.getCause());
                        }
                    });
                }
            });
        }
        return sent;
    }

    private void publishMessage(long id, Payload payload) {
        this.owner.publishMessage(this, id, payload);
    }

    Future<MetChannel> close() {
        this.stopWrites();
        this.connection.close();
        return this.closed;
    }

    private void registerChannel() {
        if (!this.closed.isDone()) {
            this.owner.registerChannel(this);
        }
    }

    private void unregisterChannel() {
        this.owner.unregisterChannel(this);
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

    private synchronized void stopWrites() {
        this.draining = true;
    }

    private boolean admitWrite(BasicFuture<?> sent) {
        synchronized (this) {
            if (!this.draining && !this.closed.isDone() && this.owner.acceptsWrites()) {
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

    private <T> void completeWrite(BasicFuture<T> result, T value, Throwable error) {
        if (result != null) {
            if (error == null) {
                result.completed(value);
            } else {
                result.failed(error);
            }
        }
    }

    private void executeWrite(BasicFuture<?> result, Runnable action) {
        try {
            this.executor.execute(() -> {
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

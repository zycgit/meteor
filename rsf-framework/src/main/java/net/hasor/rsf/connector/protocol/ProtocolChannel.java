/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.protocol;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.connector.AbstractRsfChannel;
import net.hasor.rsf.connector.ProtocolConfig;
import net.hasor.rsf.connector.ReceivedListener;
import net.hasor.rsf.connector.RsfChannel;
import net.hasor.rsf.connector.transport.ChannelListener;
import net.hasor.rsf.connector.transport.NetworkChannel;
import net.hasor.rsf.domain.payload.Payload;
import net.hasor.rsf.domain.payload.RequestPayload;
import net.hasor.rsf.domain.payload.ResponsePayload;
import net.hasor.rsf.domain.payload.ThrowPayload;

/** Common RSF channel facade and write lifecycle for every protocol session. */
final class ProtocolChannel<M> extends AbstractRsfChannel implements ChannelListener<M> {
    private final Executor                executor;
    private final EndpointConnector<?>    owner;
    private final BasicFuture<RsfChannel> closed    = new BasicFuture<>();
    private final Set<BasicFuture<?>>     submitted = new HashSet<>();
    private       boolean                 draining;
    private final NetworkChannel<M>       connection;
    private final ProtocolSession<M>      session;
    private final BasicFuture<RsfChannel> connecting;
    private       boolean                 connected;

    public ProtocolChannel(EndpointConnector<?> owner, long id, ReceivedListener listener, Executor executor, NetworkChannel<M> connection, ProtocolFactory<M> factory, BasicFuture<RsfChannel> connecting, ProtocolConfig protocolConfig) {
        super(owner, id, listener);
        this.owner = owner;
        this.executor = executor;
        this.connection = connection;
        this.connecting = connecting;

        InterAddress local = owner.config().address();
        InterAddress advertised = new InterAddress(protocolConfig.scheme(), local.getHost(), local.getPort(), local.getFormUnit());
        ProtocolContext context = new ProtocolContext(owner.manager().context(), owner.manager()::schedule, advertised, connecting != null);
        this.session = factory.create(protocolConfig, context, connection, this::received);
        this.session.ready().onFinal(done -> this.connection.execute(() -> this.completeConnect(done)));
    }

    private void completeConnect(Future<Void> done) {
        if (done.isCancelled() || done.getCause() != null) {
            if (this.connecting != null) {
                this.connecting.failed(done.isCancelled() ? new CancellationException("Protocol handshake cancelled") : done.getCause());
            }
            this.close();
        } else if (this.connected && this.isActive() && this.connecting != null) {
            if (!this.connecting.isDone()) {
                if (!this.connecting.completed(this)) {
                    this.close();
                }
            } else if (this.connecting.isCancelled()) {
                this.close();
            }
        }
    }

    @Override
    public void connected() {
        this.manageChannel();
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
            if (this.connecting != null) {
                this.connecting.failed(error);
            }
            this.forgetChannel();
        }
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
    public boolean isActive() {
        Future<Void> ready = this.session.ready();
        return this.connection.isOpen() && ready.isDone() && !ready.isCancelled() && ready.getCause() == null;
    }

    @Override
    public Future<RsfChannel> sendData(Payload payload) {
        BasicFuture<RsfChannel> sent = new BasicFuture<>();
        if (this.admitWrite(sent)) {
            this.executeWrite(this.executor, sent, () -> {
                if (!sent.isCancelled()) {
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

    private void received(long id, Payload payload) {
        switch (payload.getType()) {
            case REQUEST:
                this.listener().onRequest(this, id, (RequestPayload) payload);
                break;
            case RESPONSE:
                this.listener().onResponse(this, id, (ResponsePayload) payload);
                break;
            case THROW:
                this.listener().onFailure(this, id, (ThrowPayload) payload);
                break;
        }
    }

    private void prepareDrain() {
        this.session.prepareDrain();
    }

    private void closeAfterDrain() {
        this.connection.drainAndClose();
    }

    @Override
    public Future<RsfChannel> close() {
        this.stopWrites();
        this.connection.close();
        return this.closeFuture();
    }

    private void manageChannel() {
        if (!this.closed.isDone()) {
            this.owner.connected(this);
        }
    }

    private void forgetChannel() {
        this.owner.disconnected(this);
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

    private BasicFuture<RsfChannel> closeFuture() {
        return this.closed;
    }

    private boolean admitWrite(BasicFuture<?> sent) {
        synchronized (this) {
            if (!this.draining && !this.closed.isDone() && this.owner.writable()) {
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

    @Override
    public final Future<RsfChannel> drainAndClose() {
        synchronized (this) {
            this.draining = true;
        }

        this.executeWrite(this.executor, this.closed, () -> {
            this.prepareDrain();
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
        this.executeWrite(this.executor, this.closed, () -> {
            synchronized (this) {
                if (!this.submitted.isEmpty()) {
                    return;
                }
            }
            this.closeAfterDrain();
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

    private void executeWrite(Executor executor, BasicFuture<?> result, Runnable action) {
        try {
            executor.execute(() -> {
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
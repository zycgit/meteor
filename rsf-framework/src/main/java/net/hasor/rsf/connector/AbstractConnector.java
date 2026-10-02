/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector;
import java.util.*;
import java.util.function.Consumer;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.concurrent.future.FutureListener;
import net.hasor.cobble.logging.Logger;
import net.hasor.rsf.address.InterAddress;

/** Common endpoint lifecycle; providers supply transport operations. */
public abstract class AbstractConnector implements RsfConnector {
    private static final Logger                                    logger   = Logger.getLogger(AbstractConnector.class);
    protected final      ConnectorConfig                           config;
    protected final      ConnectorManager                          manager;
    private final        Map<InterAddress, BasicFuture<RsfListen>> bindings = new LinkedHashMap<>();
    private final        Set<RsfListen>                            listens  = new LinkedHashSet<>();
    private volatile     State                                     state    = State.NEW;
    private              boolean                                   bindDisabled;
    //
    private volatile     Consumer<RsfConnector>                    closingListener;
    private volatile     Consumer<RsfChannel>                      channelConnectedListener;
    private volatile     Consumer<RsfChannel>                      channelClosedListener;

    private enum State {
        NEW,
        READY,
        CLOSED
    }

    protected AbstractConnector(ConnectorConfig config, ConnectorManager manager) {
        this.config = Objects.requireNonNull(config, "config");
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    public final ConnectorConfig config() {
        return this.config;
    }

    public final synchronized List<RsfListen> getListenList() {
        return Collections.unmodifiableList(new ArrayList<>(this.listens));
    }

    /** Register physical listeners immediately, even while their bind is still pending. */
    protected final synchronized boolean onListen(RsfListen listen) {
        if (this.state != State.READY || this.bindDisabled || !this.manager.isInitialized()) {
            return false;
        }

        this.listens.add(listen);
        return true;
    }

    protected final synchronized void onListenClosed(RsfListen listen) {
        this.listens.remove(listen);
    }

    @Override
    public InterAddress getBindAddress() {
        for (RsfListen listen : this.getListenList()) {
            if (listen.isActive()) {
                return listen.getBindAddress();
            }
        }

        return null;
    }

    public final void init() throws Exception {
        Throwable e;
        synchronized (this) {
            if (this.state == State.CLOSED) {
                throw new IllegalStateException("RsfConnector is closed");
            }

            if (this.state == State.READY) {
                return;
            }

            try {
                this.initialize();
                this.state = State.READY;
                return;
            } catch (Exception | Error error) {
                this.state = State.CLOSED;
                e = error;
            }
        }

        // Roll back resources and keep the initialization failure as the primary cause.
        try {
            this.prepareClose();
            this.doClose();
        } catch (Throwable cleanup) {
            e.addSuppressed(cleanup);
        }

        if (e instanceof Error) {
            throw (Error) e;
        } else {
            throw (Exception) e;
        }
    }

    /** Provider hook for allocating shared resources, without listening or connecting. */
    protected abstract void initialize() throws Exception;

    /** Providers hold the connector monitor around this check and write admission. */
    protected final boolean acceptsWrites() {
        return this.state == State.READY && this.manager.isInitialized();
    }

    @Override
    public final void onClosing(Consumer<RsfConnector> listener) {
        this.closingListener = listener;
    }

    @Override
    public final void onChannelConnected(Consumer<RsfChannel> listener) {
        this.channelConnectedListener = listener;
    }

    /** Register the channel before exposing it to callers or delivering messages. */
    protected final void fireChannelConnected(RsfChannel channel) {
        Consumer<RsfChannel> listener = this.channelConnectedListener;
        if (listener != null) {
            listener.accept(channel);
        }
    }

    @Override
    public final void onChannelClosed(Consumer<RsfChannel> listener) {
        this.channelClosedListener = listener;
    }

    /** Notify after a channel terminates, including channels closed during connector shutdown. */
    protected final void fireChannelClosed(RsfChannel channel) {
        Consumer<RsfChannel> listener = this.channelClosedListener;
        if (listener != null) {
            listener.accept(channel);
        }
    }

    public final void close() {
        synchronized (this) {
            if (this.state == State.CLOSED) {
                return;
            }
            this.state = State.CLOSED;
        }

        this.prepareClose();
        this.doClose();
    }

    @Override
    public final void closeBind() {
        List<BasicFuture<RsfListen>> pending = new ArrayList<>();
        List<RsfListen> closing;
        synchronized (this) {
            this.bindDisabled = true;
            for (BasicFuture<RsfListen> binding : this.bindings.values()) {
                if (!binding.isDone()) {
                    pending.add(binding);
                }
            }

            this.bindings.clear();
            closing = new ArrayList<>(this.listens);
            this.listens.clear();
        }

        for (BasicFuture<RsfListen> binding : pending) {
            try {
                binding.failed(new IllegalStateException("RsfConnector binding is disabled"));
            } catch (RuntimeException | Error failure) {
                logger.warn("Pending binding failure notification failed", failure);
            }
        }
        for (RsfListen listen : closing) {
            try {
                listen.close();
            } catch (RuntimeException | Error failure) {
                logger.warn("Listener close failed", failure);
            }
        }
    }

    /** Shared preparation for normal close and initialization rollback. */
    private void prepareClose() {
        this.closeBind();
        Consumer<RsfConnector> listener = this.closingListener;
        this.closingListener = null;
        if (listener != null) {
            listener.accept(this);
        }
    }

    /** Release transport resources after listeners and submitted writes have been closed. */
    protected abstract void doClose();

    //

    public final Future<RsfListen> bind(InterAddress address) {
        Objects.requireNonNull(address, "address");

        BasicFuture<RsfListen> result;
        synchronized (this) {
            if (this.state != State.READY) {
                throw new IllegalStateException("RsfConnector is not ready; call init() before bind()");
            }
            if (this.bindDisabled || !this.manager.isInitialized()) {
                throw new IllegalStateException("RsfConnector binding is disabled");
            }

            Future<RsfListen> previous = this.bindings.get(address);
            if (previous != null) {
                RsfListen bound = previous.getResult();
                if (bound == null || bound.isActive()) {
                    return previous;
                }

                this.onListenClosed(bound);
            }

            result = new BasicFuture<>();
            FutureListener<Future<RsfListen>> discard = done -> {
                synchronized (this) {
                    this.bindings.remove(address, result);
                }
            };
            // Install cleanup before publishing the future, so failure/cancellation callbacks can retry.
            result.onFailed(discard).onCancel(discard);
            this.bindings.put(address, result);
        }

        if (result.isDone()) {
            return result;
        }

        try {
            this.listen(address, this.manager).onCompleted(done -> {
                this.finishBind(result, done.getResult());
            }).onFailed(done -> {
                result.failed(done.getCause());
            }).onCancel(done -> {
                result.cancel();
            });
        } catch (Throwable failure) {
            result.failed(failure);
        }

        return result;
    }

    /** Accept the created listener, or reclaim it if cancellation or shutdown won. */
    private void finishBind(BasicFuture<RsfListen> result, RsfListen bound) {
        Throwable failure = null;
        try {
            if (bound == null) {
                throw new IllegalStateException("Provider returned no listener");
            }
            if (!StringUtils.equalsIgnoreCase(this.config.listenType(), bound.getType())) {
                throw new IllegalStateException("Provider returned listener type " + bound.getType() + " for " + this.config.listenType());
            }

            if (result.isDone() || !this.onListen(bound)) {
                throw new IllegalStateException("RsfConnector no longer accepts listeners");
            }

            // Future callbacks must run outside the connector lock.
            result.completed(bound);
        } catch (Throwable error) {
            failure = error;
        }

        // Reclaim rejected listeners, including cancellation/close after registration.
        if (bound != null && result.getResult() != bound) {
            this.onListenClosed(bound);
            try {
                bound.close();
            } catch (RuntimeException | Error cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else if (failure != cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        if (failure != null) {
            result.failed(failure);
        }
    }

    /** Start one asynchronous bind. Report success only when usable, and clean partial resources on failure. */
    protected abstract Future<RsfListen> listen(InterAddress address, ReceivedListener listener) throws Exception;

    //

    public final Future<RsfChannel> connect(InterAddress target) {
        Objects.requireNonNull(target, "target");

        if (!this.acceptsWrites()) {
            return this.failed(new IllegalStateException("RsfConnector is not ready for connections"));
        }

        if (!StringUtils.equalsIgnoreCase(this.config.address().getSchema(), target.getSchema())) {
            return this.failed(new IllegalArgumentException("Endpoint schema mismatch"));
        }

        try {
            return this.openSession(target, this.manager);
        } catch (Exception | Error failure) {
            return this.failed(failure);
        }
    }

    /** Complete only when the logical session is usable; this need not mean a physical connection exists. */
    protected abstract Future<RsfChannel> openSession(InterAddress target, ReceivedListener listener);

    //

    private <T> Future<T> failed(Throwable cause) {
        BasicFuture<T> result = new BasicFuture<>();
        result.failed(cause);
        return result;
    }
}

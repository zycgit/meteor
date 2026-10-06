/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector;
import java.util.*;
import java.util.concurrent.ExecutionException;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.concurrent.future.FutureListener;
import net.hasor.cobble.logging.Logger;
import net.hasor.rsf.address.InterAddress;

/** Common endpoint lifecycle; providers supply transport operations. */
public abstract class AbstractConnector implements RsfConnector {
    private static final Logger                 logger   = Logger.getLogger(AbstractConnector.class);
    protected final      ConnectorConfig        config;
    protected final      ConnectorManager       manager;
    private              BasicFuture<RsfListen> binding;
    private final        Set<RsfListen>         listens  = new LinkedHashSet<>();
    private volatile     State                  state    = State.NEW;
    private              boolean                bindDisabled;
    private final        Map<Long, RsfChannel>  channels = new LinkedHashMap<>();

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
            this.closeBind();
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

    /** Own accepted and outgoing sessions before they can deliver messages. */
    protected final void fireChannelConnected(RsfChannel channel) {
        synchronized (this) {
            if (this.acceptsWrites()) {
                RsfChannel previous = this.channels.putIfAbsent(channel.getChannelId(), channel);
                if (previous != null && previous != channel) {
                    throw new IllegalStateException("Duplicate channel ID");
                }
                return;
            }
        }

        channel.close();
    }

    protected final synchronized void fireChannelClosed(RsfChannel channel) {
        this.channels.remove(channel.getChannelId(), channel);
    }

    public final void close() {
        synchronized (this) {
            if (this.state == State.CLOSED) {
                return;
            }
            this.state = State.CLOSED;
        }

        this.closeBind();

        try {
            this.closeChannels();
        } finally {
            this.doClose();
        }
    }

    @Override
    public final void closeBind() {
        List<BasicFuture<RsfListen>> pending = new ArrayList<>();
        List<RsfListen> closing;
        synchronized (this) {
            this.bindDisabled = true;
            if (this.binding != null && !this.binding.isDone()) {
                pending.add(this.binding);
            }

            this.binding = null;
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

    private void closeChannels() {
        List<RsfChannel> closing;
        synchronized (this) {
            closing = new ArrayList<>(this.channels.values());
        }

        List<Future<RsfChannel>> pending = new ArrayList<>();
        for (RsfChannel channel : closing) {
            try {
                pending.add(channel.drainAndClose());
            } catch (RuntimeException failure) {
                logger.warn("Channel drain failed", failure);
                try {
                    pending.add(channel.close());
                } catch (RuntimeException cleanup) {
                    logger.warn("Channel close failed", cleanup);
                }
            }
        }

        boolean interrupted = false;
        for (Future<RsfChannel> future : pending) {
            for (; ; ) {
                try {
                    future.get();
                    break;
                } catch (InterruptedException failure) {
                    interrupted = true;
                } catch (ExecutionException | RuntimeException failure) {
                    logger.warn("Channel drain failed", failure);
                    break;
                }
            }
        }

        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Release transport resources after listeners and submitted writes have been closed. */
    protected abstract void doClose();

    //

    public final Future<RsfListen> bind() {
        if (!this.config.bindEnabled()) {
            throw new IllegalStateException("Endpoint has no configured listener: " + this.config.name());
        }

        InterAddress address = this.config.address();
        BasicFuture<RsfListen> result;
        synchronized (this) {
            if (this.state != State.READY) {
                throw new IllegalStateException("RsfConnector is not ready; call init() before bind()");
            }
            if (this.bindDisabled || !this.manager.isInitialized()) {
                throw new IllegalStateException("RsfConnector binding is disabled");
            }

            Future<RsfListen> previous = this.binding;
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
                    if (this.binding == result) {
                        this.binding = null;
                    }
                }
            };
            // Install cleanup before publishing the future, so failure/cancellation callbacks can retry.
            result.onFailed(discard).onCancel(discard);
            this.binding = result;
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

        if (this.config.protocol(target.getSchema()) == null) {
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

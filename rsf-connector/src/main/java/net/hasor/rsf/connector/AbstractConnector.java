/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector;
import java.io.IOException;
import java.util.*;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.ref.Tuple;
import net.hasor.rsf.address.InterAddress;

/** Common endpoint lifecycle; providers supply transport operations. */
public abstract class AbstractConnector implements Connector {
    protected final ConnectorContext                   context;
    private final   List<RsfListen>                    listens  = new ArrayList<>();
    private final   Map<Tuple, BasicFuture<RsfListen>> bindings = new LinkedHashMap<>();
    private         State                              state    = State.NEW;

    private enum State {
        NEW,
        READY,
        CLOSED
    }

    protected AbstractConnector(ConnectorContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    public final ConnectorConfig config() {
        return this.context.config();
    }

    @Override
    public InterAddress getBindAddress(String listenType) {
        Objects.requireNonNull(listenType, "listenType");
        for (RsfListen listen : getListenList()) {
            if (StringUtils.equalsIgnoreCase(listenType, listen.getType()) && listen.isActive()) {
                return listen.getBindAddress();
            }
        }

        return null;
    }

    public final void init() throws Exception {
        Throwable e;
        synchronized (this) {
            if (this.state == State.CLOSED) {
                throw new IllegalStateException("Connector is closed");
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

        // rollback
        try {
            this.dispose();
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

    public final void close() {
        synchronized (this) {
            if (this.state == State.CLOSED) {
                return;
            } else {
                this.state = State.CLOSED;
            }
        }

        this.dispose();
    }

    private void dispose() {
        List<Runnable> cleanup = new ArrayList<>();
        synchronized (this) {
            for (int i = this.listens.size() - 1; i >= 0; i--) {
                cleanup.add(this.listens.get(i)::close);
            }
            this.listens.clear();
            for (BasicFuture<RsfListen> binding : this.bindings.values()) {
                cleanup.add(() -> binding.failed(new IllegalStateException("Connector is closed")));
            }
            this.bindings.clear();
        }

        cleanup.add(this::destroy);
        Throwable failure = null;
        // Provider cleanup may call back into the connector; never hold the lifecycle lock here.
        for (Runnable close : cleanup) {
            try {
                close.run();
            } catch (RuntimeException | Error error) {
                if (failure == null) {
                    failure = error;
                } else if (failure != error) {
                    failure.addSuppressed(error);
                }
            }
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        if (failure != null) {
            throw (RuntimeException) failure;
        }
    }

    /** Release accepted sessions and engine resources, including partial initialization. */
    protected abstract void destroy();

    //

    public final Future<RsfListen> bind(String listenType, InterAddress address) {
        Objects.requireNonNull(address, "address");
        String type = Objects.requireNonNull(listenType, "listenType").toLowerCase(Locale.ROOT);
        if (type.trim().isEmpty()) {
            throw new IllegalArgumentException("listenType must not be blank");
        }

        Tuple key = Tuple.of(type, address);
        BasicFuture<RsfListen> result;
        synchronized (this) {
            if (this.state != State.READY) {
                throw new IllegalStateException("Connector is not ready; call init() before bind()");
            }

            Future<RsfListen> previous = this.bindings.get(key);
            if (previous != null) {
                RsfListen bound = previous.getResult();
                if (bound == null || bound.isActive()) {
                    return previous;
                }

                this.listens.remove(bound);
            }

            result = new BasicFuture<>();
            this.bindings.put(key, result);
        }

        result.onCancel(i -> {
            synchronized (this) {
                this.bindings.remove(key, result);
            }
        });

        try {
            this.listen(type, address, this.context.receivedListener()).onFinal(done -> {
                if (done.isCancelled()) {
                    result.cancel();
                } else {
                    this.finishBind(key, result, done.getResult(), done.getCause());
                }
            });
        } catch (Throwable failure) {
            this.finishBind(key, result, null, failure);
        }

        return result;
    }

    /** Start one asynchronous bind. Report success only when usable, and clean partial resources on failure. */
    protected abstract Future<RsfListen> listen(String listenType, InterAddress address, ReceivedListener listener) throws Exception;

    public final synchronized List<RsfListen> getListenList() {
        return Collections.unmodifiableList(new ArrayList<>(this.listens));
    }

    private void finishBind(Tuple key, BasicFuture<RsfListen> result, RsfListen bound, Throwable failure) {
        String listenType = key.get0();
        if (bound != null && !StringUtils.equalsIgnoreCase(listenType, bound.getType())) {
            failure = new IllegalStateException("Provider returned listener type " + bound.getType() + " for " + listenType);
        } else if (failure == null && bound == null) {
            failure = new IllegalStateException("Provider returned no listener");
        }

        // Decide ownership once; provider cleanup and future callbacks run outside this lock.
        boolean accepted;
        synchronized (this) {
            accepted = failure == null && this.state == State.READY && !result.isDone() && this.bindings.get(key) == result;
            if (accepted) {
                this.listens.add(bound);
            } else {
                this.bindings.remove(key, result);
            }
        }

        if (!accepted) {
            if (bound != null) {
                try {
                    bound.close();
                } catch (RuntimeException | Error cleanup) {
                    if (failure == null) {
                        throw cleanup;
                    }
                    if (failure != cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                }
            }
            if (failure != null) {
                result.failed(failure);
            }
            return;
        }

        try {
            result.completed(bound);
        } finally {
            // Cancellation or close may win after registration but before completion.
            if (result.getResult() != bound) {
                synchronized (this) {
                    this.listens.remove(bound);
                    this.bindings.remove(key, result);
                }
                bound.close();
            }
        }
    }

    public final Future<RsfChannel> connect(String listenType, InterAddress target) {
        Objects.requireNonNull(target, "target");
        String type = Objects.requireNonNull(listenType, "listenType").toLowerCase(Locale.ROOT);
        if (type.trim().isEmpty()) {
            throw new IllegalArgumentException("listenType must not be blank");
        }

        synchronized (this) {
            if (this.state == State.CLOSED) {
                return this.failed(new IOException("Connector is closed"));
            }
            if (this.state != State.READY) {
                return this.failed(new IllegalStateException("Connector is not initialized; call init() first"));
            }
        }

        if (!StringUtils.equalsIgnoreCase(this.context.config().address().getSchema(), target.getSchema())) {
            return this.failed(new IllegalArgumentException("Endpoint schema mismatch"));
        }

        try {
            return this.openSession(type, target, this.context.receivedListener());
        } catch (Exception | Error failure) {
            return this.failed(failure);
        }
    }

    /** Complete only when the logical session is usable; this need not mean a physical connection exists. */
    protected abstract Future<RsfChannel> openSession(String listenType, InterAddress target, ReceivedListener listener);

    //

    private <T> Future<T> failed(Throwable cause) {
        BasicFuture<T> result = new BasicFuture<>();
        result.failed(cause);
        return result;
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.concurrent.future.FutureListener;
import net.hasor.cobble.logging.Logger;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.transport.*;
import net.hasor.meteor.domain.payload.Payload;

/** Owns one configured endpoint, its listeners, transport connections and protocol channels. */
final class ConnectorEndpoint<M> implements AutoCloseable {
    private static final Logger                          logger         = Logger.getLogger(ConnectorEndpoint.class);
    private final        ConnectorConfig                 config;
    private final        TransportConfig                 transportConfig;
    private final        ConnectorManager                manager;
    private final        NetworkConnectorFactory<M>      transport;
    private final        Map<String, ProtocolMount<M>>   mounts;
    private final        Map<String, NetworkRoute<M>>    routes;
    private final        ContextRoutes                   contexts;
    private final        Map<Long, ConnectorChannel<?>>  channels       = new LinkedHashMap<>();
    private final        Set<NetworkChannel<M>>          connections    = ConcurrentHashMap.newKeySet();
    private final        Set<NetworkChannel<M>>          accepted       = ConcurrentHashMap.newKeySet();
    private final        Set<NetworkChannel<M>>          closingInbound = ConcurrentHashMap.newKeySet();
    private              BasicFuture<NetworkListen>      binding;
    private              NetworkListen                   listen;
    private              NetworkConnector<M>             network;
    private volatile     State                           state          = State.NEW;
    private              boolean                         bindDisabled;

    private enum State {
        NEW,
        READY,
        CLOSED
    }

    ConnectorEndpoint(ConnectorConfig config, TransportConfig transportConfig, ConnectorManager manager, //
            NetworkConnectorFactory<M> transport, Map<String, ProtocolMount<M>> mounts, ContextRoutes contexts) {
        this.config = config;
        this.transportConfig = transportConfig;
        this.manager = manager;
        this.transport = transport;
        this.mounts = mounts;
        Map<String, NetworkRoute<M>> routes = new LinkedHashMap<>();
        for (ProtocolMount<M> mount : mounts.values()) {
            routes.put(mount.mountId(), mount.route());
        }
        this.routes = Collections.unmodifiableMap(routes);
        this.contexts = contexts;
    }

    ConnectorConfig config() {
        return this.config;
    }

    ContextRoutes contextRoutes() {
        return this.contexts;
    }

    ConnectorManager manager() {
        return this.manager;
    }

    synchronized boolean hasListener() {
        return this.listen != null;
    }

    synchronized InterAddress getBindAddress() {
        if (this.listen != null && this.listen.isOpen()) {
            return this.listen.getAddress();
        }
        return null;
    }

    void init() throws Exception {
        Throwable failure;
        synchronized (this) {
            if (this.state == State.CLOSED) {
                throw new IllegalStateException("Connector endpoint is closed");
            }
            if (this.state == State.READY) {
                return;
            }

            try {
                this.network = this.transport.create(this.transportConfig, this.manager.context().getClassLoader());
                this.state = State.READY;
                return;
            } catch (Exception | Error error) {
                this.state = State.CLOSED;
                failure = error;
            }
        }

        // Roll back resources and preserve the initialization failure as the primary cause.
        try {
            this.closeBind();
            this.closeTransport();
        } catch (Throwable cleanup) {
            failure.addSuppressed(cleanup);
        }

        if (failure instanceof Error error) {
            throw error;
        }
        throw (Exception) failure;
    }

    synchronized boolean acceptsWrites() {
        return this.state == State.READY && this.manager.isInitialized();
    }

    synchronized boolean acceptsInbound() {
        return this.state == State.READY && this.manager.acceptsInbound();
    }

    Future<NetworkListen> bind() {
        if (!this.config.bindEnabled()) {
            throw new IllegalStateException("Endpoint has no configured listener: " + this.config.name());
        }

        BasicFuture<NetworkListen> result;
        synchronized (this) {
            this.requireBindingAllowed();
            if (this.binding != null) {
                NetworkListen bound = this.binding.getResult();
                if (bound == null || bound.isOpen()) {
                    return this.binding;
                }
                if (this.listen == bound) {
                    this.listen = null;
                }
            }

            result = new BasicFuture<>();
            FutureListener<Future<NetworkListen>> discard = done -> this.forgetBinding(result);
            result.onFailed(discard).onCancel(discard);
            this.binding = result;
        }

        try {
            this.createListen(this.config.address()).onCompleted(done -> {
                this.completeBind(result, done.getResult());
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

    private void requireBindingAllowed() {
        if (this.state != State.READY) {
            throw new IllegalStateException("Connector endpoint is not ready");
        }
        if (this.bindDisabled || !this.manager.allowsBindings()) {
            throw new IllegalStateException("Connector endpoint binding is disabled");
        }
    }

    private synchronized void forgetBinding(BasicFuture<NetworkListen> result) {
        if (this.binding == result) {
            this.binding = null;
        }
    }

    private Future<NetworkListen> createListen(InterAddress address) throws Exception {
        NetworkListen networkListen = this.network.bind(address, channel -> this.attach(channel, null, null));
        networkListen.onClose(() -> this.onListenClosed(networkListen));
        return new BasicFuture<>(networkListen);
    }

    private void completeBind(BasicFuture<NetworkListen> result, NetworkListen bound) {
        Throwable failure = null;
        try {
            synchronized (this) {
                if (bound == null) {
                    throw new IllegalStateException("Transport returned no listener");
                }
                if (result.isDone() || !this.acceptListen(bound)) {
                    throw new IllegalStateException("Connector endpoint no longer accepts listeners");
                }
            }
            result.completed(bound);
        } catch (Throwable error) {
            failure = error;
        }

        if (bound != null && result.getResult() != bound) {
            this.onListenClosed(bound);
            try {
                bound.close();
            } catch (RuntimeException | Error cleanup) {
                failure = appendFailure(failure, cleanup);
            }
        }
        if (failure != null) {
            result.failed(failure);
        }
    }

    private boolean acceptListen(NetworkListen listen) {
        if (this.state != State.READY || this.bindDisabled || !this.manager.allowsBindings()) {
            return false;
        }
        this.listen = listen;
        return true;
    }

    private synchronized void onListenClosed(NetworkListen listen) {
        if (this.listen == listen) {
            this.listen = null;
        }
    }

    Future<MetChannel> connect(String mountId, InterAddress target) {
        if (!this.acceptsWrites()) {
            return failed(new IllegalStateException("Connector endpoint is not ready for connections"));
        }
        ProtocolConfig mount = this.config.mount(mountId);
        if (mount == null) {
            return failed(new IllegalArgumentException("Unknown endpoint mount: " + mountId));
        }
        if (!StringUtils.equalsIgnoreCase(mount.scheme(), target.getSchema())) {
            return failed(new IllegalArgumentException("Endpoint mount scheme mismatch: " + mountId));
        }

        try {
            return this.openSession(mountId, target);
        } catch (Exception | Error failure) {
            return failed(failure);
        }
    }

    private Future<MetChannel> openSession(String mountId, InterAddress address) {
        BasicFuture<MetChannel> result = new BasicFuture<>();
        Future<Void> connecting = this.network.connect(address, channel -> {
            result.onCancel(done -> channel.close());
            return this.attach(channel, result, mountId);
        });

        result.onCancel(done -> connecting.cancel());
        connecting.onFailed(done -> result.failed(done.getCause())).onCancel(done -> result.cancel());
        return result;
    }

    private ChannelListener<M> attach(NetworkChannel<M> channel, BasicFuture<MetChannel> connecting, String mountId) {
        this.connections.add(channel);
        boolean inbound = connecting == null;
        if (inbound) {
            this.accepted.add(channel);
        }

        try {
            Runnable terminated = () -> this.onConnectionClosed(channel);
            return new ConnectorRouter<>(this, channel, this.mounts, this.routes, connecting, mountId, inbound, //
                    this.transport.sharedRoutes(), terminated);
        } catch (RuntimeException | Error failure) {
            this.onConnectionClosed(channel);
            channel.close();
            throw failure;
        }
    }

    private void onConnectionClosed(NetworkChannel<M> channel) {
        this.connections.remove(channel);
        this.accepted.remove(channel);
        this.closingInbound.remove(channel);
    }

    void connected(ConnectorChannel<?> channel) {
        synchronized (this) {
            if (this.acceptsWrites()) {
                ConnectorChannel<?> previous = this.channels.putIfAbsent(channel.channelId(), channel);
                if (previous != null && previous != channel) {
                    throw new IllegalStateException("Duplicate channel ID");
                }
                return;
            }
        }
        channel.close();
    }

    synchronized void disconnected(ConnectorChannel<?> channel) {
        this.channels.remove(channel.channelId(), channel);
    }

    void received(MetChannel channel, long requestId, Payload payload) {
        this.manager.received(channel, requestId, payload);
    }

    boolean acceptingInbound(NetworkChannel<M> channel) {
        return this.acceptsInbound() && !this.closingInbound.contains(channel);
    }

    void closeInbound(NetworkChannel<M> channel) {
        if (!this.closingInbound.add(channel)) {
            return;
        }

        try {
            Future<Void> draining = channel.drainAndClose();
            draining.onFailed(done -> this.forceClose(channel, done.getCause()));
            draining.onCancel(done -> {
                this.forceClose(channel, new IllegalStateException("Channel drain cancelled"));
            });
        } catch (RuntimeException | Error failure) {
            this.forceClose(channel, failure);
        }
    }

    private void forceClose(NetworkChannel<M> channel, Throwable failure) {
        logger.warn("Accepted channel drain failed", failure);
        try {
            channel.close().onFailed(done -> logger.warn("Accepted channel close failed", done.getCause()));
        } catch (RuntimeException | Error cleanup) {
            logger.warn("Accepted channel close failed", cleanup);
        }
    }

    void stopInbound() {
        BasicFuture<NetworkListen> pending;
        NetworkListen current;
        synchronized (this) {
            pending = this.binding != null && !this.binding.isDone() ? this.binding : null;
            if (pending != null) {
                this.binding = null;
            }
            current = this.listen;
        }

        Throwable failure = null;
        if (pending != null) {
            try {
                pending.failed(new IllegalStateException("Inbound traffic is stopped"));
            } catch (RuntimeException | Error error) {
                failure = appendFailure(failure, error);
            }
        }
        if (current != null) {
            try {
                current.suspend();
            } catch (RuntimeException | Error error) {
                failure = appendFailure(failure, error);
            }
        }
        for (NetworkChannel<M> channel : new ArrayList<>(this.accepted)) {
            try {
                this.closeInbound(channel);
            } catch (RuntimeException | Error error) {
                failure = appendFailure(failure, error);
            }
        }
        throwFailure(failure);
    }

    void startInbound() {
        NetworkListen current;
        synchronized (this) {
            current = this.listen;
        }
        if (current != null) {
            current.resume();
        }
    }

    void closeBind() {
        BasicFuture<NetworkListen> pending;
        NetworkListen closing;
        synchronized (this) {
            this.bindDisabled = true;
            pending = this.binding != null && !this.binding.isDone() ? this.binding : null;
            this.binding = null;
            closing = this.listen;
            this.listen = null;
        }

        if (pending != null) {
            try {
                pending.failed(new IllegalStateException("Connector endpoint binding is disabled"));
            } catch (RuntimeException | Error failure) {
                logger.warn("Pending binding failure notification failed", failure);
            }
        }
        if (closing != null) {
            try {
                closing.close();
            } catch (RuntimeException | Error failure) {
                logger.warn("Listener close failed", failure);
            }
        }
    }

    @Override
    public void close() {
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
            this.closeTransport();
        }
    }

    private void closeChannels() {
        List<ConnectorChannel<?>> closing;
        synchronized (this) {
            closing = new ArrayList<>(this.channels.values());
        }

        List<Future<MetChannel>> pending = new ArrayList<>();
        for (ConnectorChannel<?> channel : closing) {
            try {
                pending.add(channel.drainAndClose());
            } catch (RuntimeException failure) {
                logger.warn("Channel drain failed", failure);
                pending.add(channel.close());
            }
        }
        this.awaitChannels(pending);
    }

    private void awaitChannels(List<Future<MetChannel>> pending) {
        boolean interrupted = false;
        for (Future<MetChannel> future : pending) {
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

    private void closeTransport() {
        List<Future<Void>> draining = new ArrayList<>();
        try {
            for (NetworkChannel<M> channel : this.connections) {
                draining.add(channel.drainAndClose());
            }
            for (Future<Void> future : draining) {
                try {
                    future.get();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception failure) {
                    // Closing the transport below also reclaims failed connections.
                }
            }
        } finally {
            if (this.network != null) {
                this.network.close();
            }
            this.connections.clear();
            this.accepted.clear();
            this.closingInbound.clear();
        }
    }

    private static <T> Future<T> failed(Throwable cause) {
        BasicFuture<T> result = new BasicFuture<>();
        result.failed(cause);
        return result;
    }

    private static Throwable appendFailure(Throwable current, Throwable failure) {
        if (current == null) {
            return failure;
        }
        if (current != failure) {
            current.addSuppressed(failure);
        }
        return current;
    }

    private static void throwFailure(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw (RuntimeException) failure;
        }
    }
}

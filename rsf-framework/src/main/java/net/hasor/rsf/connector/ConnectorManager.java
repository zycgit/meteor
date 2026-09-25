/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.concurrent.timer.HashedWheelTimer;
import net.hasor.cobble.concurrent.timer.Timeout;
import net.hasor.cobble.logging.Logger;
import net.hasor.rsf.RsfContext;
import net.hasor.rsf.domain.payload.Payload;
import net.hasor.rsf.domain.payload.RequestPayload;
import net.hasor.rsf.domain.payload.ResponsePayload;
import net.hasor.rsf.domain.payload.ThrowPayload;

/**
 * Manages connector factories, initialized connectors and all incoming/outgoing channels.
 * The owner serializes init() and close(). RsfConnector creation and stopping admission are mutually exclusive.
 */
public class ConnectorManager implements AutoCloseable, ReceivedListener {
    private static final Logger                           logger        = Logger.getLogger(ConnectorManager.class);
    private final        RsfContext                       context;
    private              HashedWheelTimer                 timer;
    private volatile     boolean                          inited;
    private final        AtomicLong                       connectionIds = new AtomicLong();
    private final        ReentrantLock                    creationLock  = new ReentrantLock();
    //
    private final        Map<String, RsfConnectorFactory> factories     = new LinkedHashMap<>();
    private final        Map<String, RsfConnector>        connectors    = new LinkedHashMap<>();
    private final        Map<Long, RsfChannel>            connections   = new LinkedHashMap<>();
    private final        Map<RsfListen, RsfConnector>     listens       = new LinkedHashMap<>();
    private final        List<ConnectorSubscriber>        subscribers   = new CopyOnWriteArrayList<>();

    public ConnectorManager(RsfContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    //
    // Subscription and message delivery
    //

    public synchronized void subscribe(ConnectorSubscriber subscriber) {
        this.requireReady();
        if (subscriber != null) {
            this.subscribers.add(subscriber);
        }
    }

    @Override
    public void onRequest(RsfChannel channel, long requestId, RequestPayload request) {
        this.notifySubscribers(channel, requestId, request);
    }

    @Override
    public void onResponse(RsfChannel channel, long requestId, ResponsePayload response) {
        this.notifySubscribers(channel, requestId, response);
    }

    @Override
    public void onFailure(RsfChannel channel, long requestId, ThrowPayload failure) {
        this.notifySubscribers(channel, requestId, failure);
    }

    private void notifySubscribers(RsfChannel channel, long requestId, Payload payload) {
        for (ConnectorSubscriber subscriber : this.subscribers) {
            try {
                subscriber.onMessage(channel, requestId, payload);
            } catch (RuntimeException failure) {
                logger.warn("RsfConnector message subscriber failed", failure);
            }
        }
    }

    //

    /** Discover factories without creating connectors or opening listeners. */
    public synchronized void init() {
        if (this.inited) {
            return;
        }

        Map<String, RsfConnectorFactory> discovered = new LinkedHashMap<>();
        for (RsfConnectorFactory factory : ServiceLoader.load(RsfConnectorFactory.class, this.context.getClassLoader())) {
            String type = listenType(factory.name());
            RsfConnectorFactory previous = discovered.putIfAbsent(type, factory);
            if (previous != null) {
                throw new IllegalStateException("Duplicate RsfConnectorFactory for listenType " + type + ": " + previous.getClass().getName() + " and " + factory.getClass().getName());
            }
        }

        this.timer = new HashedWheelTimer(task -> {
            Thread thread = new Thread(task, "RSF-Connector-timer");
            thread.setContextClassLoader(this.context.getClassLoader());
            return thread;
        });
        this.factories.putAll(discovered);
        this.inited = true;
    }

    /** Close listeners first, drain all registered channels, then release connector resources. */
    public void close() {
        // Finish connector creation before stopping admission. Release the lock before closing resources.
        this.creationLock.lock();
        try {
            this.inited = false;
        } finally {
            this.creationLock.unlock();
        }

        try {
            this.closeListeners(null);
            this.closeConnections(null);

            // Submitted writes have finished; release transport resources.
            for (RsfConnector connector : this.connectors.values()) {
                try {
                    connector.close();
                } catch (RuntimeException | Error failure) {
                    logger.warn("RsfConnector close failed", failure);
                }
            }
        } finally {
            HashedWheelTimer stopping;
            synchronized (this) {
                this.connectors.clear();
                this.connections.clear();
                this.listens.clear();
                this.factories.clear();
                stopping = this.timer;
                this.timer = null;
            }

            if (stopping != null) {
                try {
                    stopping.stop();
                } catch (RuntimeException | Error failure) {
                    logger.warn("RsfConnector timer stop failed", failure);
                }
            }
            this.subscribers.clear();
        }
    }

    /** Close registered listener resources, including sockets whose bind has not completed. */
    private void closeListeners(RsfConnector owner) {
        List<RsfListen> closing = new ArrayList<>();
        synchronized (this) {
            if (owner != null && this.inited) {
                this.connectors.remove(owner.config().name(), owner);
            }
            this.listens.entrySet().removeIf(entry -> {
                if (owner == null || entry.getValue() == owner) {
                    closing.add(entry.getKey());
                    return true;
                }
                return false;
            });
        }

        for (RsfListen listen : closing) {
            try {
                listen.close();
            } catch (RuntimeException | Error failure) {
                logger.warn("Listener close failed", failure);
            }
        }
    }

    /** Also used when an individual connector closes; other connectors' channels remain untouched. */
    private void closeConnections(RsfConnector owner) {
        List<RsfChannel> closing = new ArrayList<>();
        synchronized (this) {
            if (owner != null && this.inited) {
                this.connectors.remove(owner.config().name(), owner);
            }
            for (RsfChannel channel : this.connections.values()) {
                if (owner == null || channel.getConnector() == owner) {
                    closing.add(channel);
                }
            }
        }
        List<Future<RsfChannel>> pending = new ArrayList<>();
        for (RsfChannel channel : closing) {
            try {
                pending.add(channel.drainAndClose());
            } catch (RuntimeException | Error failure) {
                logger.warn("Channel drain and close failed", failure);
            }
        }
        for (Future<RsfChannel> future : pending) {
            awaitClose(future);
        }
        for (RsfChannel channel : closing) {
            this.onClosed(channel);
        }
    }

    private static void awaitClose(Future<RsfChannel> future) {
        boolean interrupted = false;
        try {
            for (; ; ) {
                try {
                    future.get();
                    return;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                } catch (ExecutionException failure) {
                    logger.warn("Channel drain and close failed", failure.getCause());
                    return;
                } catch (RuntimeException | Error failure) {
                    logger.warn("Channel drain and close failed", failure);
                    return;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    //

    public Future<RsfListen> bind(ConnectorConfig config) {
        try {
            this.requireReady();
            String type = listenType(config.listenType());

            RsfConnector connector = this.getOrCreateConnector(config, type);
            return connector.bind(config.address());
        } catch (Exception failure) {
            return failed(failure);
        }
    }

    /** Each call creates a connection. Both directions register through the connector's channel event. */
    public Future<RsfChannel> connect(ConnectorConfig config) {
        try {
            this.requireReady();
            String type = listenType(config.listenType());

            RsfConnector connector = this.getOrCreateConnector(config, type);
            return connector.connect(config.address());
        } catch (Exception failure) {
            return failed(failure);
        }
    }

    private RsfConnector getOrCreateConnector(ConnectorConfig config, String type) throws Exception {
        synchronized (this) {
            this.requireReady();
            RsfConnector ready = this.connectors.get(config.name());
            if (ready != null) {
                return ready;
            }
        }

        this.creationLock.lock();
        try {
            RsfConnectorFactory factory;
            synchronized (this) {
                this.requireReady();
                RsfConnector ready = this.connectors.get(config.name());
                if (ready != null) {
                    return ready;
                }

                factory = this.factories.get(type);
                if (factory == null) {
                    throw new IllegalArgumentException("No RsfConnectorFactory for listenType: " + type);
                }
            }

            RsfConnector connector = null;
            try {
                connector = factory.create(config, this);
                connector.onChannelConnected(this::onConnect);
                connector.onChannelClosed(this::onClosed);
                connector.onClosing(closing -> {
                    this.closeListeners(closing);
                    this.closeConnections(closing);
                });
                if (!config.name().equals(connector.config().name())) {
                    throw new IllegalArgumentException("Provider changed connector name");
                }

                connector.init();
                synchronized (this) {
                    this.connectors.put(config.name(), connector);
                }
                return connector;
            } catch (Exception | Error failure) {
                if (connector != null) {
                    try {
                        connector.close();
                    } catch (Throwable cleanup) {
                        if (cleanup != failure) {
                            failure.addSuppressed(cleanup);
                        }
                    }
                }
                throw failure;
            }
        } finally {
            this.creationLock.unlock();
        }
    }

    //

    /** The provider registers a channel before exposing it to callers or delivering messages. */
    private void onConnect(RsfChannel channel) {
        RsfConnector owner = channel.getConnector();
        synchronized (this) {
            if (this.inited && this.connectors.get(owner.config().name()) == owner) {
                RsfChannel previous = this.connections.get(channel.getChannelId());
                if (previous != null && previous != channel) {
                    throw new IllegalStateException("Duplicate connection ID: " + channel.getChannelId());
                }

                this.connections.put(channel.getChannelId(), channel);
                return;
            }
        }

        channel.close();
    }

    /** Connection termination, including normal close; independent of per-request onFailure(). */
    private void onClosed(RsfChannel channel) {
        synchronized (this) {
            if (this.connections.get(channel.getChannelId()) == channel) {
                this.connections.remove(channel.getChannelId());
            }
        }
    }

    /** Providers register physical listeners before bind completion, including sockets still binding. */
    public synchronized boolean onListen(RsfConnector owner, RsfListen listen) {
        if (!this.inited || this.connectors.get(owner.config().name()) != owner) {
            return false;
        }
        this.listens.put(listen, owner);
        return true;
    }

    public synchronized void onListenClosed(RsfConnector owner, RsfListen listen) {
        this.listens.remove(listen, owner);
    }

    //

    private static <T> Future<T> failed(Throwable cause) {
        BasicFuture<T> result = new BasicFuture<>();
        result.failed(cause);
        return result;
    }

    private static String listenType(String type) {
        Objects.requireNonNull(type, "listenType");
        if (type.trim().isEmpty()) {
            throw new IllegalArgumentException("listenType must not be blank");
        } else {
            return type.toLowerCase(Locale.ROOT);
        }
    }

    private void requireReady() {
        if (!this.inited) {
            throw new IllegalStateException("Manager is not initialized; call init() first");
        }
    }

    //
    // Queries and shared resources
    //

    public synchronized RsfConnector find(String name) {
        return this.connectors.get(name);
    }

    public synchronized RsfConnector forSchema(String schema) {
        for (RsfConnector connector : this.connectors.values()) {
            if (StringUtils.equalsIgnoreCase(connector.config().address().getSchema(), schema)) {
                return connector;
            }
        }
        return null;
    }

    public synchronized Set<String> protocols() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(this.connectors.keySet()));
    }

    public boolean isInitialized() {
        return this.inited;
    }

    public synchronized List<RsfListen> getListenList(RsfConnector owner) {
        List<RsfListen> result = new ArrayList<>();
        this.listens.forEach((listen, connector) -> {
            if (connector == owner) {
                result.add(listen);
            }
        });
        return Collections.unmodifiableList(result);
    }

    public synchronized RsfChannel findConnection(long id) {
        return this.connections.get(id);
    }

    /** IDs are unique within this manager and are never reset during its lifetime. */
    public long nextConnectionId() {
        return this.connectionIds.incrementAndGet();
    }

    public synchronized List<RsfChannel> getConnections() {
        return Collections.unmodifiableList(new ArrayList<>(this.connections.values()));
    }

    public RsfContext context() {
        return this.context;
    }

    public synchronized Cancellable schedule(Runnable task, long delayMillis) {
        if (this.timer == null) {
            throw new RejectedExecutionException("RsfConnector manager is not initialized or is closed");
        }
        Timeout timeout = this.timer.newTimeout(ignored -> task.run(), delayMillis, TimeUnit.MILLISECONDS);
        return timeout::cancel;
    }
}

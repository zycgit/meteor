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
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.concurrent.timer.HashedWheelTimer;
import net.hasor.cobble.concurrent.timer.Timeout;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.setting.SettingNode;
import net.hasor.rsf.RsfContext;
import net.hasor.rsf.RsfSettings;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.domain.ProtocolStatus;
import net.hasor.rsf.domain.RsfException;
import net.hasor.rsf.domain.payload.Payload;
import net.hasor.rsf.domain.payload.RequestPayload;
import net.hasor.rsf.domain.payload.ResponsePayload;
import net.hasor.rsf.domain.payload.ThrowPayload;

/**
 * Manages connector factories, initialized connectors and all incoming/outgoing channels.
 * The owner serializes init() and close(). RsfConnector creation and stopping admission are mutually exclusive.
 */
public class ConnectorManager implements AutoCloseable, ReceivedListener {
    private static final Logger                           logger             = Logger.getLogger(ConnectorManager.class);
    private final        RsfContext                       context;
    private              HashedWheelTimer                 timer;
    private volatile     boolean                          inited;
    private final        AtomicLong                       connectionIds      = new AtomicLong();
    private final        ReentrantLock                    creationLock       = new ReentrantLock();
    //
    private final        Map<String, RsfConnectorFactory> connectorFactories = new LinkedHashMap<>();
    private final        Map<String, ConnectorConfig>     connectorConfigs   = new LinkedHashMap<>();
    private final        Map<String, RsfConnector>        connectors         = new LinkedHashMap<>();
    //
    private final        Map<Long, RsfChannel>            connections        = new LinkedHashMap<>();
    private final        List<ConnectorSubscriber>        subscribers        = new CopyOnWriteArrayList<>();

    public ConnectorManager(RsfContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    //
    // Connector configuration
    //

    /** Configured endpoints, including those that have not created a connector yet. */
    public synchronized Collection<ConnectorConfig> configurations() {
        this.requireReady();
        return Collections.unmodifiableList(new ArrayList<>(this.connectorConfigs.values()));
    }

    private Map<String, ConnectorConfig> readConfigurations() {
        RsfSettings settings = this.context.getSettings();
        Map<String, ConnectorConfig> configured = new LinkedHashMap<>();
        for (String name : settings.getProtocos()) {
            String key = settings.getProtocolConfigKey(name);
            Map<String, String> options = new HashMap<>();
            for (SettingNode endpoint : settings.getNodeArray(key)) {
                for (SettingNode child : endpoint.getSubNodes()) {
                    this.copyOptions(settings, key, child, child.getName(), options);
                }
            }

            options.put("connectTimeout", Integer.toString(settings.getConnectTimeout()));
            ConnectorConfig config = new ConnectorConfig(name, settings.getBindAddressSet(name), options);
            String schema = config.address().getSchema().toLowerCase(Locale.ROOT);
            if (configured.putIfAbsent(schema, config) != null) {
                throw new IllegalArgumentException("Duplicate connector address scheme: " + schema);
            }
        }
        return configured;
    }

    private void copyOptions(RsfSettings settings, String base, SettingNode node, String path, Map<String, String> options) {
        String value = settings.getString(base + "." + path);
        if (value != null) {
            options.put(path, value);
        }
        for (SettingNode child : node.getSubNodes()) {
            this.copyOptions(settings, base, child, path + "." + child.getName(), options);
        }
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
    // life method include init and close
    //

    /** Discover factories and read configurations without creating connectors or opening listeners. */
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

        Map<String, ConnectorConfig> configured = this.readConfigurations();
        this.timer = new HashedWheelTimer(task -> {
            Thread thread = new Thread(task, "RSF-Connector-timer");
            thread.setContextClassLoader(this.context.getClassLoader());
            return thread;
        });
        this.connectorFactories.putAll(discovered);
        this.connectorConfigs.putAll(configured);
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
            for (RsfConnector connector : this.connectors.values()) {
                try {
                    connector.closeBind();
                } catch (RuntimeException | Error failure) {
                    logger.warn("RsfConnector listener close failed", failure);
                }
            }

            // Each connector drains its connections before releasing transport resources.
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
                this.connectorFactories.clear();
                this.connectorConfigs.clear();
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

    /** Also used when an individual connector closes; other connectors' channels remain untouched. */
    private void closeConnections(RsfConnector owner) {
        List<RsfChannel> closing = new ArrayList<>();
        synchronized (this) {
            if (this.inited) {
                this.connectors.remove(owner.config().name(), owner);
            }
            for (RsfChannel channel : this.connections.values()) {
                if (channel.getConnector() == owner) {
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
    // bind port
    //

    public Future<RsfListen> bind(ConnectorConfig config) {
        try {
            RsfConnector connector = this.getOrCreateConnector(config);
            return connector.bind(config.address());
        } catch (Exception failure) {
            return failed(failure);
        }
    }

    /** Each call creates a connection. Both directions register through the connector's channel event. */
    public Future<RsfChannel> connect(InterAddress address) {
        try {
            ConnectorConfig config;
            synchronized (this) {
                this.requireReady();
                config = this.connectorConfigs.get(address.getSchema().toLowerCase(Locale.ROOT));
            }
            if (config == null) {
                throw new RsfException(ProtocolStatus.ProtocolUndefined, "No connector configured for " + address.getSchema());
            }
            RsfConnector connector = this.getOrCreateConnector(config);
            return connector.connect(address);
        } catch (Exception failure) {
            return failed(failure);
        }
    }

    private RsfConnector getOrCreateConnector(ConnectorConfig config) throws Exception {
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

                String type = config.listenType();
                factory = this.connectorFactories.get(type);
                if (factory == null) {
                    throw new IllegalArgumentException("No RsfConnectorFactory for listenType: " + type);
                }
            }

            RsfConnector connector = null;
            try {
                connector = factory.create(config, this);
                connector.onChannelConnected(this::onConnect);
                connector.onChannelClosed(this::onClosed);
                connector.onClosing(this::closeConnections);
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
                RsfChannel previous = this.connections.putIfAbsent(channel.getChannelId(), channel);
                if (previous != null && previous != channel) {
                    throw new IllegalStateException("Duplicate connection ID: " + channel.getChannelId());
                }

                return;
            }
        }

        channel.close();
    }

    /** Connection termination, including normal close; independent of per-request onFailure(). */
    private synchronized void onClosed(RsfChannel channel) {
        this.connections.remove(channel.getChannelId(), channel);
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

    public synchronized Set<String> protocols() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(this.connectors.keySet()));
    }

    public boolean isInitialized() {
        return this.inited;
    }

    /** IDs are unique within this manager and are never reset during its lifetime. */
    public long nextConnectionId() {
        return this.connectionIds.incrementAndGet();
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

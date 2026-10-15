/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
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
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;

/**
 * Manages configured network endpoints, protocol routes and message subscribers.
 * The owner serializes init() and close(). Endpoint creation and stopping admission are mutually exclusive.
 */
public class ConnectorManager implements AutoCloseable {
    private static final Logger                            logger           = Logger.getLogger(ConnectorManager.class);
    private final        MetContext                        context;
    private              HashedWheelTimer                  timer;
    private volatile     State                             state            = State.STOPPED;
    private final        AtomicLong                        connectionIds    = new AtomicLong();
    private final        ReentrantLock                     creationLock     = new ReentrantLock();
    //
    private final        ConnectorAssembler                assembler;
    private final        Map<String, ConnectorConfig>      connectorConfigs = new LinkedHashMap<>();
    private final        Map<String, ConnectorEndpoint<?>> connectors       = new LinkedHashMap<>();
    private final        List<ConnectorSubscriber>         subscribers      = new CopyOnWriteArrayList<>();

    private enum State {
        STOPPED,
        ACTIVE,
        OPENING,
        INBOUND_STOPPED,
        CLOSED
    }

    public ConnectorManager(MetContext context, ConnectorAssembler assembler) {
        this.context = Objects.requireNonNull(context, "context");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
    }

    private Map<String, ConnectorConfig> readConfigurations() {
        Map<String, ConnectorConfig> configured = new LinkedHashMap<>();
        for (ConnectorConfig config : this.context.getSettings().getConnectorConfigs()) {
            if (configured.putIfAbsent(config.name(), config) != null) {
                throw new IllegalArgumentException("Duplicate endpoint: " + config.name());
            }
        }
        return configured;
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

    void received(MetChannel channel, long requestId, Payload payload) {
        for (ConnectorSubscriber subscriber : this.subscribers) {
            try {
                subscriber.onMessage(channel, requestId, payload);
            } catch (RuntimeException failure) {
                logger.warn("Connector message subscriber failed", failure);
            }
        }
    }

    //
    // life method include init and close
    //

    /** Prepare the supplied assembler and read configurations without opening network resources. */
    public void init() {
        this.creationLock.lock();
        try {
            synchronized (this) {
                if (this.state == State.CLOSED) {
                    throw new IllegalStateException("Connector manager is closed");
                }
                if (this.state != State.STOPPED) {
                    return;
                }
            }

            Set<String> supported = new HashSet<>();
            for (String name : this.assembler.listenTypes(this.context.getClassLoader())) {
                supported.add(listenType(name));
            }

            Map<String, ConnectorConfig> configured = this.readConfigurations();
            for (ConnectorConfig config : configured.values()) {
                if (!supported.contains(config.listenType())) {
                    throw new IllegalArgumentException("No connector factory for " + config.listenType());
                }
            }

            HashedWheelTimer initializedTimer = new HashedWheelTimer(task -> {
                Thread thread = new Thread(task, "Meteor-Connector-timer");
                thread.setContextClassLoader(this.context.getClassLoader());
                return thread;
            });

            synchronized (this) {
                this.connectorConfigs.putAll(configured);
                this.timer = initializedTimer;
                this.state = State.ACTIVE;
            }
        } finally {
            this.creationLock.unlock();
        }
    }

    /** Bind or restart every configured listener, exposing inbound traffic only after all endpoints are ready. */
    public void startInbound() throws Exception {
        this.creationLock.lock();
        try {
            List<ConnectorConfig> configured;
            boolean restarting;
            synchronized (this) {
                this.requireReady();
                restarting = this.state == State.INBOUND_STOPPED;
                this.state = State.OPENING;
                configured = new ArrayList<>(this.connectorConfigs.values());
            }

            try {
                for (ConnectorConfig config : configured) {
                    if (!config.bindEnabled()) {
                        continue;
                    }

                    ConnectorEndpoint<?> connector = this.getOrCreateConnector(config);
                    if (restarting && connector.hasListener()) {
                        connector.startInbound();
                    }
                    connector.bind().get();
                }
                this.state = State.ACTIVE;
            } catch (Exception | Error failure) {
                this.state = State.INBOUND_STOPPED;
                this.stopAllInbound(failure);
                throw failure;
            }
        } finally {
            this.creationLock.unlock();
        }
    }

    /** Stop accepting new inbound traffic and start draining accepted connections. */
    public void stopInbound() {
        this.creationLock.lock();
        try {
            synchronized (this) {
                this.requireReady();
                this.state = State.INBOUND_STOPPED;
            }

            Throwable failure = this.stopAllInbound(null);

            if (failure instanceof Error error) {
                throw error;
            }
            if (failure != null) {
                throw (RuntimeException) failure;
            }
        } finally {
            this.creationLock.unlock();
        }
    }

    /** Close listeners first, drain all registered channels, then release connector resources. */
    public void close() {
        // Finish connector creation before stopping admission. Release the lock before closing resources.
        this.creationLock.lock();
        try {
            if (this.state == State.CLOSED) {
                return;
            }
            this.state = State.CLOSED;
        } finally {
            this.creationLock.unlock();
        }

        List<ConnectorEndpoint<?>> closing = this.snapshotConnectors();
        try {
            for (ConnectorEndpoint<?> connector : closing) {
                try {
                    connector.closeBind();
                } catch (RuntimeException | Error failure) {
                    logger.warn("Connector listener close failed", failure);
                }
            }

            // Each connector drains its connections before releasing transport resources.
            for (ConnectorEndpoint<?> connector : closing) {
                try {
                    connector.close();
                } catch (RuntimeException | Error failure) {
                    logger.warn("Connector close failed", failure);
                }
            }
        } finally {
            HashedWheelTimer stopping;
            synchronized (this) {
                this.connectors.clear();
                this.connectorConfigs.clear();
                stopping = this.timer;
                this.timer = null;
            }

            if (stopping != null) {
                try {
                    stopping.stop();
                } catch (RuntimeException | Error failure) {
                    logger.warn("Connector timer stop failed", failure);
                }
            }
            this.subscribers.clear();
        }
    }

    /** Connect only when the published address scheme identifies one configured mount. */
    public Future<MetChannel> connect(InterAddress address) {
        return this.connect(address, null);
    }

    /** Resolve a published address scheme together with its logical protocol. */
    public Future<MetChannel> connect(InterAddress address, String protocol) {
        try {
            MountTarget target;
            synchronized (this) {
                this.requireReady();
                target = this.resolve(address.getSchema(), protocol);
            }
            return this.connect(target, address);
        } catch (Exception failure) {
            return failed(failure);
        }
    }

    /** Connect through one endpoint using an exact mount, logical protocol or published scheme selector. */
    public Future<MetChannel> connect(String endpointName, String selector, InterAddress address) {
        try {
            MountTarget target;
            synchronized (this) {
                this.requireReady();
                ConnectorConfig endpoint = this.connectorConfigs.get(endpointName);
                if (endpoint == null) {
                    throw undefined("Unknown connector endpoint: " + endpointName);
                }

                List<MountTarget> available = new ArrayList<>();
                for (ProtocolConfig mount : endpoint.protocols()) {
                    available.add(new MountTarget(endpoint, mount));
                }
                List<MountTarget> selected = this.selectForConnect(available, selector, address.getSchema());
                MountTarget resolved = this.requireSingleMount(selected, address.getSchema(), selector);
                ProtocolConfig mount = resolved.mount();
                target = new MountTarget(endpoint, mount);
            }
            return this.connect(target, address);
        } catch (Exception failure) {
            return failed(failure);
        }
    }

    private Future<MetChannel> connect(MountTarget target, InterAddress address) throws Exception {
        ConnectorEndpoint<?> connector = this.getOrCreateConnector(target.endpoint());
        return connector.connect(target.mount().mountId(), address);
    }

    private MountTarget resolve(String scheme, String protocol) {
        if (protocol == null) {
            return this.requireSingleMount(this.findConfiguredMountsByScheme(scheme), scheme, null);
        }

        List<MountTarget> candidates = this.selectForConnect(this.configuredMounts(), protocol, scheme);
        return this.requireSingleMount(candidates, scheme, protocol);
    }

    private List<MountTarget> selectForConnect(//
            Collection<MountTarget> candidates, String selector, String scheme) {
        List<MountTarget> mounted = this.selectMounted(candidates, selector);
        if (!mounted.isEmpty()) {
            mounted.removeIf(candidate -> !StringUtils.equalsIgnoreCase(candidate.mount().scheme(), scheme));
            return mounted;
        }

        boolean logicalName = this.isLogicalSelector(selector);
        List<MountTarget> selected = new ArrayList<>();
        for (MountTarget candidate : candidates) {
            ProtocolConfig mount = candidate.mount();
            if (!StringUtils.equalsIgnoreCase(mount.scheme(), scheme)) {
                continue;
            }

            String value = logicalName ? mount.name() : mount.scheme();
            if (StringUtils.equalsIgnoreCase(value, selector)) {
                selected.add(candidate);
            }
        }
        return selected;
    }

    private List<MountTarget> configuredMounts() {
        List<MountTarget> candidates = new ArrayList<>();
        for (ConnectorConfig endpoint : this.connectorConfigs.values()) {
            for (ProtocolConfig mount : endpoint.protocols()) {
                candidates.add(new MountTarget(endpoint, mount));
            }
        }
        return candidates;
    }

    private List<MountTarget> findConfiguredMountsByScheme(String scheme) {
        List<MountTarget> candidates = new ArrayList<>();
        for (ConnectorConfig endpoint : this.connectorConfigs.values()) {
            for (ProtocolConfig mount : endpoint.protocols()) {
                if (StringUtils.equalsIgnoreCase(mount.scheme(), scheme)) {
                    candidates.add(new MountTarget(endpoint, mount));
                }
            }
        }
        return candidates;
    }

    private List<MountTarget> selectMounts(Collection<MountTarget> candidates, String selector) {
        List<MountTarget> mounted = this.selectMounted(candidates, selector);
        if (!mounted.isEmpty()) {
            return mounted;
        }

        boolean logicalName = this.isLogicalSelector(selector);
        List<MountTarget> selected = new ArrayList<>();
        for (MountTarget candidate : candidates) {
            ProtocolConfig mount = candidate.mount();
            String value = logicalName ? mount.name() : mount.scheme();
            if (StringUtils.equalsIgnoreCase(value, selector)) {
                selected.add(candidate);
            }
        }
        return selected;
    }

    private List<MountTarget> selectMounted(Collection<MountTarget> candidates, String selector) {
        List<MountTarget> mounted = new ArrayList<>();
        for (MountTarget candidate : candidates) {
            if (StringUtils.equalsIgnoreCase(candidate.mount().mountId(), selector)) {
                mounted.add(candidate);
            }
        }
        return mounted;
    }

    private boolean isLogicalSelector(String selector) {
        for (MountTarget candidate : this.configuredMounts()) {
            if (StringUtils.equalsIgnoreCase(candidate.mount().name(), selector)) {
                return true;
            }
        }
        return false;
    }

    private MountTarget requireSingleMount(List<MountTarget> candidates, String scheme, String protocol) {
        if (candidates.isEmpty()) {
            String message = "No connector configured for " + scheme;
            if (protocol != null) {
                message += " and protocol " + protocol;
            }
            throw undefined(message);
        }
        if (candidates.size() > 1) {
            String message = "Ambiguous connector mount for scheme " + scheme;
            if (protocol != null) {
                message += " and protocol " + protocol;
            }
            throw undefined(message);
        }
        return candidates.get(0);
    }

    private static MetException undefined(String message) {
        return new MetException(ProtocolStatus.ProtocolUndefined, message);
    }

    private static final class MountTarget {
        private final ConnectorConfig endpoint;
        private final ProtocolConfig  mount;

        private MountTarget(ConnectorConfig endpoint, ProtocolConfig mount) {
            this.endpoint = endpoint;
            this.mount = mount;
        }

        private ConnectorConfig endpoint() {
            return this.endpoint;
        }

        private ProtocolConfig mount() {
            return this.mount;
        }
    }

    private ConnectorEndpoint<?> getOrCreateConnector(ConnectorConfig config) throws Exception {
        synchronized (this) {
            this.requireReady();
            ConnectorEndpoint<?> ready = this.connectors.get(config.name());
            if (ready != null) {
                return ready;
            }
        }

        this.creationLock.lock();
        try {
            synchronized (this) {
                this.requireReady();
                ConnectorEndpoint<?> ready = this.connectors.get(config.name());
                if (ready != null) {
                    return ready;
                }
            }

            ConnectorEndpoint<?> connector = null;
            try {
                connector = this.assembler.create(config, this);
                if (!StringUtils.equals(config.name(), connector.config().name())) {
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

    private static <T> Future<T> failed(Throwable cause) {
        BasicFuture<T> result = new BasicFuture<>();
        result.failed(cause);
        return result;
    }

    private static String listenType(String type) {
        Objects.requireNonNull(type, "listenType");
        if (StringUtils.isBlank(type)) {
            throw new IllegalArgumentException("listenType must not be blank");
        }
        return type.toLowerCase(Locale.ROOT);
    }

    private void requireReady() {
        if (this.state == State.STOPPED) {
            throw new IllegalStateException("Manager is not initialized; call init() first");
        }
        if (this.state == State.CLOSED) {
            throw new IllegalStateException("Connector manager is closed");
        }
    }

    boolean allowsBindings() {
        return this.state == State.ACTIVE || this.state == State.OPENING;
    }

    boolean acceptsInbound() {
        return this.state == State.ACTIVE;
    }

    private synchronized List<ConnectorEndpoint<?>> snapshotConnectors() {
        return new ArrayList<>(this.connectors.values());
    }

    private Throwable stopAllInbound(Throwable failure) {
        Throwable result = failure;
        for (ConnectorEndpoint<?> connector : this.snapshotConnectors()) {
            try {
                connector.stopInbound();
            } catch (RuntimeException | Error cleanup) {
                if (result == null) {
                    result = cleanup;
                } else if (result != cleanup) {
                    result.addSuppressed(cleanup);
                }
            }
        }
        return result;
    }

    //
    // Queries and shared resources
    //

    /** Active listener address selected by logical protocol first, then by address scheme alias. */
    public synchronized InterAddress bindAddress(String protocol) {
        List<InterAddress> addresses = this.bindAddresses(protocol);
        if (addresses.isEmpty()) {
            return null;
        }
        if (addresses.size() > 1) {
            throw new IllegalArgumentException("Ambiguous bound protocol address: " + protocol);
        }
        return addresses.get(0);
    }

    /** All active listener addresses selected by logical protocol first, then by address scheme alias. */
    public synchronized List<InterAddress> bindAddresses(String protocol) {
        List<MountTarget> targets = this.findBoundMounts(protocol);
        Map<String, InterAddress> addresses = new LinkedHashMap<>();
        for (MountTarget target : targets) {
            InterAddress address = this.bindAddressByMount(target.endpoint().name(), target.mount().mountId());
            if (address != null) {
                String key = target.endpoint().name() + "\u0000" + target.mount().scheme();
                addresses.putIfAbsent(key, address);
            }
        }
        return List.copyOf(addresses.values());
    }

    /** Active listener address on one endpoint, selected by mount identity, logical protocol or scheme alias. */
    public synchronized InterAddress bindAddress(String endpointName, String protocol) {
        ConnectorEndpoint<?> endpoint = this.connectors.get(endpointName);
        if (endpoint == null) {
            return null;
        }

        ProtocolConfig exact = endpoint.config().mount(protocol);
        if (exact != null) {
            return this.bindAddressByMount(endpointName, exact.mountId());
        }

        List<MountTarget> available = new ArrayList<>();
        for (ProtocolConfig mount : endpoint.config().protocols()) {
            available.add(new MountTarget(endpoint.config(), mount));
        }
        List<MountTarget> selected = this.selectMounts(available, protocol);
        if (selected.isEmpty()) {
            return null;
        }
        Map<String, MountTarget> schemes = new LinkedHashMap<>();
        for (MountTarget target : selected) {
            schemes.putIfAbsent(target.mount().scheme(), target);
        }
        if (schemes.size() > 1) {
            String message = "Ambiguous protocol mount on endpoint " + endpointName + ": " + protocol;
            throw new IllegalArgumentException(message);
        }
        MountTarget target = schemes.values().iterator().next();
        return this.bindAddressByMount(endpointName, target.mount().mountId());
    }

    private InterAddress bindAddressByMount(String endpointName, String mountId) {
        ConnectorEndpoint<?> endpoint = this.connectors.get(endpointName);
        ProtocolConfig mount = endpoint == null ? null : endpoint.config().mount(mountId);
        if (mount == null) {
            return null;
        }
        InterAddress actual = endpoint.getBindAddress();
        if (actual == null) {
            return null;
        }
        return new InterAddress(mount.scheme(), actual.getHost(), actual.getPort(), actual.getFormUnit());
    }

    private List<MountTarget> findBoundMounts(String selector) {
        List<MountTarget> available = new ArrayList<>();
        for (ConnectorEndpoint<?> endpoint : this.connectors.values()) {
            if (endpoint.getBindAddress() == null) {
                continue;
            }
            for (ProtocolConfig mount : endpoint.config().protocols()) {
                available.add(new MountTarget(endpoint.config(), mount));
            }
        }
        return this.selectMounts(available, selector);
    }

    public synchronized Set<String> protocols() {
        Set<String> protocols = new LinkedHashSet<>();
        for (ConnectorEndpoint<?> connector : this.connectors.values()) {
            for (ProtocolConfig protocol : connector.config().protocols()) {
                protocols.add(protocol.name());
            }
        }

        return Collections.unmodifiableSet(protocols);
    }

    public boolean isInitialized() {
        return this.state != State.STOPPED && this.state != State.CLOSED;
    }

    /** IDs are unique within this manager and are never reset during its lifetime. */
    long nextConnectionId() {
        return this.connectionIds.incrementAndGet();
    }

    public MetContext context() {
        return this.context;
    }

    public synchronized Cancellable schedule(Runnable task, long delayMillis) {
        if (this.timer == null) {
            throw new RejectedExecutionException("Connector manager is not initialized or is closed");
        }
        Timeout timeout = this.timer.newTimeout(ignored -> task.run(), delayMillis, TimeUnit.MILLISECONDS);
        return timeout::cancel;
    }
}

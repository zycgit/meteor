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
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetContextEvent;
import net.hasor.meteor.MetContextEventType;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.transport.SocketTransport;
import net.hasor.meteor.connector.transport.TransportRuntime;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;

/**
 * Manages configured network endpoints, protocol routes and message subscribers.
 * The owner serializes init() and close(). Endpoint creation and stopping admission are mutually exclusive.
 */
public class ConnectorManager implements AutoCloseable {
    private static final Logger                            logger         = Logger.getLogger(ConnectorManager.class);
    private final        MetContext                        context;
    private final        TransportRuntime                  runtime;
    private              HashedWheelTimer                  timer;
    private volatile     State                             state          = State.STOPPED;
    private volatile     Thread                            closeThread;
    private final        AtomicLong                        connectionIds  = new AtomicLong();
    private final        ReentrantLock                     lifecycleLock  = new ReentrantLock();
    private final        BasicFuture<Void>                 closeCompleted = new BasicFuture<>();
    //
    private final        PlanCompiler                      compiler       = new PlanCompiler();
    private final        Map<String, ConnectorEndpoint<?>> connectors     = new LinkedHashMap<>();
    private final        List<ConnectorSubscriber>         subscribers    = new CopyOnWriteArrayList<>();
    private              RouteRegistry                     routeRegistry;

    private enum State {
        STOPPED,
        ACTIVE,
        OPENING,
        INBOUND_STOPPED,
        CLOSED
    }

    public ConnectorManager(MetContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.runtime = new TransportRuntime(this.context.getClassLoader(), this::schedule);
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

    /** Compile every configured endpoint without opening network resources. */
    public void init() {
        this.lifecycleLock.lock();
        try {
            synchronized (this) {
                if (this.state == State.CLOSED) {
                    throw new IllegalStateException("Connector manager is closed");
                }
                if (this.state != State.STOPPED) {
                    return;
                }
            }

            // 发现传输层和协议扩展，然后统一校验全部端点配置。
            this.compiler.discover(this.context.getClassLoader());
            Map<String, ConnectorConfig> configured = this.readConfigurations();
            this.compiler.prepareAll(configured.values());

            // 编译启动计划，并激活独立的运行期路由表。
            RouteRegistry initializedRoutes = new RouteRegistry(this.context);
            Map<String, ConnectorEndpoint<?>> endpoints = new LinkedHashMap<>();
            for (ConnectorConfig config : configured.values()) {
                PlanConnector<?> plan = this.compiler.compile(config);
                endpoints.put(config.name(), plan.activate(this, initializedRoutes));
            }

            // The timer and compiled plans become visible as one initialized manager state.
            HashedWheelTimer initializedTimer = new HashedWheelTimer(task -> {
                Thread thread = new Thread(task, "Meteor-Connector-timer");
                thread.setContextClassLoader(this.context.getClassLoader());
                return thread;
            });

            synchronized (this) {
                this.connectors.putAll(endpoints);
                this.routeRegistry = initializedRoutes;
                this.timer = initializedTimer;
                this.state = State.ACTIVE;
            }
        } finally {
            this.lifecycleLock.unlock();
        }
    }

    /** Bind or restart every configured listener, exposing inbound traffic only after all endpoints are ready. */
    public void startInbound() throws Exception {
        this.lifecycleLock.lock();
        try {
            List<ConnectorEndpoint<?>> endpoints;
            boolean restarting;
            synchronized (this) {
                this.requireReady();
                restarting = this.state == State.INBOUND_STOPPED;
                this.state = State.OPENING;
                endpoints = this.bindEndpoints();
            }

            try {
                // Build all configured inbound transports before any listener is exposed.
                for (ConnectorEndpoint<?> connector : endpoints) {
                    connector.init();
                }

                // Existing listeners resume only after every endpoint can serve traffic.
                for (ConnectorEndpoint<?> connector : endpoints) {
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
            this.lifecycleLock.unlock();
        }
    }

    /** Stop accepting new inbound traffic and start draining accepted connections. */
    public void stopInbound() {
        this.lifecycleLock.lock();
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
            this.lifecycleLock.unlock();
        }
    }

    /** Close listeners first, drain all registered channels, then release connector resources. */
    public void close() {
        boolean startClose = false;
        List<ConnectorEndpoint<?>> closing = Collections.emptyList();
        this.lifecycleLock.lock();
        try {
            if (this.state != State.CLOSED) {
                this.state = State.CLOSED;
                closing = this.snapshotConnectors();
                startClose = true;
            }
        } finally {
            this.lifecycleLock.unlock();
        }

        if (startClose) {
            this.startClose(closing);
        }
        if (Thread.currentThread() != this.closeThread && !SocketTransport.isCurrentThreadInTransport()) {
            this.awaitClose();
        }
    }

    private void startClose(List<ConnectorEndpoint<?>> closing) {
        Thread lifecycle = new Thread(() -> this.closeResources(closing), "Meteor-Connector-close");
        lifecycle.setContextClassLoader(this.context.getClassLoader());
        lifecycle.setDaemon(false);
        this.closeThread = lifecycle;
        lifecycle.start();
    }

    private void closeResources(List<ConnectorEndpoint<?>> closing) {
        try {
            // Stop every listener before any endpoint begins draining its admitted work.
            for (ConnectorEndpoint<?> connector : closing) {
                try {
                    connector.closeBind();
                } catch (RuntimeException | Error failure) {
                    logger.warn("Connector listener close failed", failure);
                }
            }

            for (ConnectorEndpoint<?> connector : closing) {
                try {
                    connector.beginClose();
                } catch (RuntimeException | Error failure) {
                    logger.warn("Connector close preparation failed", failure);
                }
            }

            // Drain every logical channel before asking physical connections to finish.
            List<Future<?>> drainingChannels = new ArrayList<>();
            for (ConnectorEndpoint<?> connector : closing) {
                try {
                    drainingChannels.addAll(connector.drainChannels());
                } catch (RuntimeException | Error failure) {
                    logger.warn("Connector channel drain failed", failure);
                }
            }
            this.awaitCloseFutures(drainingChannels, "Connector channel drain failed");

            // Physical drains include transport writes which do not belong to one protocol session.
            List<Future<?>> drainingConnections = new ArrayList<>();
            for (ConnectorEndpoint<?> connector : closing) {
                try {
                    drainingConnections.addAll(connector.drainConnections());
                } catch (RuntimeException | Error failure) {
                    logger.warn("Connector connection drain failed", failure);
                }
            }
            this.awaitCloseFutures(drainingConnections, "Connector connection drain failed");
        } finally {
            for (ConnectorEndpoint<?> connector : closing) {
                try {
                    connector.closeTransport();
                } catch (RuntimeException | Error failure) {
                    logger.warn("Connector transport close failed", failure);
                }
            }

            HashedWheelTimer stopping;
            RouteRegistry stoppingRoutes;
            synchronized (this) {
                this.connectors.clear();
                stoppingRoutes = this.routeRegistry;
                this.routeRegistry = null;
                stopping = this.timer;
                this.timer = null;
            }

            if (stoppingRoutes != null) {
                stoppingRoutes.clear();
            }

            if (stopping != null) {
                try {
                    stopping.stop();
                } catch (RuntimeException | Error failure) {
                    logger.warn("Connector timer stop failed", failure);
                }
            }
            this.subscribers.clear();
            this.closeCompleted.completed(null);
        }
    }

    private void awaitCloseFutures(List<Future<?>> pending, String message) {
        boolean interrupted = false;
        for (Future<?> future : pending) {
            for (; ; ) {
                try {
                    future.get();
                    break;
                } catch (InterruptedException failure) {
                    interrupted = true;
                } catch (ExecutionException | RuntimeException failure) {
                    logger.warn(message, failure);
                    break;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void awaitClose() {
        boolean interrupted = false;
        for (; ; ) {
            try {
                this.closeCompleted.get();
                break;
            } catch (InterruptedException failure) {
                interrupted = true;
            } catch (ExecutionException failure) {
                logger.warn("Connector close failed", failure);
                break;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Apply one synchronous service declaration event to every active endpoint route. */
    protected final void serviceEvent(MetContextEvent event) {
        MetContextEventType eventType = event.getType();
        if (eventType != MetContextEventType.REGISTERED && eventType != MetContextEventType.REMOVED) {
            return;
        }

        this.lifecycleLock.lock();
        try {
            RouteRegistry activeRoutes;
            synchronized (this) {
                this.requireReady();
                activeRoutes = this.routeRegistry;
            }

            if (eventType == MetContextEventType.REGISTERED) {
                activeRoutes.registerService(event.getServiceInfo());
            } else {
                activeRoutes.removeService(event.getServiceInfo());
            }
        } finally {
            this.lifecycleLock.unlock();
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
                ConnectorEndpoint<?> connector = this.connectors.get(endpointName);
                if (connector == null) {
                    throw undefined("Unknown connector endpoint: " + endpointName);
                }

                ConnectorConfig endpoint = connector.config();
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
        this.lifecycleLock.lock();
        try {
            ConnectorEndpoint<?> connector;
            synchronized (this) {
                this.requireReady();
                connector = this.connectors.get(target.endpoint().name());
                if (connector == null) {
                    throw undefined("Unknown connector endpoint: " + target.endpoint().name());
                }
            }

            // Starting the transport connection and stopping admission are one lifecycle decision.
            connector.init();
            return connector.connect(target.mount().mountId(), address);
        } finally {
            this.lifecycleLock.unlock();
        }
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
        for (ConnectorEndpoint<?> connector : this.connectors.values()) {
            ConnectorConfig endpoint = connector.config();
            for (ProtocolConfig mount : endpoint.protocols()) {
                candidates.add(new MountTarget(endpoint, mount));
            }
        }
        return candidates;
    }

    private List<MountTarget> findConfiguredMountsByScheme(String scheme) {
        List<MountTarget> candidates = new ArrayList<>();
        for (ConnectorEndpoint<?> connector : this.connectors.values()) {
            ConnectorConfig endpoint = connector.config();
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

    private static <T> Future<T> failed(Throwable cause) {
        BasicFuture<T> result = new BasicFuture<>();
        result.failed(cause);
        return result;
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

    private List<ConnectorEndpoint<?>> bindEndpoints() {
        List<ConnectorEndpoint<?>> endpoints = new ArrayList<>();
        for (ConnectorEndpoint<?> endpoint : this.connectors.values()) {
            if (endpoint.config().bindEnabled()) {
                endpoints.add(endpoint);
            }
        }
        return endpoints;
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

    TransportRuntime runtime() {
        return this.runtime;
    }

    public synchronized Cancellable schedule(Runnable task, long delayMillis) {
        if (this.timer == null) {
            throw new RejectedExecutionException("Connector manager is not initialized or is closed");
        }
        Timeout timeout = this.timer.newTimeout(ignored -> task.run(), delayMillis, TimeUnit.MILLISECONDS);
        return timeout::cancel;
    }
}

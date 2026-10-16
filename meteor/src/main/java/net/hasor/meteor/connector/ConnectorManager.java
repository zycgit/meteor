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
import net.hasor.meteor.connector.transport.SocketRuntime;
import net.hasor.meteor.connector.transport.TransportRuntimeServices;
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
    private final        TransportRuntimeServices          transportRuntimeServices;
    private              HashedWheelTimer                  timer;
    private volatile     State                             state          = State.STOPPED;
    private volatile     Thread                            closeThread;
    private final        AtomicLong                        channelIds     = new AtomicLong();
    private final        ReentrantLock                     lifecycleLock  = new ReentrantLock();
    private final        BasicFuture<Void>                 closeCompleted = new BasicFuture<>();
    //
    private final        PlanCompiler                      compiler       = new PlanCompiler();
    private final        Map<String, ConnectorEndpoint<?>> endpoints      = new LinkedHashMap<>();
    private final        List<ConnectorSubscriber>         subscribers    = new CopyOnWriteArrayList<>();
    private              RouteServiceRegistry              serviceRouteRegistry;

    private enum State {
        STOPPED,
        ACTIVE,
        OPENING,
        INBOUND_STOPPED,
        CLOSED
    }

    public ConnectorManager(MetContext context) {
        this.context = Objects.requireNonNull(context, "context");
        this.transportRuntimeServices = new TransportRuntimeServices(this.context.getClassLoader(), this::schedule);
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

    void publishMessage(MetChannel channel, long requestId, Payload payload) {
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
            RouteServiceRegistry initializedServiceRoutes = new RouteServiceRegistry(this.context);
            Map<String, ConnectorEndpoint<?>> endpoints = new LinkedHashMap<>();
            for (ConnectorConfig config : configured.values()) {
                PlanEndpoint<?> plan = this.compiler.compile(config);
                endpoints.put(config.name(), plan.activate(this, initializedServiceRoutes));
            }

            // The timer and compiled plans become visible as one initialized manager state.
            HashedWheelTimer initializedTimer = new HashedWheelTimer(task -> {
                Thread thread = new Thread(task, "Meteor-Connector-timer");
                thread.setContextClassLoader(this.context.getClassLoader());
                return thread;
            });

            synchronized (this) {
                this.endpoints.putAll(endpoints);
                this.serviceRouteRegistry = initializedServiceRoutes;
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
                for (ConnectorEndpoint<?> endpoint : endpoints) {
                    endpoint.init();
                }

                // Existing listeners resume only after every endpoint can serve traffic.
                for (ConnectorEndpoint<?> endpoint : endpoints) {
                    if (restarting && endpoint.hasListener()) {
                        endpoint.startInbound();
                    }
                    endpoint.bind().get();
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
                closing = this.snapshotEndpoints();
                startClose = true;
            }
        } finally {
            this.lifecycleLock.unlock();
        }

        if (startClose) {
            this.startClose(closing);
        }
        if (Thread.currentThread() != this.closeThread && !SocketRuntime.isCurrentThreadInTransport()) {
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
            for (ConnectorEndpoint<?> endpoint : closing) {
                try {
                    endpoint.closeListener();
                } catch (RuntimeException | Error failure) {
                    logger.warn("Connector listener close failed", failure);
                }
            }

            for (ConnectorEndpoint<?> endpoint : closing) {
                try {
                    endpoint.beginClose();
                } catch (RuntimeException | Error failure) {
                    logger.warn("Connector close preparation failed", failure);
                }
            }

            // Drain every logical channel before asking physical connections to finish.
            List<Future<?>> drainingChannels = new ArrayList<>();
            for (ConnectorEndpoint<?> endpoint : closing) {
                try {
                    drainingChannels.addAll(endpoint.drainChannels());
                } catch (RuntimeException | Error failure) {
                    logger.warn("Connector channel drain failed", failure);
                }
            }
            this.awaitCloseFutures(drainingChannels, "Connector channel drain failed");

            // Physical drains include transport writes which do not belong to one protocol session.
            List<Future<?>> drainingConnections = new ArrayList<>();
            for (ConnectorEndpoint<?> endpoint : closing) {
                try {
                    drainingConnections.addAll(endpoint.drainConnections());
                } catch (RuntimeException | Error failure) {
                    logger.warn("Connector connection drain failed", failure);
                }
            }
            this.awaitCloseFutures(drainingConnections, "Connector connection drain failed");
        } finally {
            for (ConnectorEndpoint<?> endpoint : closing) {
                try {
                    endpoint.closeTransport();
                } catch (RuntimeException | Error failure) {
                    logger.warn("Connector transport close failed", failure);
                }
            }

            HashedWheelTimer stopping;
            RouteServiceRegistry stoppingServiceRoutes;
            synchronized (this) {
                this.endpoints.clear();
                stoppingServiceRoutes = this.serviceRouteRegistry;
                this.serviceRouteRegistry = null;
                stopping = this.timer;
                this.timer = null;
            }

            if (stoppingServiceRoutes != null) {
                stoppingServiceRoutes.clear();
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
            RouteServiceRegistry activeServiceRoutes;
            synchronized (this) {
                this.requireReady();
                activeServiceRoutes = this.serviceRouteRegistry;
            }

            if (eventType == MetContextEventType.REGISTERED) {
                activeServiceRoutes.registerService(event.getServiceInfo());
            } else {
                activeServiceRoutes.removeService(event.getServiceInfo());
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
            ResolvedMount target;
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
            ResolvedMount target;
            synchronized (this) {
                this.requireReady();
                ConnectorEndpoint<?> endpoint = this.endpoints.get(endpointName);
                if (endpoint == null) {
                    throw undefined("Unknown connector endpoint: " + endpointName);
                }

                ConnectorConfig endpointConfig = endpoint.config();
                List<ResolvedMount> available = new ArrayList<>();
                for (ProtocolConfig mount : endpointConfig.protocols()) {
                    available.add(new ResolvedMount(endpointConfig, mount));
                }

                List<ResolvedMount> selected = this.selectForConnect(available, selector, address.getSchema());
                ResolvedMount resolved = this.requireSingleMount(selected, address.getSchema(), selector);
                ProtocolConfig mount = resolved.mount();
                target = new ResolvedMount(endpointConfig, mount);
            }
            return this.connect(target, address);
        } catch (Exception failure) {
            return failed(failure);
        }
    }

    private Future<MetChannel> connect(ResolvedMount target, InterAddress address) throws Exception {
        this.lifecycleLock.lock();
        try {
            ConnectorEndpoint<?> endpoint;
            synchronized (this) {
                this.requireReady();
                endpoint = this.endpoints.get(target.endpoint().name());
                if (endpoint == null) {
                    throw undefined("Unknown connector endpoint: " + target.endpoint().name());
                }
            }

            // Starting the transport connection and stopping admission are one lifecycle decision.
            endpoint.init();
            return endpoint.connect(target.mount().mountId(), address);
        } finally {
            this.lifecycleLock.unlock();
        }
    }

    private ResolvedMount resolve(String scheme, String protocol) {
        if (protocol == null) {
            return this.requireSingleMount(this.findConfiguredMountsByScheme(scheme), scheme, null);
        }

        List<ResolvedMount> candidates = this.selectForConnect(this.configuredMounts(), protocol, scheme);
        return this.requireSingleMount(candidates, scheme, protocol);
    }

    private List<ResolvedMount> selectForConnect(//
            Collection<ResolvedMount> candidates, String selector, String scheme) {
        List<ResolvedMount> mounted = this.selectMounted(candidates, selector);
        if (!mounted.isEmpty()) {
            mounted.removeIf(candidate -> !StringUtils.equalsIgnoreCase(candidate.mount().scheme(), scheme));
            return mounted;
        }

        boolean logicalName = this.isLogicalSelector(selector);
        List<ResolvedMount> selected = new ArrayList<>();
        for (ResolvedMount candidate : candidates) {
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

    private List<ResolvedMount> configuredMounts() {
        List<ResolvedMount> candidates = new ArrayList<>();
        for (ConnectorEndpoint<?> endpoint : this.endpoints.values()) {
            ConnectorConfig endpointConfig = endpoint.config();
            for (ProtocolConfig mount : endpointConfig.protocols()) {
                candidates.add(new ResolvedMount(endpointConfig, mount));
            }
        }
        return candidates;
    }

    private List<ResolvedMount> findConfiguredMountsByScheme(String scheme) {
        List<ResolvedMount> candidates = new ArrayList<>();
        for (ConnectorEndpoint<?> endpoint : this.endpoints.values()) {
            ConnectorConfig endpointConfig = endpoint.config();
            for (ProtocolConfig mount : endpointConfig.protocols()) {
                if (StringUtils.equalsIgnoreCase(mount.scheme(), scheme)) {
                    candidates.add(new ResolvedMount(endpointConfig, mount));
                }
            }
        }
        return candidates;
    }

    private List<ResolvedMount> selectMounts(Collection<ResolvedMount> candidates, String selector) {
        List<ResolvedMount> mounted = this.selectMounted(candidates, selector);
        if (!mounted.isEmpty()) {
            return mounted;
        }

        boolean logicalName = this.isLogicalSelector(selector);
        List<ResolvedMount> selected = new ArrayList<>();
        for (ResolvedMount candidate : candidates) {
            ProtocolConfig mount = candidate.mount();
            String value = logicalName ? mount.name() : mount.scheme();
            if (StringUtils.equalsIgnoreCase(value, selector)) {
                selected.add(candidate);
            }
        }
        return selected;
    }

    private List<ResolvedMount> selectMounted(Collection<ResolvedMount> candidates, String selector) {
        List<ResolvedMount> mounted = new ArrayList<>();
        for (ResolvedMount candidate : candidates) {
            if (StringUtils.equalsIgnoreCase(candidate.mount().mountId(), selector)) {
                mounted.add(candidate);
            }
        }
        return mounted;
    }

    private boolean isLogicalSelector(String selector) {
        for (ResolvedMount candidate : this.configuredMounts()) {
            if (StringUtils.equalsIgnoreCase(candidate.mount().name(), selector)) {
                return true;
            }
        }
        return false;
    }

    private ResolvedMount requireSingleMount(List<ResolvedMount> candidates, String scheme, String protocol) {
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

    private static final class ResolvedMount {
        private final ConnectorConfig endpoint;
        private final ProtocolConfig  mount;

        private ResolvedMount(ConnectorConfig endpoint, ProtocolConfig mount) {
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

    private synchronized List<ConnectorEndpoint<?>> snapshotEndpoints() {
        return new ArrayList<>(this.endpoints.values());
    }

    private List<ConnectorEndpoint<?>> bindEndpoints() {
        List<ConnectorEndpoint<?>> endpoints = new ArrayList<>();
        for (ConnectorEndpoint<?> endpoint : this.endpoints.values()) {
            if (endpoint.config().bindEnabled()) {
                endpoints.add(endpoint);
            }
        }
        return endpoints;
    }

    private Throwable stopAllInbound(Throwable failure) {
        Throwable result = failure;
        for (ConnectorEndpoint<?> endpoint : this.snapshotEndpoints()) {
            try {
                endpoint.stopInbound();
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

    /** Returns the unique active listener address for one logical protocol. */
    public synchronized InterAddress getListenAddress(String protocol) {
        List<InterAddress> addresses = this.getListenAddresses(protocol);
        if (addresses.isEmpty()) {
            return null;
        }
        if (addresses.size() > 1) {
            throw new IllegalArgumentException("Multiple listener addresses match protocol: " + protocol);
        }
        return addresses.get(0);
    }

    /** Returns all active listener addresses for one logical protocol. */
    public synchronized List<InterAddress> getListenAddresses(String protocol) {
        Objects.requireNonNull(protocol, "protocol");
        List<ResolvedMount> targets = this.findBoundMountsByProtocol(protocol);
        Map<String, InterAddress> addresses = new LinkedHashMap<>();
        for (ResolvedMount target : targets) {
            InterAddress address = this.getListenAddressByMount(target.endpoint().name(), target.mount().mountId());
            if (address != null) {
                String key = target.endpoint().name() + "\u0000" + target.mount().scheme();
                addresses.putIfAbsent(key, address);
            }
        }
        return List.copyOf(addresses.values());
    }

    /** Returns the unique active listener address for a logical protocol and its public address scheme. */
    public synchronized InterAddress getListenAddress(String protocol, String addressScheme) {
        Objects.requireNonNull(addressScheme, "addressScheme");
        List<InterAddress> addresses = new ArrayList<>();
        for (InterAddress address : this.getListenAddresses(protocol)) {
            if (StringUtils.equalsIgnoreCase(address.getSchema(), addressScheme)) {
                addresses.add(address);
            }
        }
        if (addresses.isEmpty()) {
            return null;
        }
        if (addresses.size() > 1) {
            String message = "Multiple listener addresses match protocol " + protocol + " and scheme " + addressScheme;
            throw new IllegalArgumentException(message);
        }
        return addresses.get(0);
    }

    /** Returns an active listener selected by its configured connector, logical protocol and public scheme. */
    public synchronized InterAddress getListenAddress(//
            String connectorName, String protocol, String addressScheme) {
        Objects.requireNonNull(connectorName, "connectorName");
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(addressScheme, "addressScheme");
        ConnectorEndpoint<?> endpoint = this.endpoints.get(connectorName);
        InterAddress actual = endpoint == null ? null : endpoint.getBindAddress();
        if (actual == null) {
            return null;
        }
        for (ProtocolConfig mount : endpoint.config().protocols()) {
            boolean protocolMatched = StringUtils.equalsIgnoreCase(mount.name(), protocol);
            boolean schemeMatched = StringUtils.equalsIgnoreCase(mount.scheme(), addressScheme);
            if (protocolMatched && schemeMatched) {
                return new InterAddress(mount.scheme(), actual.getHost(), actual.getPort(), actual.getFormUnit());
            }
        }
        return null;
    }

    /** Internal exact lookup used after protocol routing has already selected a mount. */
    private InterAddress getListenAddressByMount(String endpointName, String mountId) {
        ConnectorEndpoint<?> endpoint = this.endpoints.get(endpointName);
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

    private List<ResolvedMount> findBoundMountsByProtocol(String protocol) {
        List<ResolvedMount> available = new ArrayList<>();
        for (ConnectorEndpoint<?> endpoint : this.endpoints.values()) {
            if (endpoint.getBindAddress() == null) {
                continue;
            }
            for (ProtocolConfig mount : endpoint.config().protocols()) {
                if (StringUtils.equalsIgnoreCase(mount.name(), protocol)) {
                    available.add(new ResolvedMount(endpoint.config(), mount));
                }
            }
        }
        return available;
    }

    public synchronized Set<String> protocols() {
        Set<String> protocols = new LinkedHashSet<>();
        for (ConnectorEndpoint<?> endpoint : this.endpoints.values()) {
            for (ProtocolConfig protocol : endpoint.config().protocols()) {
                protocols.add(protocol.name());
            }
        }

        return Collections.unmodifiableSet(protocols);
    }

    public boolean isInitialized() {
        return this.state != State.STOPPED && this.state != State.CLOSED;
    }

    /** IDs are unique within this manager and are never reset during its lifetime. */
    long nextChannelId() {
        return this.channelIds.incrementAndGet();
    }

    public MetContext context() {
        return this.context;
    }

    TransportRuntimeServices transportRuntimeServices() {
        return this.transportRuntimeServices;
    }

    public synchronized Cancellable schedule(Runnable task, long delayMillis) {
        if (this.timer == null) {
            throw new RejectedExecutionException("Connector manager is not initialized or is closed");
        }
        Timeout timeout = this.timer.newTimeout(ignored -> task.run(), delayMillis, TimeUnit.MILLISECONDS);
        return timeout::cancel;
    }
}

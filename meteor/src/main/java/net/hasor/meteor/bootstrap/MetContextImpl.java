/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.bootstrap;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;
import net.hasor.meteor.*;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorManager;
import net.hasor.meteor.connector.MetChannel;
import net.hasor.meteor.container.MetContainer;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.rpc.MetCaller;
import net.hasor.meteor.rpc.MetClientImpl;
import net.hasor.meteor.serialize.SerializeCoder;
import net.hasor.meteor.serialize.SerializeFactory;

/** Composes RSF components and owns their lifecycle without requiring a container or subclass. */
public final class MetContextImpl implements MetContext {
    private static final Logger                   LOGGER          = LoggerFactory.getLogger(MetContextImpl.class);
    private final        List<MetContextListener> listeners       = new CopyOnWriteArrayList<>();
    private final        MetSettings              settings;
    private final        ClassLoader              classLoader;
    private final        String                   instanceID      = UUID.randomUUID().toString().replace("-", "");
    private final        SerializeFactory         serializeFactory;
    private final        CacheAddressPool         addresses;
    private final        MetContainer             rsfContainer;
    private final        MetPublisher             publisher;
    private              ConnectorManager         connectors;
    private              MetCaller                rsfCaller;
    private final        List<MetExtension>       extensions      = new CopyOnWriteArrayList<>();
    private final        ReentrantLock            lifecycleLock   = new ReentrantLock();
    private final        Queue<LifecycleDelivery> lifecycleEvents = new ArrayDeque<>();
    private              Thread                   lifecycleEventThread;
    private volatile     State                    state           = State.NEW;

    private enum State {
        NEW,
        STARTING,
        OFFLINE,
        ONLINE,
        CLOSED
    }

    private final class ContextConnectorManager extends ConnectorManager {
        private ContextConnectorManager() {
            super(MetContextImpl.this);
        }

        private void onServiceEvent(MetContextEvent event) {
            this.serviceEvent(event);
        }

        @Override
        public Future<MetChannel> connect(InterAddress address) {
            Future<MetChannel> connecting = super.connect(address);
            connecting.onFailed(failure -> MetContextImpl.this.addresses.invalidAddress(address));
            return connecting;
        }
    }

    private static final class LifecycleDelivery {
        private final MetContextEvent event;
        private       boolean         delivered;
        private       Throwable       cleanupFailure;

        private LifecycleDelivery(MetContextEvent event) {
            this.event = event;
        }
    }

    /** 组装配置、服务容器、序列化器和地址管理组件。 */
    MetContextImpl(MetSettings settings, ClassLoader classLoader) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");

        // 组装服务注册、序列化和地址管理能力。
        this.serializeFactory = this.createSerializeFactory();
        this.addresses = new CacheAddressPool(this.settings);
        this.rsfContainer = new MetContainer(this.addresses, this.settings);
        this.publisher = new Publisher(this.rsfContainer.createPublisher());
    }

    /** 构建入口在返回上下文前完成初始化；监听仍由 start() 开启。 */
    void init(List<MetExtensionFactory> extensionFactory) {
        this.installFilters();

        // Context 持有连接管理器，连接失败时同步反馈给地址池。
        ContextConnectorManager connectorManager = new ContextConnectorManager();
        this.connectors = connectorManager;

        try {
            this.connectors.init();

            // 服务路由同步校验必须先于普通事件通知，异常由容器负责回滚服务登记。
            this.rsfContainer.subscribe(connectorManager::onServiceEvent);
            this.rsfContainer.subscribe(this::notifyEvent);
            this.rsfCaller = new MetCaller(this.connectors, this.rsfContainer::getFilterProviders);
        } catch (RuntimeException | Error failure) {
            try {
                this.connectors.close();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }

        // 核心组件就绪后再创建扩展，扩展失败时回收已完成初始化的组件。
        try {
            for (MetExtensionFactory factory : extensionFactory) {
                MetExtension extension = factory.create(this);
                this.extensions.add(Objects.requireNonNull(extension, "extension"));
            }
        } catch (Exception | Error failure) {
            try {
                this.close();
            } catch (Throwable cleanup) {
                failure.addSuppressed(cleanup);
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Cannot create Meteor extension", failure);
        }
    }

    @Override
    public void start() {
        Throwable startupFailure = null;
        LifecycleDelivery lifecycleEvent = null;
        this.lifecycleLock.lock();
        try {
            if (this.state == State.CLOSED) {
                throw new IllegalStateException("Meteor context is closed");
            }
            if (this.state != State.NEW) {
                return;
            }
            this.state = State.STARTING;

            try {
                // 扩展先准备治理配置；此时网络尚未接受请求。
                for (MetExtension extension : this.extensions) {
                    extension.beforeStart();
                }

                this.addresses.start(this.connectors::schedule);
                if (this.settings.isAutomaticOnline()) {
                    this.connectors.startInbound();
                    this.state = State.ONLINE;
                } else {
                    this.connectors.stopInbound();
                    this.state = State.OFFLINE;
                }

                // 监听成功后，扩展才能发布实际地址。
                for (MetExtension extension : this.extensions) {
                    extension.afterStart();
                }
                lifecycleEvent = this.enqueueLifecycleEvent(MetContextEventType.STARTED);
            } catch (Exception | Error failure) {
                synchronized (this.listeners) {
                    this.state = State.CLOSED;
                }
                lifecycleEvent = this.enqueueLifecycleEvent(MetContextEventType.CLOSED);
                startupFailure = failure;
            }
        } finally {
            this.lifecycleLock.unlock();
        }

        // 事件和失败回收不占用生命周期锁，订阅者可以调用其他生命周期 API。
        this.dispatchLifecycleEvent(lifecycleEvent);
        if (startupFailure != null) {
            Throwable cause = startupFailure;
            if (startupFailure instanceof ExecutionException) {
                cause = startupFailure.getCause();
            }

            Throwable cleanup = this.getCleanupFailure(lifecycleEvent);
            if (cleanup != null) {
                cause.addSuppressed(cleanup);
            }
            if (startupFailure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Cannot start Meteor context", cause);
        }
    }

    @Override
    public void close() {
        LifecycleDelivery lifecycleEvent;
        this.lifecycleLock.lock();
        try {
            if (this.state == State.CLOSED) {
                return;
            }
            synchronized (this.listeners) {
                this.state = State.CLOSED;
            }
            lifecycleEvent = this.enqueueLifecycleEvent(MetContextEventType.CLOSED);
        } finally {
            this.lifecycleLock.unlock();
        }
        this.dispatchLifecycleEvent(lifecycleEvent);
        this.throwCleanupFailure(lifecycleEvent);
    }

    private void closeResources() {
        // 停止后台任务和网络排空可能等待其他线程，不能同时占用生命周期锁。
        List<AutoCloseable> resources = new ArrayList<>(this.extensions);
        resources.add(this.addresses);
        // Context 创建并持有连接管理器和调用器；先排空网络，再释放 RPC 自有资源。
        resources.add(this.connectors);
        resources.add(this.rsfCaller);
        Throwable failure = null;
        for (AutoCloseable resource : resources) {
            try {
                resource.close();
            } catch (Throwable cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else if (failure != cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
        }

        this.extensions.clear();
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new IllegalStateException("Cannot close Meteor context", failure);
        }
    }

    @Override
    public void online() {
        Throwable openingFailure = null;
        LifecycleDelivery lifecycleEvent = null;
        this.lifecycleLock.lock();
        try {
            if (this.state == State.NEW || this.state == State.STARTING || this.state == State.CLOSED) {
                throw new IllegalStateException("Meteor context is not started");
            }
            if (this.state == State.ONLINE) {
                return;
            }

            try {
                this.connectors.startInbound();
                this.state = State.ONLINE;
                lifecycleEvent = this.enqueueLifecycleEvent(MetContextEventType.ONLINE);
            } catch (Exception | Error failure) {
                this.state = State.OFFLINE;
                openingFailure = failure;
            }
        } finally {
            this.lifecycleLock.unlock();
        }

        this.dispatchLifecycleEvent(lifecycleEvent);
        if (openingFailure != null) {
            Throwable cause = openingFailure;
            if (openingFailure instanceof ExecutionException) {
                cause = openingFailure.getCause();
            }
            if (openingFailure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Cannot bring Meteor context online", cause);
        }
    }

    @Override
    public void offline() {
        Throwable stoppingFailure = null;
        LifecycleDelivery lifecycleEvent = null;
        boolean changed = false;
        this.lifecycleLock.lock();
        try {
            if (this.state != State.ONLINE && this.state != State.OFFLINE) {
                return;
            }
            changed = this.state == State.ONLINE;

            // 先停止监听并让已接入连接进入排空关闭，再公布离线状态。
            try {
                this.connectors.stopInbound();
            } catch (RuntimeException | Error failure) {
                stoppingFailure = failure;
            }
            this.state = State.OFFLINE;
            if (changed) {
                lifecycleEvent = this.enqueueLifecycleEvent(MetContextEventType.OFFLINE);
            }
        } finally {
            this.lifecycleLock.unlock();
        }

        this.dispatchLifecycleEvent(lifecycleEvent);
        if (stoppingFailure instanceof Error error) {
            throw error;
        }
        if (stoppingFailure != null) {
            throw (RuntimeException) stoppingFailure;
        }
    }

    @Override
    public Cancellable subscribe(MetContextListener listener) {
        Objects.requireNonNull(listener, "listener");
        MetContextListener subscription = listener::onEvent;
        synchronized (this.listeners) {
            if (this.state == State.CLOSED) {
                throw new IllegalStateException("Meteor context is closed");
            }
            this.listeners.add(subscription);
        }
        return () -> this.listeners.remove(subscription);
    }

    private void notifyEvent(MetContextEvent event) {
        for (MetContextListener listener : this.listeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException failure) {
                LOGGER.warn("Meteor context event callback failed: " + event.getType(), failure);
            }
        }
    }

    private LifecycleDelivery enqueueLifecycleEvent(MetContextEventType type) {
        LifecycleDelivery delivery = new LifecycleDelivery(new MetContextEvent(type, null));
        synchronized (this.lifecycleEvents) {
            this.lifecycleEvents.add(delivery);
        }
        return delivery;
    }

    private Throwable getCleanupFailure(LifecycleDelivery delivery) {
        synchronized (this.lifecycleEvents) {
            return delivery.cleanupFailure;
        }
    }

    private void throwCleanupFailure(LifecycleDelivery delivery) {
        Throwable failure = this.getCleanupFailure(delivery);
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure != null) {
            throw new IllegalStateException("Cannot close Meteor context", failure);
        }
    }

    private void dispatchLifecycleEvent(LifecycleDelivery requested) {
        if (requested == null) {
            return;
        }

        Thread currentThread = Thread.currentThread();
        boolean interrupted = false;
        try {
            while (true) {
                boolean deliverEvents = false;
                synchronized (this.lifecycleEvents) {
                    if (requested.delivered) {
                        return;
                    }
                    // 监听器中的生命周期重入只负责入队，由当前投递循环在本事件结束后继续处理。
                    if (this.lifecycleEventThread == currentThread) {
                        return;
                    }
                    if (this.lifecycleEventThread == null) {
                        this.lifecycleEventThread = currentThread;
                        deliverEvents = true;
                    } else {
                        try {
                            this.lifecycleEvents.wait();
                        } catch (InterruptedException e) {
                            interrupted = true;
                        }
                    }
                }

                if (deliverEvents) {
                    this.deliverLifecycleEvents();
                }
            }
        } finally {
            if (interrupted) {
                currentThread.interrupt();
            }
        }
    }

    private void deliverLifecycleEvents() {
        Thread currentThread = Thread.currentThread();
        try {
            while (true) {
                LifecycleDelivery delivery;
                synchronized (this.lifecycleEvents) {
                    delivery = this.lifecycleEvents.poll();
                    if (delivery == null) {
                        return;
                    }
                }

                try {
                    if (delivery.event.getType() == MetContextEventType.CLOSED) {
                        this.rsfCaller.stopAdmission();
                    }
                    this.notifyEvent(delivery.event);
                } finally {
                    if (delivery.event.getType() == MetContextEventType.CLOSED) {
                        this.listeners.clear();
                        try {
                            this.closeResources();
                        } catch (Throwable failure) {
                            delivery.cleanupFailure = failure;
                        }
                    }
                    synchronized (this.lifecycleEvents) {
                        delivery.delivered = true;
                        this.lifecycleEvents.notifyAll();
                    }
                }
            }
        } finally {
            synchronized (this.lifecycleEvents) {
                if (this.lifecycleEventThread == currentThread) {
                    this.lifecycleEventThread = null;
                }
                this.lifecycleEvents.notifyAll();
            }
        }
    }

    @Override
    public <T> T getExtension(Class<T> type) {
        T result = null;
        for (MetExtension extension : this.extensions) {
            T service;
            if (type.isInstance(extension)) {
                service = type.cast(extension);
            } else {
                service = extension.getService(type);
            }

            if (service != null) {
                if (result != null) {
                    throw new IllegalStateException("Multiple extensions provide " + type.getName());
                }
                result = service;
            }
        }
        return result;
    }

    @Override
    public boolean isOnline() {
        return this.state == State.ONLINE;
    }

    @Override
    public String getApplicationName() {
        return this.settings.getApplicationName();
    }

    @Override
    public String getInstanceID() {
        return this.instanceID;
    }

    /**获取运行着哪些协议*/
    @Override
    public Set<String> runProtocols() {
        return this.connectors.protocols();
    }

    @Override
    public String getDefaultProtocol() {
        return this.settings.getDefaultProtocol();
    }

    @Override
    public SerializeCoder getSerializeCoder(String name) {
        return this.serializeFactory.getSerializeCoder(name);
    }

    @Override
    public MetSettings getSettings() {
        return this.settings;
    }

    @Override
    public MetUpdater getUpdater() {
        return this.addresses;
    }

    @Override
    public ClassLoader getClassLoader() {
        return this.classLoader;
    }

    @Override
    public InterAddress getListenAddress(String protocol) {
        return this.connectors.getListenAddress(protocol);
    }

    @Override
    public List<InterAddress> getListenAddresses(String protocol) {
        return this.connectors.getListenAddresses(protocol);
    }

    @Override
    public InterAddress getListenAddress(String protocol, String addressScheme) {
        return this.connectors.getListenAddress(protocol, addressScheme);
    }

    @Override
    public InterAddress getListenAddress(String connectorName, String protocol, String addressScheme) {
        return this.connectors.getListenAddress(connectorName, protocol, addressScheme);
    }

    @Override
    public MetClient getRsfClient() {
        return new MetClientImpl(this.rsfCaller, this.addresses.getProvider());
    }

    @Override
    public MetClient getRsfClient(String targetStr) throws URISyntaxException, UnknownHostException {
        return this.getRsfClient(new InterAddress(targetStr));
    }

    @Override
    public MetClient getRsfClient(URI targetURL) throws UnknownHostException {
        return this.getRsfClient(new InterAddress(targetURL));
    }

    @Override
    public MetClient getRsfClient(InterAddress target) {
        return new MetClientImpl(this.rsfCaller, this.addresses.getProvider(target));
    }

    @Override
    public <T> MetBindInfo<T> getServiceInfo(String serviceID) {
        return (MetBindInfo<T>) this.rsfContainer.getRsfBindInfo(serviceID);
    }

    @Override
    public <T> MetBindInfo<T> getServiceInfo(String aliasType, String aliasName) {
        return (MetBindInfo<T>) this.rsfContainer.getRsfBindInfo(aliasType, aliasName);
    }

    @Override
    public <T> MetBindInfo<T> getServiceInfo(Class<T> serviceType) {
        return this.rsfContainer.getRsfBindInfo(serviceType);
    }

    @Override
    public <T> MetBindInfo<T> getServiceInfo(String group, String name, String version) {
        return (MetBindInfo<T>) this.rsfContainer.getRsfBindInfo(group, name, version);
    }

    @Override
    public List<String> getServiceIDs() {
        return this.rsfContainer.getServiceIDs();
    }

    @Override
    public List<String> getServiceIDs(String aliasType) {
        return this.rsfContainer.getServiceIDs(aliasType);
    }

    @Override
    public <T> Supplier<T> getServiceProvider(MetBindInfo<T> bindInfo) {
        return this.rsfContainer.getProvider(bindInfo);
    }

    @Override
    public MetPublisher publisher() {
        return this.publisher;
    }

    private SerializeFactory createSerializeFactory() {
        try {
            return SerializeFactory.createFactory(this.classLoader);
        } catch (RuntimeException e) {
            throw new MetException(ProtocolStatus.SerializeError, e);
        }
    }

    private void installFilters() {
        this.publisher.bindFilter("Hprose_AliasNameFilter", (request, response, chain) -> {
            response.addOption("Location", request.getOption("Location"));
            response.addOption("Origin", request.getOption("Origin"));
            chain.doFilter(request, response);
        });
    }

    private static final class Publisher implements MetPublisher {
        private final MetPublisher publisher;

        private Publisher(MetPublisher publisher) {
            this.publisher = publisher;
        }

        @Override
        public MetSettings getSettings() {
            return this.publisher.getSettings();
        }

        @Override
        public MetPublisher bindFilter(String filterID, MetFilter instance) {
            this.publisher.bindFilter(filterID, instance);
            return this;
        }

        @Override
        public MetPublisher bindFilter(String filterID, Supplier<? extends MetFilter> provider) {
            this.publisher.bindFilter(filterID, provider);
            return this;
        }

        @Override
        public MetPublisher bindFilter(String filterID, Class<? extends MetFilter> filterType) {
            this.publisher.bindFilter(filterID, filterType);
            return this;
        }

        @Override
        public <T> LinkedBuilder<T> rsfService(Class<T> type) {
            LinkedBuilder<T> builder = this.publisher.rsfService(type);
            builder.aliasName("Hprose", StringUtils.firstCharToLowerCase(type.getSimpleName()));
            return builder;
        }

        @Override
        public <T> ConfigurationBuilder<T> rsfService(Class<T> type, T instance) {
            return this.rsfService(type).toInstance(instance);
        }

        @Override
        public <T> ConfigurationBuilder<T> rsfService(Class<T> type, Class<? extends T> implementation) {
            return this.rsfService(type).to(implementation);
        }

        @Override
        public <T> ConfigurationBuilder<T> rsfService(Class<T> type, Supplier<T> provider) {
            return this.rsfService(type).toProvider(provider);
        }
    }
}

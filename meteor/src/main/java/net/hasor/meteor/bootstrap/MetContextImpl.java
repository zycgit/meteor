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
import net.hasor.meteor.connector.*;
import net.hasor.meteor.connector.protocol.EndpointConnectorFactory;
import net.hasor.meteor.container.MetContainer;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.rpc.MetCaller;
import net.hasor.meteor.rpc.MetClientImpl;
import net.hasor.meteor.rpc.filters.local.LocalPref;
import net.hasor.meteor.rpc.filters.online.OnlineMetFilter;
import net.hasor.meteor.rpc.filters.thread.LocalWarpFilter;
import net.hasor.meteor.serialize.SerializeCoder;
import net.hasor.meteor.serialize.SerializeFactory;

/** Composes RSF components and owns their lifecycle without requiring a container or subclass. */
public final class MetContextImpl implements MetContext {
    private static final Logger                   LOGGER        = LoggerFactory.getLogger(MetContextImpl.class);
    private final        List<MetContextListener> listeners     = new CopyOnWriteArrayList<>();
    private final        MetSettings              settings;
    private final        ClassLoader              classLoader;
    private final        String                   instanceID    = UUID.randomUUID().toString().replace("-", "");
    private final        SerializeFactory         serializeFactory;
    private final        CacheAddressPool         addresses;
    private final        MetContainer             rsfContainer;
    private final        MetPublisher             publisher;
    private              ConnectorManager         connectors;
    private              MetCaller                rsfCaller;
    private final        List<MetExtension>       extensions    = new CopyOnWriteArrayList<>();
    private final        ReentrantLock            lifecycleLock = new ReentrantLock();
    private volatile     State                    state         = State.NEW;

    private enum State {
        NEW,
        STARTING,
        OFFLINE,
        ONLINE,
        CLOSED
    }

    /** 组装配置、服务容器、序列化器和地址管理组件。 */
    MetContextImpl(MetSettings settings, ClassLoader classLoader) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");

        // 组装服务注册、序列化和地址管理能力。
        this.serializeFactory = this.createSerializeFactory();
        this.addresses = new CacheAddressPool(this.settings);
        this.rsfContainer = new MetContainer(this.addresses, this.settings);
        this.rsfContainer.subscribe(this::notifyEvent);
        this.publisher = new Publisher(this.rsfContainer.createPublisher());
    }

    /** 构建入口在返回上下文前完成初始化；监听仍由 start() 开启。 */
    void init(EndpointConnectorFactory connectorFactory, List<MetExtensionFactory> extensionFactory) {
        this.installFilters();

        // 连接事件交给 RPC 层，连接失败同时反馈给地址池。
        this.connectors = new ConnectorManager(this, connectorFactory) {
            @Override
            public Future<MetChannel> connect(InterAddress address) {
                Future<MetChannel> connecting = super.connect(address);
                connecting.onFailed(failure -> {
                    MetContextImpl.this.addresses.invalidAddress(address);
                });
                return connecting;
            }
        };

        try {
            this.connectors.init();
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
                for (ConnectorConfig config : this.connectors.configurations()) {
                    if (config.bindEnabled()) {
                        this.connectors.bind(config.name()).get();
                    }
                }

                // 监听成功后，扩展才能发布实际地址。
                this.state = this.settings.isAutomaticOnline() ? State.ONLINE : State.OFFLINE;
                for (MetExtension extension : this.extensions) {
                    extension.afterStart();
                }
            } catch (Exception | Error failure) {
                synchronized (this.listeners) {
                    this.state = State.CLOSED;
                }
                startupFailure = failure;
            }
        } finally {
            this.lifecycleLock.unlock();
        }

        // 事件和失败回收不占用生命周期锁，订阅者可以调用其他生命周期 API。
        if (startupFailure != null) {
            Throwable cause = startupFailure;
            if (startupFailure instanceof ExecutionException) {
                cause = startupFailure.getCause();
            }

            try {
                this.closeResources();
            } catch (Throwable cleanup) {
                cause.addSuppressed(cleanup);
            } finally {
                if (startupFailure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Cannot start Meteor context", cause);
        }
        this.notifyEvent(new MetContextEvent(MetContextEventType.STARTED, null));
    }

    @Override
    public void close() {
        this.lifecycleLock.lock();
        try {
            if (this.state == State.CLOSED) {
                return;
            }
            synchronized (this.listeners) {
                this.state = State.CLOSED;
            }
        } finally {
            this.lifecycleLock.unlock();
        }
        this.closeResources();
    }

    private void closeResources() {
        this.notifyEvent(new MetContextEvent(MetContextEventType.CLOSED, null));
        this.listeners.clear();

        // 停止后台任务和网络排空可能等待其他线程，不能同时占用生命周期锁。
        List<AutoCloseable> resources = new ArrayList<>(this.extensions);
        resources.add(this.addresses);
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
        this.lifecycleLock.lock();
        try {
            if (this.state == State.NEW || this.state == State.STARTING || this.state == State.CLOSED) {
                throw new IllegalStateException("Meteor context is not started");
            }
            if (this.state == State.ONLINE) {
                return;
            }
            this.state = State.ONLINE;
        } finally {
            this.lifecycleLock.unlock();
        }
        this.notifyEvent(new MetContextEvent(MetContextEventType.ONLINE, null));
    }

    @Override
    public void offline() {
        this.lifecycleLock.lock();
        try {
            if (this.state != State.ONLINE) {
                return;
            }
            this.state = State.OFFLINE;
        } finally {
            this.lifecycleLock.unlock();
        }
        this.notifyEvent(new MetContextEvent(MetContextEventType.OFFLINE, null));
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
    public InterAddress bindAddress(String protocol) {
        MetConnector connector = this.connectors.find(protocol);
        if (connector == null) {
            return null;
        }

        // Only return an address after its listener has become active.
        for (MetListen listen : connector.getListenList()) {
            if (listen.isActive()) {
                InterAddress actual = listen.getBindAddress();
                InterAddress configured = this.settings.getBindAddressSet(protocol);
                return new InterAddress(configured.getSchema(), actual.getHost(), actual.getPort(), actual.getFormUnit());
            }
        }
        return null;
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
        this.publisher.bindFilter("LocalPref", new LocalPref());
        this.publisher.bindFilter("LocalWarpFilter", new LocalWarpFilter());
        this.publisher.bindFilter("SecurityRsfFilter", new OnlineMetFilter());
        this.publisher.bindFilter("Hprose_AliasNameFilter", (request, response, chain) -> {
            response.addOption("Location", request.getOption("Location"));
            response.addOption("Origin", request.getOption("Origin"));
            chain.doFilter(request, response);
        });
    }

    private record Publisher(MetPublisher publisher) implements MetPublisher {

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

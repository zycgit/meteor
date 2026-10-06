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
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.*;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.connector.protocol.EndpointConnectorFactory;
import net.hasor.meteor.container.MetContainer;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.rpc.MetCaller;
import net.hasor.meteor.rpc.MetClientImpl;
import net.hasor.meteor.rpc.filters.local.LocalPref;
import net.hasor.meteor.rpc.filters.online.OnlineMetFilter;
import net.hasor.meteor.rpc.filters.thread.LocalWarpFilter;
import net.hasor.meteor.serialize.SerializeCoder;
import net.hasor.meteor.serialize.SerializeFactory;

/** Composes RSF components and owns their lifecycle without requiring a container or subclass. */
public final class MetContextImpl implements MetContext {
    private final    MetSettings      settings;
    private final    ClassLoader      classLoader;
    private final    String           instanceID = UUID.randomUUID().toString().replace("-", "");
    private final    SerializeFactory serializeFactory;
    private final    CacheAddressPool addresses;
    private final    MetContainer     rsfContainer;
    private final    MetPublisher     publisher;
    private final    ConnectorManager connectors;
    private final    MetCaller        rsfCaller;
    private volatile State            state      = State.NEW;

    private enum State {
        NEW,
        OFFLINE,
        ONLINE,
        CLOSED
    }

    /** Initializes components for registration and invocation; start() opens the configured listeners. */
    MetContextImpl(MetSettings settings, ClassLoader classLoader, EndpointConnectorFactory factory) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
        this.serializeFactory = this.createSerializeFactory();
        this.addresses = new CacheAddressPool(this.settings);
        this.rsfContainer = new MetContainer(this.addresses, this.settings);
        this.publisher = new Publisher(this.rsfContainer.createPublisher());
        this.installFilters();
        this.connectors = new ConnectorManager(this, factory) {
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
    }

    @Override
    public synchronized void start() {
        if (this.state == State.CLOSED) {
            throw new IllegalStateException("RSF context is closed");
        }
        if (this.state != State.NEW) {
            return;
        }

        try {
            this.addresses.start(this.connectors::schedule);
            for (ConnectorConfig config : this.connectors.configurations()) {
                if (config.bindEnabled()) {
                    this.connectors.bind(config.name()).get();
                }
            }

            this.state = this.settings.isAutomaticOnline() ? State.ONLINE : State.OFFLINE;
        } catch (Exception | Error failure) {
            Throwable cause = failure instanceof ExecutionException ? failure.getCause() : failure;
            try {
                this.close();
            } catch (Throwable cleanup) {
                cause.addSuppressed(cleanup);
            } finally {
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }

            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException("Cannot start RSF context", cause);
        }
    }

    @Override
    public synchronized void close() {
        if (this.state == State.CLOSED) {
            return;
        }

        this.state = State.CLOSED;
        try {
            // Cancel address maintenance before its shared scheduler is closed with the connectors.
            this.addresses.close();
        } finally {
            this.rsfCaller.close();
        }
    }

    @Override
    public synchronized void online() {
        if (this.state == State.NEW || this.state == State.CLOSED) {
            throw new IllegalStateException("RSF context is not started");
        }

        this.state = State.ONLINE;
    }

    @Override
    public synchronized void offline() {
        if (this.state == State.ONLINE) {
            this.state = State.OFFLINE;
        }
    }

    @Override
    public boolean isOnline() {
        return this.state == State.ONLINE;
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

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.bootstrap;
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
import net.hasor.rsf.*;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.connector.*;
import net.hasor.rsf.connector.protocol.EndpointConnectorFactory;
import net.hasor.rsf.container.RsfContainer;
import net.hasor.rsf.domain.ProtocolStatus;
import net.hasor.rsf.domain.RsfException;
import net.hasor.rsf.rpc.RsfCaller;
import net.hasor.rsf.rpc.RsfClientImpl;
import net.hasor.rsf.rpc.filters.local.LocalPref;
import net.hasor.rsf.rpc.filters.online.OnlineRsfFilter;
import net.hasor.rsf.rpc.filters.thread.LocalWarpFilter;
import net.hasor.rsf.serialize.SerializeCoder;
import net.hasor.rsf.serialize.SerializeFactory;

/** Composes RSF components and owns their lifecycle without requiring a container or subclass. */
public final class RsfContextImpl implements RsfContext {
    private final    RsfSettings      settings;
    private final    ClassLoader      classLoader;
    private final    String           instanceID = UUID.randomUUID().toString().replace("-", "");
    private final    SerializeFactory serializeFactory;
    private final    CacheAddressPool addresses;
    private final    RsfContainer     rsfContainer;
    private final    RsfPublisher     publisher;
    private final    ConnectorManager connectors;
    private final    RsfCaller        rsfCaller;
    private volatile State            state      = State.NEW;

    private enum State {
        NEW,
        OFFLINE,
        ONLINE,
        CLOSED
    }

    /** Initializes components for registration and invocation; start() opens the configured listeners. */
    RsfContextImpl(RsfSettings settings, ClassLoader classLoader, EndpointConnectorFactory factory) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
        this.serializeFactory = this.createSerializeFactory();
        this.addresses = new CacheAddressPool(this.settings);
        this.rsfContainer = new RsfContainer(this.addresses, this.settings);
        this.publisher = new Publisher(this.rsfContainer.createPublisher());
        this.installFilters();
        this.connectors = new ConnectorManager(this, factory) {
            @Override
            public Future<RsfChannel> connect(InterAddress address) {
                Future<RsfChannel> connecting = super.connect(address);
                connecting.onFailed(failure -> {
                    RsfContextImpl.this.addresses.invalidAddress(address);
                });
                return connecting;
            }
        };

        try {
            this.connectors.init();
            this.rsfCaller = new RsfCaller(this.connectors, this.rsfContainer::getFilterProviders);
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
    public RsfSettings getSettings() {
        return this.settings;
    }

    @Override
    public RsfUpdater getUpdater() {
        return this.addresses;
    }

    @Override
    public ClassLoader getClassLoader() {
        return this.classLoader;
    }

    @Override
    public InterAddress bindAddress(String protocol) {
        RsfConnector connector = this.connectors.find(protocol);
        if (connector == null) {
            return null;
        }

        // Only return an address after its listener has become active.
        for (RsfListen listen : connector.getListenList()) {
            if (listen.isActive()) {
                InterAddress actual = listen.getBindAddress();
                InterAddress configured = this.settings.getBindAddressSet(protocol);
                return new InterAddress(configured.getSchema(), actual.getHost(), actual.getPort(), actual.getFormUnit());
            }
        }
        return null;
    }

    @Override
    public RsfClient getRsfClient() {
        return new RsfClientImpl(this.rsfCaller, this.addresses.getProvider());
    }

    @Override
    public RsfClient getRsfClient(String targetStr) throws URISyntaxException, UnknownHostException {
        return this.getRsfClient(new InterAddress(targetStr));
    }

    @Override
    public RsfClient getRsfClient(URI targetURL) throws UnknownHostException {
        return this.getRsfClient(new InterAddress(targetURL));
    }

    @Override
    public RsfClient getRsfClient(InterAddress target) {
        return new RsfClientImpl(this.rsfCaller, this.addresses.getProvider(target));
    }

    @Override
    public <T> RsfBindInfo<T> getServiceInfo(String serviceID) {
        return (RsfBindInfo<T>) this.rsfContainer.getRsfBindInfo(serviceID);
    }

    @Override
    public <T> RsfBindInfo<T> getServiceInfo(String aliasType, String aliasName) {
        return (RsfBindInfo<T>) this.rsfContainer.getRsfBindInfo(aliasType, aliasName);
    }

    @Override
    public <T> RsfBindInfo<T> getServiceInfo(Class<T> serviceType) {
        return this.rsfContainer.getRsfBindInfo(serviceType);
    }

    @Override
    public <T> RsfBindInfo<T> getServiceInfo(String group, String name, String version) {
        return (RsfBindInfo<T>) this.rsfContainer.getRsfBindInfo(group, name, version);
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
    public <T> Supplier<T> getServiceProvider(RsfBindInfo<T> bindInfo) {
        return this.rsfContainer.getProvider(bindInfo);
    }

    @Override
    public RsfPublisher publisher() {
        return this.publisher;
    }

    private SerializeFactory createSerializeFactory() {
        try {
            return SerializeFactory.createFactory(this.classLoader);
        } catch (RuntimeException e) {
            throw new RsfException(ProtocolStatus.SerializeError, e);
        }
    }

    private void installFilters() {
        this.publisher.bindFilter("LocalPref", new LocalPref());
        this.publisher.bindFilter("LocalWarpFilter", new LocalWarpFilter());
        this.publisher.bindFilter("SecurityRsfFilter", new OnlineRsfFilter());
        this.publisher.bindFilter("Hprose_AliasNameFilter", (request, response, chain) -> {
            response.addOption("Location", request.getOption("Location"));
            response.addOption("Origin", request.getOption("Origin"));
            chain.doFilter(request, response);
        });
    }

    private static final class Publisher implements RsfPublisher {
        private final RsfPublisher publisher;

        private Publisher(RsfPublisher publisher) {
            this.publisher = publisher;
        }

        @Override
        public RsfSettings getSettings() {
            return this.publisher.getSettings();
        }

        @Override
        public RsfPublisher bindFilter(String filterID, RsfFilter instance) {
            this.publisher.bindFilter(filterID, instance);
            return this;
        }

        @Override
        public RsfPublisher bindFilter(String filterID, Supplier<? extends RsfFilter> provider) {
            this.publisher.bindFilter(filterID, provider);
            return this;
        }

        @Override
        public RsfPublisher bindFilter(String filterID, Class<? extends RsfFilter> filterType) {
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

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.dubbo;
import java.util.*;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.registry.dubbo.metadata.DubboMetadataCertProvider;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.url.component.ServiceConfigURL;
import org.apache.dubbo.config.ApplicationConfig;
import org.apache.dubbo.config.MetadataReportConfig;
import org.apache.dubbo.config.SslConfig;
import org.apache.dubbo.metadata.MetadataInfo;
import org.apache.dubbo.metadata.ServiceNameMapping;
import org.apache.dubbo.metadata.report.MetadataReportInstance;
import org.apache.dubbo.registry.*;
import org.apache.dubbo.registry.client.*;
import org.apache.dubbo.registry.client.metadata.ServiceInstanceMetadataUtils;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.apache.dubbo.rpc.model.FrameworkModel;
import static org.apache.dubbo.metadata.RevisionResolver.EMPTY_REVISION;

/** 独占 Dubbo 注册与元数据会话，业务调用始终使用 Meteor 协议通道。 */
final class DubboRegistrySession implements AutoCloseable {
    private final MetContext                                         context;
    private final URL                                                registryUrl;
    private final Set<URL>                                           providers = new LinkedHashSet<>();
    private final Map<NotifyListener, Map<String, NotifyListener>>   listeners = new LinkedHashMap<>();
    private final Map<NotifyListener, Map<String, Map<String, URL>>> snapshots = new LinkedHashMap<>();
    private       FrameworkModel                                     framework;
    private       ApplicationModel                                   application;
    private       Registry                                           interfaceRegistry;
    private       Registry                                           discoveryRegistry;

    DubboRegistrySession(MetContext context, URL registryUrl) {
        this.context = context;
        this.registryUrl = registryUrl;
    }

    void open() {
        // 独占模型避免注册中心配置、缓存与元数据代理污染其他 Meteor 实例或应用中的 Dubbo。
        this.framework = new FrameworkModel();
        this.application = this.framework.newApplication();
        ApplicationConfig applicationConfig = new ApplicationConfig(this.application);
        applicationConfig.setName(this.context.getApplicationName());
        applicationConfig.setQosEnable(false);
        applicationConfig.setEnableFileCache(false);
        applicationConfig.setMetadataType("remote");
        URL registryAddress = this.configureMetadataTls(applicationConfig);
        this.application.getApplicationConfigManager().setApplication(applicationConfig);

        // 元数据存于原生注册中心，Meteor 发布者不额外开放 Dubbo 元数据 RPC 端口。
        MetadataReportConfig metadataConfig = new MetadataReportConfig(this.application);
        metadataConfig.setAddress(registryAddress.toFullString());
        metadataConfig.setUsername(registryAddress.getUsername());
        metadataConfig.setPassword(registryAddress.getPassword());
        metadataConfig.setGroup(registryAddress.getParameter("group"));
        metadataConfig.setTimeout(registryAddress.getParameter("timeout", 5000));
        metadataConfig.setParameters(new LinkedHashMap<>(registryAddress.getParameters()));
        metadataConfig.setSyncReport(true);
        metadataConfig.setReportDefinition(false);
        metadataConfig.setReportMetadata(true);
        this.application.getApplicationConfigManager().addMetadataReport(metadataConfig);
        MetadataReportInstance metadata = this.application.getBeanFactory().getBean(MetadataReportInstance.class);
        metadata.init(Collections.singletonList(metadataConfig));

        // 接口目录兼容 Dubbo 2；应用目录兼容仅注册应用实例的 Dubbo 3。
        URL nativeUrl = registryAddress.setPath(RegistryService.class.getName()) //
                .addParameter("application", this.context.getApplicationName()) //
                .addParameter("enable-empty-protection", false);
        nativeUrl = nativeUrl.setScopeModel(this.application);
        RegistryFactory factory = this.application.getExtensionLoader(RegistryFactory.class).getAdaptiveExtension();
        this.interfaceRegistry = factory.getRegistry(nativeUrl);
        URL discoveryUrl = nativeUrl.setProtocol("service-discovery-registry") //
                .addParameter("registry", this.registryUrl.getProtocol());
        this.discoveryRegistry = factory.getRegistry(discoveryUrl);
    }

    private URL configureMetadataTls(ApplicationConfig configuration) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put(DubboMetadataCertProvider.APPLICATION_MARKER, "true");
        URL address = this.registryUrl;
        List<String> options = List.of(SslConfig.CLIENT_TRUST_CERT_COLLECTION_PATH, //
                SslConfig.CLIENT_KEY_CERT_CHAIN_PATH, //
                SslConfig.CLIENT_PRIVATE_KEY_PATH, //
                SslConfig.CLIENT_KEY_PASSWORD);
        for (String option : options) {
            String key = "metadata." + option;
            String value = address.getParameter(key);
            if (value != null) {
                parameters.put(key, value);
                address = address.removeParameter(key);
            }
        }
        configuration.setParameters(parameters);
        return address;
    }

    void register(URL provider) {
        URL scoped = provider.setScopeModel(this.application);
        this.interfaceRegistry.register(scoped);
        if (StringUtils.equals("consumer", scoped.getSide())) {
            return;
        }
        URL selected = this.selectedProvider(scoped.getProtocolServiceKey());
        if (selected != null) {
            this.providers.add(scoped);
            return;
        }
        try {
            this.discoveryRegistry.register(scoped);
            boolean mapped = ServiceNameMapping.getDefaultExtension(this.application).map(scoped);
            if (!mapped) {
                String message = "Cannot publish Dubbo service application mapping: " + provider.getServiceInterface();
                throw new IllegalStateException(message);
            }
            this.providers.add(scoped);
        } catch (RuntimeException failure) {
            // 未成功的首次登记不成为代表端点，下次协调仍能完整重试。
            try {
                this.discoveryRegistry.unregister(scoped);
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            try {
                this.interfaceRegistry.unregister(scoped);
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    void unregister(URL provider) {
        URL scoped = provider.setScopeModel(this.application);
        this.interfaceRegistry.unregister(scoped);
        if (StringUtils.equals("consumer", scoped.getSide())) {
            return;
        }
        URL selected = this.selectedProvider(scoped.getProtocolServiceKey());
        this.providers.remove(scoped);
        if (scoped.equals(selected)) {
            this.discoveryRegistry.unregister(scoped);
            URL remaining = this.selectedProvider(scoped.getProtocolServiceKey());
            if (remaining != null) {
                this.discoveryRegistry.register(remaining);
            }
        }
    }

    void updateInstance() {
        Registry registry = this.discoveryRegistry;
        while (registry instanceof ListenerRegistryWrapper) {
            registry = ((ListenerRegistryWrapper) registry).getRegistry();
        }
        ServiceDiscovery discovery = ((ServiceDiscoveryRegistry) registry).getServiceDiscovery();
        if (this.providers.isEmpty() && discovery.getLocalInstance() == null) {
            return;
        }
        try {
            // 重新监听后端口可能变化；先撤销旧实例，再依据当前代表端点刷新 SDK 的实例地址。
            ServiceInstance instance = discovery.getLocalInstance();
            if (!this.providers.isEmpty() && instance instanceof DefaultServiceInstance) {
                URL selected = this.providers.iterator().next();
                boolean hostChanged = !StringUtils.equals(instance.getHost(), selected.getHost());
                boolean portChanged = instance.getPort() != selected.getPort();
                if (hostChanged || portChanged) {
                    discovery.unregister();
                    DefaultServiceInstance local = (DefaultServiceInstance) instance;
                    local.setHost(selected.getHost());
                    local.setPort(selected.getPort());
                    local.setEndpoints(null);
                    ServiceInstanceMetadataUtils.customizeInstance(local, this.application);
                    this.resetInstanceRevision(discovery);
                }
            }

            // 空版本会注销实例；地址变化时 SDK 重新复制实例并登记，普通服务变更仍按版本更新。
            discovery.update();
        } catch (RuntimeException failure) {
            // SDK 可能已经替换本地实例后才写入失败，清除缓存使下一轮仍能实际登记。
            this.resetInstanceRevision(discovery);
            throw failure;
        }
    }

    private void resetInstanceRevision(ServiceDiscovery discovery) {
        ServiceInstance instance = discovery.getLocalInstance();
        if (instance != null) {
            String revisionKey = ServiceInstanceMetadataUtils.EXPORTED_SERVICES_REVISION_PROPERTY_NAME;
            instance.getMetadata().put(revisionKey, EMPTY_REVISION);
        }
        // 首次登记失败时 SDK 会清空实例，但已计算的 MetadataInfo 版本同样需要失效。
        discovery.getLocalMetadata().setRevision(null);
    }

    void subscribe(URL consumer, NotifyListener listener) {
        URL scoped = consumer.setScopeModel(this.application);
        Map<String, NotifyListener> sources = new LinkedHashMap<>();
        sources.put("interface", this.sourceListener(scoped, listener, "interface"));
        sources.put("instance", this.sourceListener(scoped, listener, "instance"));
        synchronized (this.snapshots) {
            this.listeners.put(listener, sources);
            this.snapshots.put(listener, new LinkedHashMap<>());
        }

        // 分别保留两种发现模型的完整快照，单侧空通知不会擦除另一侧提供者。
        try {
            this.interfaceRegistry.subscribe(scoped, sources.get("interface"));
            this.discoveryRegistry.subscribe(scoped, sources.get("instance"));
        } catch (RuntimeException failure) {
            try {
                this.unsubscribe(consumer, listener);
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    void unsubscribe(URL consumer, NotifyListener listener) {
        Map<String, NotifyListener> sources;
        synchronized (this.snapshots) {
            sources = this.listeners.remove(listener);
            this.snapshots.remove(listener);
        }
        if (sources != null) {
            URL scoped = consumer.setScopeModel(this.application);
            RuntimeException failure = null;
            try {
                this.interfaceRegistry.unsubscribe(scoped, sources.get("interface"));
            } catch (RuntimeException cleanup) {
                failure = cleanup;
            }
            try {
                this.discoveryRegistry.unsubscribe(scoped, sources.get("instance"));
            } catch (RuntimeException cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else {
                    failure.addSuppressed(cleanup);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    synchronized boolean isAvailable() {
        if (this.interfaceRegistry == null || this.discoveryRegistry == null) {
            return false;
        }
        return this.interfaceRegistry.isAvailable() && this.discoveryRegistry.isAvailable();
    }

    @Override
    public synchronized void close() {
        // 先取消本会话的订阅，停止 SDK 映射回调，再销毁发现连接与应用模型。
        Map<NotifyListener, Map<String, NotifyListener>> closing;
        synchronized (this.snapshots) {
            closing = new LinkedHashMap<>(this.listeners);
        }
        RuntimeException failure = null;
        for (var entry : closing.entrySet()) {
            URL consumer = entry.getValue().get("instance").getConsumerUrl();
            try {
                this.unsubscribe(consumer, entry.getKey());
            } catch (RuntimeException cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        synchronized (this.snapshots) {
            this.listeners.clear();
            this.snapshots.clear();
        }
        this.providers.clear();

        // 取消订阅失败也必须释放独占模型，关闭错误保留给调用方。
        if (this.framework != null) {
            try {
                this.framework.destroy();
            } catch (RuntimeException cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else {
                    failure.addSuppressed(cleanup);
                }
            } finally {
                this.framework = null;
                this.application = null;
                this.interfaceRegistry = null;
                this.discoveryRegistry = null;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private NotifyListener sourceListener(URL consumer, NotifyListener listener, String source) {
        return new NotifyListener() {
            @Override
            public void notify(List<URL> urls) {
                DubboRegistrySession.this.notifySource(consumer, listener, source, urls);
            }

            @Override
            public URL getConsumerUrl() {
                return consumer;
            }
        };
    }

    private URL selectedProvider(String serviceKey) {
        // Dubbo 应用元数据每个协议服务键只能保留一个地址，优先使用最早登记且仍存活的端点。
        for (URL provider : this.providers) {
            if (StringUtils.equals(serviceKey, provider.getProtocolServiceKey())) {
                return provider;
            }
        }
        return null;
    }

    private void notifySource(URL consumer, NotifyListener listener, String source, List<URL> urls) {
        Map<String, URL> current = new LinkedHashMap<>();
        for (URL url : urls) {
            if (StringUtils.equals("empty", url.getProtocol())) {
                continue;
            }
            for (URL provider : this.providerUrls(consumer, url)) {
                String key = provider.getProtocol() + "://" + provider.getAddress() + "/" + provider.getServiceKey();
                current.put(key, provider);
            }
        }

        // 串行通知合并结果，保证并发到达的应用与接口快照不会逆序覆盖。
        synchronized (this.snapshots) {
            Map<String, Map<String, URL>> snapshots = this.snapshots.get(listener);
            if (snapshots == null) {
                return;
            }
            snapshots.put(source, current);
            Map<String, URL> merged = new LinkedHashMap<>();
            for (Map<String, URL> snapshot : snapshots.values()) {
                merged.putAll(snapshot);
            }
            listener.notify(new ArrayList<>(merged.values()));
        }
    }

    private List<URL> providerUrls(URL consumer, URL url) {
        if (!(url instanceof InstanceAddressURL)) {
            return Collections.singletonList(url);
        }

        // SDK 实例 URL 的服务参数依赖线程中的 RpcContext，转换成独立 URL 后再交给 Meteor。
        InstanceAddressURL address = (InstanceAddressURL) url;
        ServiceInstance instance = address.getInstance();
        MetadataInfo metadata = address.getMetadataInfo();
        List<URL> providers = new ArrayList<>();
        for (MetadataInfo.ServiceInfo service : metadata.getServices().values()) {
            boolean interfaceMatches = StringUtils.equals(service.getName(), consumer.getServiceInterface());
            String providerGroup = StringUtils.defaultString(service.getGroup());
            String consumerGroup = StringUtils.defaultString(consumer.getGroup());
            boolean groupMatches = StringUtils.equals(providerGroup, consumerGroup);
            String providerVersion = StringUtils.defaultString(service.getVersion());
            String consumerVersion = StringUtils.defaultString(consumer.getVersion());
            boolean versionMatches = StringUtils.equals(providerVersion, consumerVersion);
            boolean protocolMatches = StringUtils.equals(service.getProtocol(), address.getProtocol());
            if (interfaceMatches && groupMatches && versionMatches && protocolMatches) {
                Map<String, String> parameters = new LinkedHashMap<>(instance.getAllParams());
                parameters.putAll(service.getParams());
                parameters.put("interface", service.getName());
                parameters.put("group", providerGroup);
                parameters.put("version", providerVersion);
                parameters.put("application", instance.getServiceName());
                int port = service.getPort();
                if (port <= 0) {
                    port = instance.getPort();
                }
                String host = instance.getHost();
                URL provider = new ServiceConfigURL(service.getProtocol(), host, port, service.getPath(), parameters);
                providers.add(provider);
            }
        }
        return providers;
    }
}

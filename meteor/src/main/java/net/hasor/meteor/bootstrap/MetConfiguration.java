/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.bootstrap;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import net.hasor.cobble.NetworkUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.*;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.protocol.EndpointConnectorFactory;
import net.hasor.meteor.domain.OptionInfo;
import net.hasor.meteor.utils.ContextPathUtils;

/** Configures RSF components and creates a context ready for service registration. */
public final class MetConfiguration implements MetSettings {
    // 服务默认值
    private       int                          defaultTimeout        = 6000;
    private       String                       defaultGroup          = "default";
    private       String                       defaultVersion        = "1.0.0";
    // 连接与协议
    private       String                       bindAddress           = "127.0.0.1";
    private       String                       defaultProtocol       = null;
    private final List<ConnectorSettings>      connectors            = new ArrayList<>();
    // 请求与响应
    private       int                          requestTimeout        = 0;
    private       int                          maximumRequest        = 200;
    private       SendLimitPolicy              sendLimitPolicy       = SendLimitPolicy.Reject;
    private       int                          connectTimeout        = 3000;
    private final OptionInfo                   requestOptions        = new OptionInfo();
    private final OptionInfo                   responseOptions       = new OptionInfo();
    // 处理调用
    private       int                          queueMaxSize          = 4096;
    private       int                          queueMinPoolSize      = 1;
    private       int                          queueMaxPoolSize      = 4;
    private       long                         queueKeepAliveTime    = 300L;
    // 地址缓存与路由
    private       String                       unitName              = "default";
    private       Path                         dataHome              = Paths.get("meteor-data");
    private       long                         invalidWaitTime       = 120000L;
    private       long                         refreshCacheTime      = 60000L;
    private       boolean                      localDiskCache        = false;
    private       long                         diskCacheTimeInterval = 3600000L;
    // 实例配置
    private       String                       applicationName       = "application";
    private       boolean                      automaticOnline       = true;
    /** Connector configurations prepared for a context; editable configurations derive them on demand. */
    private final Map<String, ConnectorConfig> connectorConfigs;
    private final List<MetExtensionFactory>    extensionFactory      = new ArrayList<>();

    public MetConfiguration() {
        this.connectorConfigs = null;
    }

    private MetConfiguration(MetConfiguration source, EndpointConnectorFactory factory) throws IOException {
        this.defaultTimeout = source.defaultTimeout;
        this.defaultGroup = source.defaultGroup;
        this.defaultVersion = source.defaultVersion;
        this.bindAddress = source.bindAddress;
        this.defaultProtocol = source.defaultProtocol;
        this.requestTimeout = source.requestTimeout;
        this.maximumRequest = source.maximumRequest;
        this.sendLimitPolicy = source.sendLimitPolicy;
        this.connectTimeout = source.connectTimeout;
        this.queueMaxSize = source.queueMaxSize;
        this.queueMinPoolSize = source.queueMinPoolSize;
        this.queueMaxPoolSize = source.queueMaxPoolSize;
        this.queueKeepAliveTime = source.queueKeepAliveTime;
        this.unitName = source.unitName;
        this.dataHome = source.dataHome;
        this.invalidWaitTime = source.invalidWaitTime;
        this.refreshCacheTime = source.refreshCacheTime;
        this.localDiskCache = source.localDiskCache;
        this.diskCacheTimeInterval = source.diskCacheTimeInterval;
        this.applicationName = source.applicationName;
        this.automaticOnline = source.automaticOnline;
        this.requestOptions.addOptionMap(source.requestOptions);
        this.responseOptions.addOptionMap(source.responseOptions);
        this.bindAddress = this.resolveBindAddress();
        this.connectorConfigs = source.createConnectorConfigs(factory);
        this.defaultProtocol = this.resolveDefaultProtocol(this.connectorConfigs);
    }

    // 服务默认值与序列化器
    public MetConfiguration setDefaultTimeout(int value) {
        this.defaultTimeout = value;
        return this;
    }

    public MetConfiguration setDefaultGroup(String value) {
        this.defaultGroup = value;
        return this;
    }

    public MetConfiguration setDefaultVersion(String value) {
        this.defaultVersion = value;
        return this;
    }

    // 处理调用
    public MetConfiguration setResponseOption(String name, String value) {
        this.responseOptions.addOption(name, value);
        return this;
    }

    public MetConfiguration setQueueMaxSize(int value) {
        this.queueMaxSize = value;
        return this;
    }

    public MetConfiguration setQueueMinPoolSize(int value) {
        this.queueMinPoolSize = value;
        return this;
    }

    public MetConfiguration setQueueMaxPoolSize(int value) {
        this.queueMaxPoolSize = value;
        return this;
    }

    public MetConfiguration setQueueKeepAliveTime(long value) {
        this.queueKeepAliveTime = value;
        return this;
    }

    // 连接与协议
    public MetConfiguration setBindAddress(String value) {
        this.bindAddress = value;
        return this;
    }

    public MetConfiguration setDefaultProtocol(String value) {
        this.defaultProtocol = value;
        return this;
    }

    /** Adds an endpoint using a transport discovered by SPI. Explicit endpoints replace the defaults. */
    public ConnectorSettings connector(String type) {
        ConnectorSettings connector = new ConnectorSettings(type);
        this.connectors.add(connector);
        return connector;
    }

    // 发起调用
    public MetConfiguration setRequestOption(String name, String value) {
        this.requestOptions.addOption(name, value);
        return this;
    }

    /** 设置服务调用的默认超时（毫秒）；非正数表示沿用 defaultTimeout，服务显式超时优先。 */
    public MetConfiguration setRequestTimeout(int value) {
        this.requestTimeout = value;
        return this;
    }

    public MetConfiguration setMaximumRequest(int value) {
        this.maximumRequest = value;
        return this;
    }

    public MetConfiguration setSendLimitPolicy(SendLimitPolicy value) {
        this.sendLimitPolicy = value;
        return this;
    }

    public MetConfiguration setConnectTimeout(int value) {
        this.connectTimeout = value;
        return this;
    }

    // 地址缓存与路由
    public MetConfiguration setInvalidWaitTime(long value) {
        this.invalidWaitTime = value;
        return this;
    }

    public MetConfiguration setRefreshCacheTime(long value) {
        this.refreshCacheTime = value;
        return this;
    }

    public MetConfiguration setLocalDiskCache(boolean value) {
        this.localDiskCache = value;
        return this;
    }

    public MetConfiguration setDiskCacheTimeInterval(long value) {
        this.diskCacheTimeInterval = value;
        return this;
    }

    // 实例配置
    public MetConfiguration setUnitName(String value) {
        this.unitName = value;
        return this;
    }

    public MetConfiguration setDataHome(Path value) {
        this.dataHome = value;
        return this;
    }

    /** 设置应用名称；null 或空白使用默认名称 application。 */
    public MetConfiguration setApplicationName(String value) {
        this.applicationName = StringUtils.defaultIfBlank(value, "application");
        return this;
    }

    public MetConfiguration setAutomaticOnline(boolean value) {
        this.automaticOnline = value;
        return this;
    }

    /** 添加扩展工厂；每次构建上下文都会创建独立的扩展实例。 */
    public MetConfiguration addExtension(MetExtensionFactory factory) {
        this.extensionFactory.add(Objects.requireNonNull(factory, "factory"));
        return this;
    }

    /** Builds the components; start() on the returned context opens its listeners. */
    public MetContext buildContext() throws IOException {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return this.buildContext(loader == null ? MetConfiguration.class.getClassLoader() : loader);
    }

    public MetContext buildContext(ClassLoader classLoader) throws IOException {
        Objects.requireNonNull(classLoader, "classLoader");
        EndpointConnectorFactory factory = new EndpointConnectorFactory();
        factory.listenTypes(classLoader);

        MetConfiguration settings = new MetConfiguration(this, factory);
        MetContextImpl context = new MetContextImpl(settings, classLoader);
        context.init(factory, this.extensionFactory);
        return context;
    }

    //
    //
    //

    // 服务默认值与序列化器
    @Override
    public int getDefaultTimeout() {
        return this.defaultTimeout;
    }

    @Override
    public String getDefaultGroup() {
        return this.defaultGroup;
    }

    @Override
    public String getDefaultVersion() {
        return this.defaultVersion;
    }

    // 处理调用
    @Override
    public MetOptionSet getResponseOptions() {
        return this.responseOptions;
    }

    @Override
    public int getQueueMaxSize() {
        return this.queueMaxSize;
    }

    @Override
    public int getQueueMinPoolSize() {
        return this.queueMinPoolSize;
    }

    @Override
    public int getQueueMaxPoolSize() {
        return this.queueMaxPoolSize;
    }

    @Override
    public long getQueueKeepAliveTime() {
        return this.queueKeepAliveTime;
    }

    // 连接与协议
    @Override
    public String getBindAddress() {
        return this.bindAddress;
    }

    @Override
    public String getDefaultProtocol() {
        return this.resolveDefaultProtocol(this.configuredConnectors());
    }

    @Override
    public Set<String> getProtocols() {
        Set<String> protocols = new LinkedHashSet<>();
        for (ConnectorConfig endpoint : this.configuredConnectors().values()) {
            for (ProtocolConfig protocol : endpoint.protocols()) {
                protocols.add(protocol.name());
            }
        }
        return Collections.unmodifiableSet(protocols);
    }

    @Override
    public InterAddress getBindAddressSet(String protocolName) {
        for (ConnectorConfig config : this.configuredConnectors().values()) {
            if (!config.bindEnabled()) {
                continue;
            }
            ProtocolConfig protocol = config.protocol(protocolName);
            if (protocol == null) {
                for (ProtocolConfig candidate : config.protocols()) {
                    if (StringUtils.equalsIgnoreCase(candidate.name(), protocolName)) {
                        protocol = candidate;
                        break;
                    }
                }
            }
            if (protocol != null) {
                InterAddress address = config.address();
                return InterAddress.forBinding(protocol.scheme(), address.getHost(), address.getPort(), address.getFormUnit());
            }
        }
        return null;
    }

    @Override
    public Collection<ConnectorConfig> getConnectorConfigs() {
        return Collections.unmodifiableCollection(this.configuredConnectors().values());
    }

    // 发起调用
    @Override
    public MetOptionSet getRequestOptions() {
        return this.requestOptions;
    }

    @Override
    public int getRequestTimeout() {
        if (this.requestTimeout > 0) {
            return this.requestTimeout;
        }
        return this.defaultTimeout;
    }

    @Override
    public int getMaximumRequest() {
        return this.maximumRequest;
    }

    @Override
    public SendLimitPolicy getSendLimitPolicy() {
        return this.sendLimitPolicy;
    }

    @Override
    public int getConnectTimeout() {
        return this.connectTimeout;
    }

    // 地址缓存与路由
    @Override
    public long getInvalidWaitTime() {
        return this.invalidWaitTime;
    }

    @Override
    public long getRefreshCacheTime() {
        return this.refreshCacheTime;
    }

    @Override
    public boolean isLocalDiskCache() {
        return this.localDiskCache;
    }

    @Override
    public long getDiskCacheTimeInterval() {
        return this.diskCacheTimeInterval;
    }

    // 实例配置
    @Override
    public String getApplicationName() {
        return this.applicationName;
    }

    @Override
    public String getUnitName() {
        return this.unitName;
    }

    @Override
    public Path getDataHome() {
        return this.dataHome;
    }

    @Override
    public boolean isAutomaticOnline() {
        return this.automaticOnline;
    }

    private Map<String, ConnectorConfig> configuredConnectors() {
        if (this.connectorConfigs != null) {
            return this.connectorConfigs;
        }
        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            EndpointConnectorFactory factory = new EndpointConnectorFactory();
            factory.listenTypes(loader == null ? MetConfiguration.class.getClassLoader() : loader);
            return this.createConnectorConfigs(factory);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private String resolveBindAddress() throws IOException {
        if (StringUtils.equalsIgnoreCase("local", this.bindAddress)) {
            List<String> addresses = NetworkUtils.localAddrForIPv4();
            Collections.sort(addresses);
            return addresses.isEmpty() ? InetAddress.getLocalHost().getHostAddress() : addresses.get(0);
        }
        return InetAddress.getByName(this.bindAddress).getHostAddress();
    }

    private Map<String, ConnectorConfig> createConnectorConfigs(EndpointConnectorFactory factory) throws IOException {
        List<ConnectorSettings> endpoints = this.connectors;
        if (endpoints.isEmpty()) {
            ConnectorSettings defaults = new ConnectorSettings("tcp");
            defaults.bind(this.bindAddress, 2181);
            defaults.option("workerThread", 2);
            defaults.option("taskThread", 2);
            defaults.protocol("rsf");
            endpoints = Collections.singletonList(defaults);
        }

        Map<String, ConnectorConfig> configured = new LinkedHashMap<>();
        Set<String> schemes = new HashSet<>();
        for (ConnectorSettings endpoint : endpoints) {
            // 先解析协议挂载，确保每种地址协议只属于一个端点。
            List<ProtocolConfig> protocols = new ArrayList<>();
            for (ProtocolSettings selection : endpoint.protocols) {
                ProtocolConfig protocol = factory.protocol(selection.name, selection.options);
                if (!schemes.add(protocol.scheme())) {
                    throw new IllegalArgumentException("Protocol scheme belongs to multiple mounts: " + protocol.scheme());
                }
                protocols.add(protocol);
            }

            // 合并连接参数，再由工厂校验传输层与协议的组合。
            Map<String, String> options = new LinkedHashMap<>(endpoint.options);
            options.put("listenType", endpoint.type);
            options.putIfAbsent("connectTimeout", Integer.toString(this.connectTimeout));
            String host = endpoint.host == null ? this.resolveBindAddress() : endpoint.host;
            InterAddress address = InterAddress.forBinding(endpoint.type, host, endpoint.port, this.unitName);
            String id = endpoint.type + "-" + configured.size();
            ConnectorConfig config = new ConnectorConfig(id, address, options, protocols, endpoint.host != null);
            factory.prepare(config);
            configured.put(id, config);
        }
        this.resolveDefaultProtocol(configured);
        return Collections.unmodifiableMap(configured);
    }

    private String resolveDefaultProtocol(Map<String, ConnectorConfig> configured) {
        for (ConnectorConfig endpoint : configured.values()) {
            for (ProtocolConfig protocol : endpoint.protocols()) {
                boolean matchesName = StringUtils.equalsIgnoreCase(this.defaultProtocol, protocol.name());
                boolean matchesScheme = StringUtils.equalsIgnoreCase(this.defaultProtocol, protocol.scheme());
                if (this.defaultProtocol == null || matchesName || matchesScheme) {
                    return protocol.name();
                }
            }
        }
        throw new IllegalArgumentException("Default protocol is not configured: " + this.defaultProtocol);
    }

    private static String selection(String name) {
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("SPI name must not be blank");
        }
        return name.toLowerCase(Locale.ROOT);
    }

    private static void option(Map<String, String> options, String name, Object value) {
        Set<String> identityOptions = Set.of("name", "listentype", "factory", "scheme", "protocol", "localport");
        boolean reserved = name != null && identityOptions.contains(name.toLowerCase(Locale.ROOT));
        if (StringUtils.isBlank(name) || reserved) {
            throw new IllegalArgumentException("Option cannot redefine connector or protocol identity: " + name);
        }
        options.put(name, Objects.requireNonNull(value, "value").toString());
    }

    /** Editable endpoint settings. Without bind(), the endpoint only creates outbound connections. */
    public static final class ConnectorSettings {
        private final String                 type;
        private       String                 host;
        private       int                    port;
        private final Map<String, String>    options   = new LinkedHashMap<>();
        private final List<ProtocolSettings> protocols = new ArrayList<>();

        private ConnectorSettings(String type) {
            this.type = selection(type);
        }

        public ConnectorSettings bind(String host, int port) {
            if (StringUtils.isBlank(host) || port < 0 || port > 65535) {
                throw new IllegalArgumentException("Invalid bind address: " + host + ":" + port);
            }
            this.host = host;
            this.port = port;
            return this;
        }

        public ConnectorSettings option(String name, Object value) {
            MetConfiguration.option(this.options, name, value);
            return this;
        }

        public ProtocolSettings protocol(String name) {
            ProtocolSettings protocol = new ProtocolSettings(name);
            this.protocols.add(protocol);
            return protocol;
        }
    }

    /** Editable protocol options; its name, scheme and required transport are owned by the SPI. */
    public static final class ProtocolSettings {
        private final String              name;
        private final Map<String, String> options = new LinkedHashMap<>();

        private ProtocolSettings(String name) {
            this.name = selection(name);
        }

        public ProtocolSettings option(String name, Object value) {
            MetConfiguration.option(this.options, name, value);
            return this;
        }

        /** 设置协议默认上下文，未显式选择路径的服务只发布到这个上下文。 */
        public ProtocolSettings contextPath(String path) {
            String normalized = ContextPathUtils.normalize(path);
            return this.option("contextPath", normalized);
        }

        /** 添加可供服务显式选择的上下文，不改变协议默认上下文。 */
        public ProtocolSettings addContextPath(String path) {
            ContextPathUtils.addContextPath(this.options, path);
            return this;
        }
    }
}

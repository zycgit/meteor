/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.bootstrap;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import net.hasor.cobble.NetworkUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.rsf.RsfContext;
import net.hasor.rsf.RsfOptionSet;
import net.hasor.rsf.RsfSettings;
import net.hasor.rsf.SendLimitPolicy;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.address.route.ArgsKey;
import net.hasor.rsf.address.route.DefaultArgsKey;
import net.hasor.rsf.connector.ConnectorConfig;
import net.hasor.rsf.connector.ProtocolConfig;
import net.hasor.rsf.connector.protocol.EndpointConnectorFactory;
import net.hasor.rsf.domain.OptionInfo;

/** Configures RSF components and creates a context ready for service registration. */
public final class Configuration implements RsfSettings {

    // 服务默认值
    private       int                      defaultTimeout        = 6000;
    private       String                   defaultGroup          = "RSF";
    private       String                   defaultVersion        = "1.0.0";
    // 序列化器
    private       String                   serializeType         = "Java";
    // 连接与协议
    private       String                   bindAddress           = "127.0.0.1";
    private       String                   defaultProtocol       = null;
    private final List<ConnectorSettings>  connectors            = new ArrayList<>();
    // 请求与响应
    private       int                      requestTimeout        = 6000;
    private       int                      maximumRequest        = 200;
    private       SendLimitPolicy          sendLimitPolicy       = SendLimitPolicy.Reject;
    private       int                      connectTimeout        = 3000;
    private final OptionInfo               requestOptions        = new OptionInfo();
    private final OptionInfo               responseOptions       = new OptionInfo();
    // 处理调用
    private       int                      queueMaxSize          = 4096;
    private       int                      queueMinPoolSize      = 1;
    private       int                      queueMaxPoolSize      = 4;
    private       long                     queueKeepAliveTime    = 300L;
    // 地址缓存与路由
    private       String                   unitName              = "default";
    private       Path                     dataHome              = Paths.get("rsf-data");
    private       long                     invalidWaitTime       = 120000L;
    private       long                     refreshCacheTime      = 60000L;
    private       boolean                  localDiskCache        = false;
    private       long                     diskCacheTimeInterval = 3600000L;
    private       Class<? extends ArgsKey> argsKeyClass          = DefaultArgsKey.class;
    // 实例配置
    private       boolean                  automaticOnline       = true;

    /** Connector configurations prepared for a context; editable configurations derive them on demand. */
    private final Map<String, ConnectorConfig> connectorConfigs;

    public Configuration() {
        this.connectorConfigs = null;
    }

    private Configuration(Configuration source, EndpointConnectorFactory factory) throws IOException {
        this.defaultTimeout = source.defaultTimeout;
        this.defaultGroup = source.defaultGroup;
        this.defaultVersion = source.defaultVersion;
        this.serializeType = source.serializeType;
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
        this.argsKeyClass = source.argsKeyClass;
        this.automaticOnline = source.automaticOnline;
        this.requestOptions.addOptionMap(source.requestOptions);
        this.responseOptions.addOptionMap(source.responseOptions);
        this.bindAddress = this.resolveBindAddress();
        this.connectorConfigs = source.createConnectorConfigs(factory);
        this.defaultProtocol = this.resolveDefaultProtocol(this.connectorConfigs);
    }

    // 服务默认值与序列化器
    public Configuration setDefaultTimeout(int value) {
        this.defaultTimeout = value;
        return this;
    }

    public Configuration setDefaultGroup(String value) {
        this.defaultGroup = value;
        return this;
    }

    public Configuration setDefaultVersion(String value) {
        this.defaultVersion = value;
        return this;
    }

    public Configuration setSerializeType(String value) {
        this.serializeType = value;
        return this;
    }

    // 处理调用
    public Configuration setResponseOption(String name, String value) {
        this.responseOptions.addOption(name, value);
        return this;
    }

    public Configuration setQueueMaxSize(int value) {
        this.queueMaxSize = value;
        return this;
    }

    public Configuration setQueueMinPoolSize(int value) {
        this.queueMinPoolSize = value;
        return this;
    }

    public Configuration setQueueMaxPoolSize(int value) {
        this.queueMaxPoolSize = value;
        return this;
    }

    public Configuration setQueueKeepAliveTime(long value) {
        this.queueKeepAliveTime = value;
        return this;
    }

    // 连接与协议
    public Configuration setBindAddress(String value) {
        this.bindAddress = value;
        return this;
    }

    public Configuration setDefaultProtocol(String value) {
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
    public Configuration setRequestOption(String name, String value) {
        this.requestOptions.addOption(name, value);
        return this;
    }

    public Configuration setRequestTimeout(int value) {
        this.requestTimeout = value;
        return this;
    }

    public Configuration setMaximumRequest(int value) {
        this.maximumRequest = value;
        return this;
    }

    public Configuration setSendLimitPolicy(SendLimitPolicy value) {
        this.sendLimitPolicy = value;
        return this;
    }

    public Configuration setConnectTimeout(int value) {
        this.connectTimeout = value;
        return this;
    }

    // 地址缓存与路由
    public Configuration setInvalidWaitTime(long value) {
        this.invalidWaitTime = value;
        return this;
    }

    public Configuration setRefreshCacheTime(long value) {
        this.refreshCacheTime = value;
        return this;
    }

    public Configuration setLocalDiskCache(boolean value) {
        this.localDiskCache = value;
        return this;
    }

    public Configuration setDiskCacheTimeInterval(long value) {
        this.diskCacheTimeInterval = value;
        return this;
    }

    public Configuration setArgsKey(Class<? extends ArgsKey> type) {
        this.argsKeyClass = Objects.requireNonNull(type, "argsKeyClass");
        return this;
    }

    // 实例配置
    public Configuration setUnitName(String value) {
        this.unitName = value;
        return this;
    }

    public Configuration setDataHome(Path value) {
        this.dataHome = value;
        return this;
    }

    public Configuration setAutomaticOnline(boolean value) {
        this.automaticOnline = value;
        return this;
    }

    /** Builds the components; start() on the returned context opens its listeners. */
    public RsfContext buildContext() throws IOException {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return this.buildContext(loader == null ? Configuration.class.getClassLoader() : loader);
    }

    public RsfContext buildContext(ClassLoader classLoader) throws IOException {
        Objects.requireNonNull(classLoader, "classLoader");
        EndpointConnectorFactory factory = new EndpointConnectorFactory();
        factory.listenTypes(classLoader);
        Configuration settings = new Configuration(this, factory);
        return new RsfContextImpl(settings, classLoader, factory);
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

    @Override
    public String getSerializeType() {
        return this.serializeType;
    }

    // 处理调用
    @Override
    public RsfOptionSet getResponseOptions() {
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
    public RsfOptionSet getRequestOptions() {
        return this.requestOptions;
    }

    @Override
    public int getRequestTimeout() {
        return this.requestTimeout;
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

    @Override
    public Class<? extends ArgsKey> getArgsKeyClass() {
        return this.argsKeyClass;
    }

    // 实例配置
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
            factory.listenTypes(loader == null ? Configuration.class.getClassLoader() : loader);
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
            ConnectorSettings defaults = new ConnectorSettings("tcp").bind(this.bindAddress, 2181).option("workerThread", 2).option("taskThread", 2);
            defaults.protocol("rsf");
            endpoints = Collections.singletonList(defaults);
        }
        Map<String, ConnectorConfig> configured = new LinkedHashMap<>();
        Set<String> schemes = new HashSet<>();
        for (ConnectorSettings endpoint : endpoints) {
            List<ProtocolConfig> protocols = new ArrayList<>();
            for (ProtocolSettings selection : endpoint.protocols) {
                ProtocolConfig protocol = factory.protocol(selection.name, selection.options);
                if (!schemes.add(protocol.scheme())) {
                    throw new IllegalArgumentException("Protocol scheme belongs to multiple mounts: " + protocol.scheme());
                }
                protocols.add(protocol);
            }
            Map<String, String> options = new LinkedHashMap<>(endpoint.options);
            options.put("listenType", endpoint.type);
            options.putIfAbsent("connectTimeout", Integer.toString(this.connectTimeout));
            String host = endpoint.host == null ? this.resolveBindAddress() : endpoint.host;
            InterAddress address = InterAddress.forBinding(endpoint.type, host, endpoint.port, this.unitName);
            String id = endpoint.type + "-" + configured.size();
            ConnectorConfig config = new ConnectorConfig(id, address, options, protocols, endpoint.host != null);
            configured.put(id, factory.prepare(config));
        }
        this.resolveDefaultProtocol(configured);
        return Collections.unmodifiableMap(configured);
    }

    private String resolveDefaultProtocol(Map<String, ConnectorConfig> configured) {
        for (ConnectorConfig endpoint : configured.values()) {
            for (ProtocolConfig protocol : endpoint.protocols()) {
                if (this.defaultProtocol == null || StringUtils.equalsIgnoreCase(this.defaultProtocol, protocol.name()) || StringUtils.equalsIgnoreCase(this.defaultProtocol, protocol.scheme())) {
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
        if (StringUtils.isBlank(name) || Set.of("name", "listentype", "factory", "scheme", "protocol", "localport").contains(name.toLowerCase(Locale.ROOT))) {
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
            Configuration.option(this.options, name, value);
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
            Configuration.option(this.options, name, value);
            return this;
        }

        public ProtocolSettings contextPath(String path) {
            return this.option("contextPath", path);
        }
    }
}

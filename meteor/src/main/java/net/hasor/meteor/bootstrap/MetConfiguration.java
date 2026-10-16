/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.bootstrap;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetExtensionFactory;
import net.hasor.meteor.MetOptionSet;
import net.hasor.meteor.SendLimitPolicy;
import net.hasor.meteor.domain.OptionInfo;
import net.hasor.meteor.utils.ContextPathUtils;

/** Editable configuration inputs; address resolution and component assembly run only when building a context. */
public final class MetConfiguration {
    // 服务默认值
    private       int                       defaultTimeout        = 6000;
    private       String                    defaultGroup          = "default";
    private       String                    defaultVersion        = "1.0.0";
    // 连接与协议
    private       String                    bindAddress           = "127.0.0.1";
    private       String                    defaultProtocol       = null;
    private final List<ConnectorSettings>   connectors            = new ArrayList<>();
    // 请求与响应
    private       int                       requestTimeout        = 0;
    private       int                       maximumRequest        = 200;
    private       SendLimitPolicy           sendLimitPolicy       = SendLimitPolicy.Reject;
    private       int                       connectTimeout        = 3000;
    private final OptionInfo                requestOptions        = new OptionInfo();
    private final OptionInfo                responseOptions       = new OptionInfo();
    // 处理调用
    private       int                       queueMaxSize          = 4096;
    private       int                       queueMinPoolSize      = 1;
    private       int                       queueMaxPoolSize      = 4;
    private       long                      queueKeepAliveTime    = 300L;
    // 地址缓存与路由
    private       String                    unitName              = "default";
    private       Path                      dataHome              = Paths.get("meteor-data");
    private       long                      invalidWaitTime       = 120000L;
    private       long                      refreshCacheTime      = 60000L;
    private       boolean                   localDiskCache        = false;
    private       long                      diskCacheTimeInterval = 3600000L;
    // 实例配置
    private       String                    applicationName       = "application";
    private       boolean                   automaticOnline       = true;
    private final List<MetExtensionFactory> extensionFactory      = new ArrayList<>();

    public MetConfiguration() {
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
        return MetContextFactory.create(this);
    }

    public MetContext buildContext(ClassLoader classLoader) throws IOException {
        return MetContextFactory.create(this, classLoader);
    }

    // 服务默认值与序列化器
    public int getDefaultTimeout() {
        return this.defaultTimeout;
    }

    public String getDefaultGroup() {
        return this.defaultGroup;
    }

    public String getDefaultVersion() {
        return this.defaultVersion;
    }

    // 处理调用
    public MetOptionSet getResponseOptions() {
        return this.responseOptions;
    }

    public int getQueueMaxSize() {
        return this.queueMaxSize;
    }

    public int getQueueMinPoolSize() {
        return this.queueMinPoolSize;
    }

    public int getQueueMaxPoolSize() {
        return this.queueMaxPoolSize;
    }

    public long getQueueKeepAliveTime() {
        return this.queueKeepAliveTime;
    }

    // 连接与协议
    public String getBindAddress() {
        return this.bindAddress;
    }

    /** 原始协议选择；null 表示由构建阶段选择第一个挂载协议。 */
    public String getDefaultProtocol() {
        return this.defaultProtocol;
    }

    /** 用户声明的端点；未声明时返回空列表，默认端点在构建时补齐。 */
    public List<ConnectorSettings> getConnectors() {
        return Collections.unmodifiableList(this.connectors);
    }

    List<MetExtensionFactory> getExtensionFactories() {
        return Collections.unmodifiableList(this.extensionFactory);
    }

    // 发起调用
    public MetOptionSet getRequestOptions() {
        return this.requestOptions;
    }

    /** 原始调用超时；非正数在构建时回退到 defaultTimeout。 */
    public int getRequestTimeout() {
        return this.requestTimeout;
    }

    public int getMaximumRequest() {
        return this.maximumRequest;
    }

    public SendLimitPolicy getSendLimitPolicy() {
        return this.sendLimitPolicy;
    }

    public int getConnectTimeout() {
        return this.connectTimeout;
    }

    // 地址缓存与路由
    public long getInvalidWaitTime() {
        return this.invalidWaitTime;
    }

    public long getRefreshCacheTime() {
        return this.refreshCacheTime;
    }

    public boolean isLocalDiskCache() {
        return this.localDiskCache;
    }

    public long getDiskCacheTimeInterval() {
        return this.diskCacheTimeInterval;
    }

    // 实例配置
    public String getApplicationName() {
        return this.applicationName;
    }

    public String getUnitName() {
        return this.unitName;
    }

    public Path getDataHome() {
        return this.dataHome;
    }

    public boolean isAutomaticOnline() {
        return this.automaticOnline;
    }

    private static String selection(String name) {
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("SPI name must not be blank");
        }
        return name.toLowerCase(Locale.ROOT);
    }

    private static void option(Map<String, String> options, String name, Object value) {
        Set<String> identityOptions = Set.of("name", "transporttype", "factory", "scheme", "protocol", "localport");
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

        public String getType() {
            return this.type;
        }

        public String getHost() {
            return this.host;
        }

        public int getPort() {
            return this.port;
        }

        public Map<String, String> getOptions() {
            return Collections.unmodifiableMap(this.options);
        }

        public List<ProtocolSettings> getProtocols() {
            return Collections.unmodifiableList(this.protocols);
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

    /** Editable protocol mount options. The SPI name and public address scheme are configured separately. */
    public static final class ProtocolSettings {
        private final String              name;
        private       String              addressScheme;
        private final Map<String, String> options = new LinkedHashMap<>();

        private ProtocolSettings(String name) {
            this.name = selection(name);
        }

        public String getName() {
            return this.name;
        }

        public String getAddressScheme() {
            return this.addressScheme;
        }

        public Map<String, String> getOptions() {
            return Collections.unmodifiableMap(this.options);
        }

        /** 设置该挂载发布和直连时使用的地址 scheme，不改变协议 SPI 身份。 */
        public ProtocolSettings addressScheme(String scheme) {
            this.addressScheme = selection(scheme);
            return this;
        }

        public ProtocolSettings option(String name, Object value) {
            if (transportSecurityOption(name)) {
                throw new IllegalArgumentException("Transport security option belongs to connector: " + name);
            }
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

    private static boolean transportSecurityOption(String name) {
        String normalized = StringUtils.defaultIfBlank(name, "").toLowerCase(Locale.ROOT);
        return normalized.startsWith("tls") || normalized.startsWith("dtls") || //
                StringUtils.equalsIgnoreCase(normalized, "maxDatagramSize");
    }
}

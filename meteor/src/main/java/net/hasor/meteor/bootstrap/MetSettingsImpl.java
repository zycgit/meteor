/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.bootstrap;
import java.nio.file.Path;
import java.util.*;
import net.hasor.meteor.MetOptionParser;
import net.hasor.meteor.MetOptionSet;
import net.hasor.meteor.MetSettings;
import net.hasor.meteor.SendLimitPolicy;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ProtocolConfig;

/** Read-only MetSettings implementation owned by its context. */
final class MetSettingsImpl implements MetSettings {
    private final int                   defaultTimeout;
    private final String                defaultGroup;
    private final String                defaultVersion;
    private final String                bindAddress;
    private final String                defaultProtocol;
    private final int                   requestTimeout;
    private final int                   maximumRequest;
    private final SendLimitPolicy       sendLimitPolicy;
    private final int                   connectTimeout;
    private final int                   queueMaxSize;
    private final int                   queueMinPoolSize;
    private final int                   queueMaxPoolSize;
    private final long                  queueKeepAliveTime;
    private final String                unitName;
    private final Path                  dataHome;
    private final long                  invalidWaitTime;
    private final long                  refreshCacheTime;
    private final boolean               localDiskCache;
    private final long                  diskCacheTimeInterval;
    private final String                applicationName;
    private final boolean               automaticOnline;
    private final MetOptionSet          requestOptions;
    private final MetOptionSet          responseOptions;
    private final List<ConnectorConfig> connectorConfigs;
    private final Set<String>           protocols;

    MetSettingsImpl(MetConfiguration source, String bindAddress, String defaultProtocol, //
            List<ConnectorConfig> connectorConfigs) {
        this.defaultTimeout = source.getDefaultTimeout();
        this.defaultGroup = source.getDefaultGroup();
        this.defaultVersion = source.getDefaultVersion();
        this.bindAddress = bindAddress;
        this.defaultProtocol = defaultProtocol;
        this.requestTimeout = source.getRequestTimeout() > 0 ? source.getRequestTimeout() : source.getDefaultTimeout();
        this.maximumRequest = source.getMaximumRequest();
        this.sendLimitPolicy = source.getSendLimitPolicy();
        this.connectTimeout = source.getConnectTimeout();
        this.queueMaxSize = source.getQueueMaxSize();
        this.queueMinPoolSize = source.getQueueMinPoolSize();
        this.queueMaxPoolSize = source.getQueueMaxPoolSize();
        this.queueKeepAliveTime = source.getQueueKeepAliveTime();
        this.unitName = source.getUnitName();
        this.dataHome = source.getDataHome();
        this.invalidWaitTime = source.getInvalidWaitTime();
        this.refreshCacheTime = source.getRefreshCacheTime();
        this.localDiskCache = source.isLocalDiskCache();
        this.diskCacheTimeInterval = source.getDiskCacheTimeInterval();
        this.applicationName = source.getApplicationName();
        this.automaticOnline = source.isAutomaticOnline();
        this.requestOptions = new ReadOnlyOptions(source.getRequestOptions());
        this.responseOptions = new ReadOnlyOptions(source.getResponseOptions());
        this.connectorConfigs = List.copyOf(connectorConfigs);
        Set<String> protocols = new LinkedHashSet<>();
        for (ConnectorConfig connector : this.connectorConfigs) {
            for (ProtocolConfig protocol : connector.protocols()) {
                protocols.add(protocol.name());
            }
        }
        this.protocols = Collections.unmodifiableSet(protocols);
    }

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
    public String getBindAddress() {
        return this.bindAddress;
    }

    @Override
    public String getDefaultProtocol() {
        return this.defaultProtocol;
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

    @Override
    public String getUnitName() {
        return this.unitName;
    }

    @Override
    public Path getDataHome() {
        return this.dataHome;
    }

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
    public String getApplicationName() {
        return this.applicationName;
    }

    @Override
    public boolean isAutomaticOnline() {
        return this.automaticOnline;
    }

    @Override
    public MetOptionSet getRequestOptions() {
        return this.requestOptions;
    }

    @Override
    public MetOptionSet getResponseOptions() {
        return this.responseOptions;
    }

    @Override
    public Collection<ConnectorConfig> getConnectorConfigs() {
        return this.connectorConfigs;
    }

    @Override
    public Set<String> getProtocols() {
        return this.protocols;
    }

    /** Keeps the existing option-reading contract while rejecting every mutation entry point. */
    private static final class ReadOnlyOptions implements MetOptionSet {
        private final Map<String, String> options;

        private ReadOnlyOptions(MetOptionSet source) {
            Map<String, String> options = new LinkedHashMap<>();
            for (String key : source.getOptionKeys()) {
                options.put(key, source.getOption(key));
            }
            // OptionInfo 允许 null 键和值；只读副本保留相同的读取语义。
            this.options = Collections.unmodifiableMap(options);
        }

        @Override
        public String[] getOptionKeys() {
            return this.options.keySet().toArray(new String[0]);
        }

        @Override
        public String getOption(String key) {
            return this.options.get(key);
        }

        @Override
        public <T> T getOption(String key, MetOptionParser<T> parser) {
            return parser.parse(this.getOption(key));
        }

        @Override
        public void addOption(String key, String value) {
            throw new UnsupportedOperationException("Context settings are read-only");
        }

        @Override
        public <T> void addOption(String key, T value, MetOptionParser<T> parser) {
            throw new UnsupportedOperationException("Context settings are read-only");
        }

        @Override
        public void removeOption(String key) {
            throw new UnsupportedOperationException("Context settings are read-only");
        }
    }
}

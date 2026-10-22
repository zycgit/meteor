/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.*;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.connector.transport.TransportConfig;
import net.hasor.meteor.connector.transport.TransportFactory;
import net.hasor.meteor.connector.transport.TransportPlan;
import net.hasor.meteor.domain.ProtocolConfig;

/** Discovers connector SPIs and compiles immutable startup plans without allocating network resources. */
final class PlanCompiler {
    private final Map<String, TransportFactory<?>> discoveredTransports = new LinkedHashMap<>();
    private final Map<String, ProtocolProvider<?>> discoveredProtocols  = new LinkedHashMap<>();
    private final Set<ConnectorConfig>             preparedConfigs      = Collections.newSetFromMap(new IdentityHashMap<>());
    private       ClassLoader                      loader;

    void discover(ClassLoader loader) {
        if (this.loader == loader) {
            return;
        }

        // 发现并校验传输层 SPI。
        Map<String, TransportFactory<?>> transports = new LinkedHashMap<>();
        for (TransportFactory<?> factory : ServiceLoader.load(TransportFactory.class, loader)) {
            put(transports, factory.name(), factory, "transport");
        }

        // 发现并校验协议 SPI。
        Map<String, ProtocolProvider<?>> protocols = new LinkedHashMap<>();
        for (ProtocolProvider<?> provider : ServiceLoader.load(ProtocolProvider.class, loader)) {
            put(protocols, provider.name(), provider, "protocol");
        }

        // 两类 SPI 都加载成功后再替换当前规划器的发现结果。
        this.discoveredTransports.clear();
        this.discoveredTransports.putAll(transports);
        this.discoveredProtocols.clear();
        this.discoveredProtocols.putAll(protocols);
        this.preparedConfigs.clear();
        this.loader = loader;
    }

    /** Validate all endpoints against the discovered protocol providers. */
    void prepareAll(Collection<ConnectorConfig> configs) {
        for (ConnectorConfig config : configs) {
            TransportFactory<?> transport = this.discoveredTransports.get(config.transportType());
            if (transport == null) {
                throw new IllegalArgumentException("Unknown transport: " + config.transportType());
            }

            if (!this.preparedConfigs.contains(config)) {
                this.prepare(config, transport);
                this.preparedConfigs.add(config);
            }
        }
    }

    private <M> void prepare(ConnectorConfig config, TransportFactory<M> transport) {
        // 参数优先级：协议显式配置 > 端点公共参数 > 协议默认值。
        Map<String, Map<String, String>> routes = new LinkedHashMap<>();
        for (ProtocolConfig mount : config.protocols()) {
            ProtocolProvider<M> protocol = this.resolve(mount, transport);
            Map<String, String> options = new LinkedHashMap<>();
            copyProtocolOptions(config.options(), options);
            copyProtocolOptions(mount.options(), options);

            // 协议只读取端点最终的安全事实，不能接触证书材料或覆盖真实传输状态。
            String tlsEnabled = config.options().get("tlsEnabled");
            if (tlsEnabled != null) {
                options.put("tlsEnabled", tlsEnabled);
            }

            boolean hasContext = options.containsKey("contextPath") || options.containsKey("contextPaths");
            if (hasContext && !StringUtils.equalsIgnoreCase(transport.name(), "http")) {
                throw new IllegalArgumentException("Transport does not support HTTP contexts: " + transport.name());
            }

            if (!hasContext && StringUtils.equalsIgnoreCase(transport.name(), "http")) {
                String defaultPath = config.protocols().size() == 1 ? "/" : "/" + mount.name();
                options.put("contextPath", defaultPath);
            }

            options.putIfAbsent("serializeType", protocol.defaultSerialize());
            routes.put(mount.name(), options);
        }

        // 传输层只整理参数；校验完成后更新原来的协议配置。
        transport.configRoutes(routes);
        for (ProtocolConfig mount : config.protocols()) {
            mount.setOptions(routes.get(mount.name()));
        }
    }

    private static void copyProtocolOptions(Map<String, String> source, Map<String, String> target) {
        for (Map.Entry<String, String> option : source.entrySet()) {
            String name = option.getKey();
            String normalized = name.toLowerCase(Locale.ROOT);
            boolean transportSecurity = normalized.startsWith("tls") ||//
                    normalized.startsWith("dtls") || //
                    StringUtils.equalsIgnoreCase(normalized, "maxDatagramSize");
            boolean transportPipeline = StringUtils.equalsIgnoreCase(normalized, "transportPipeline");
            if (!transportSecurity && !transportPipeline) {
                target.put(name, option.getValue());
            }
        }
    }

    private static <T> void put(Map<String, T> registry, String name, T provider, String kind) {
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("Missing " + kind + " SPI name");
        }
        if (registry.putIfAbsent(name.toLowerCase(Locale.ROOT), provider) != null) {
            throw new IllegalStateException("Duplicate " + kind + " SPI name: " + name);
        }
    }

    PlanEndpoint<?> compile(ConnectorConfig config) {
        TransportFactory<?> transport = this.discoveredTransports.get(config.transportType());
        if (transport == null) {
            throw new IllegalArgumentException("Unknown transport: " + config.transportType());
        }
        return this.compile(config, transport);
    }

    private <M> PlanEndpoint<M> compile(ConnectorConfig config, TransportFactory<M> transport) {
        // 固定协议挂载与上下文声明，运行期路由表由管理器统一组装。
        Map<String, ProtocolMountBinding<M>> protocolBindings = new LinkedHashMap<>();
        Map<String, Map<String, String>> protocolOptionsByMount = new LinkedHashMap<>();
        for (ProtocolConfig mount : config.protocols()) {
            ProtocolProvider<M> protocol = this.resolve(mount, transport);
            RouteMountIndex routes = new RouteMountIndex(config.name(), mount);
            protocolBindings.put(mount.name(), new ProtocolMountBinding<>(mount, protocol, routes));
            protocolOptionsByMount.put(mount.name(), mount.options());
        }

        // 传输计划只保留运行时创建连接器所需的不可变装配结果。
        TransportConfig transportConfig = new TransportConfig(//
                config.name(), config.address(), config.bindEnabled(), config.options(), protocolOptionsByMount);
        TransportPlan<M> transportPlan = transport.prepare(transportConfig, this.loader);
        return new PlanEndpoint<>(config, transportPlan, transport.sharedRoutes(), protocolBindings);
    }

    @SuppressWarnings("unchecked")
    private <M> ProtocolProvider<M> resolve(ProtocolConfig config, TransportFactory<M> transport) {
        ProtocolProvider<?> protocol = this.discoveredProtocols.get(config.name());
        if (protocol == null) {
            throw new IllegalArgumentException("Unknown protocol: " + config.name());
        }

        if (!StringUtils.equalsIgnoreCase(transport.name(), protocol.transportType())) {
            String message = "Protocol " + protocol.name() + " requires transport " + protocol.transportType();
            message += ", endpoint uses " + transport.name();
            throw new IllegalArgumentException(message);
        }

        if (protocol.messageType() != transport.messageType()) {
            throw new IllegalArgumentException("Incompatible transport message type for protocol: " + protocol.name());
        }

        // SPI 消息类型已经在跨越泛型注册表之前完成校验。
        return (ProtocolProvider<M>) protocol;
    }
}

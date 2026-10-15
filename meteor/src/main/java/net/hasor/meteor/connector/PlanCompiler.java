/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.*;
import java.util.function.Function;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.connector.transport.NetworkConnectorFactory;
import net.hasor.meteor.connector.transport.TransportConfig;
import net.hasor.meteor.connector.transport.TransportPlan;

/** Discovers connector SPIs and compiles immutable startup plans without allocating network resources. */
final class PlanCompiler {
    private final Map<String, NetworkConnectorFactory<?>> transports = new LinkedHashMap<>();
    private final Map<String, ProtocolFactory<?>>         protocols  = new LinkedHashMap<>();
    private final Set<ConnectorConfig>                    prepared   = Collections.newSetFromMap(new IdentityHashMap<>());
    private       ClassLoader                             loader;

    void discover(ClassLoader loader) {
        if (this.loader == loader) {
            return;
        }

        // 发现并校验传输层 SPI。
        Map<String, NetworkConnectorFactory<?>> networks = new LinkedHashMap<>();
        for (NetworkConnectorFactory<?> factory : ServiceLoader.load(NetworkConnectorFactory.class, loader)) {
            put(networks, factory.name(), factory, "transport");
        }

        // 发现并校验协议 SPI。
        Map<String, ProtocolFactory<?>> codecs = new LinkedHashMap<>();
        for (ProtocolFactory<?> factory : ServiceLoader.load(ProtocolFactory.class, loader)) {
            put(codecs, factory.name(), factory, "protocol");
        }

        // 两类 SPI 都加载成功后再替换当前规划器的发现结果。
        this.transports.clear();
        this.transports.putAll(networks);
        this.protocols.clear();
        this.protocols.putAll(codecs);
        this.prepared.clear();
        this.loader = loader;
    }

    /** Validate all endpoints against one logical protocol namespace. */
    void prepareAll(Collection<ConnectorConfig> configs) {
        this.configureLogicalNames(configs);
        for (ConnectorConfig config : configs) {
            NetworkConnectorFactory<?> transport = this.transports.get(config.listenType());
            if (transport == null) {
                throw new IllegalArgumentException("Unknown transport: " + config.listenType());
            }
            if (!this.prepared.contains(config)) {
                this.prepare(config, transport);
                this.prepared.add(config);
            }
        }
    }

    private void configureLogicalNames(Collection<ConnectorConfig> endpoints) {
        Set<String> logicalNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (ConnectorConfig endpoint : endpoints) {
            for (ProtocolConfig protocol : endpoint.protocols()) {
                logicalNames.add(protocol.name());
            }
        }
        for (ConnectorConfig endpoint : endpoints) {
            for (ProtocolConfig protocol : endpoint.protocols()) {
                protocol.setLogicalNames(logicalNames);
            }
        }
    }

    private <M> void prepare(ConnectorConfig config, NetworkConnectorFactory<M> transport) {
        // 参数优先级：协议显式配置 > 端点公共参数 > 协议默认值。
        Map<String, Map<String, String>> routes = new LinkedHashMap<>();
        for (ProtocolConfig mount : config.protocols()) {
            ProtocolFactory<M> protocol = this.resolve(mount, transport);
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

            options.putIfAbsent("serializeType", protocol.defaultSerializeType());
            routes.put(mount.mountId(), options);
        }

        // 传输层只整理参数；校验完成后更新原来的协议配置。
        transport.configRoutes(routes);
        for (ProtocolConfig mount : config.protocols()) {
            mount.setOptions(routes.get(mount.mountId()));
        }
    }

    private static void copyProtocolOptions(Map<String, String> source, Map<String, String> target) {
        for (Map.Entry<String, String> option : source.entrySet()) {
            String name = option.getKey();
            String normalized = name.toLowerCase(Locale.ROOT);
            boolean transportSecurity = normalized.startsWith("tls") || normalized.startsWith("dtls") || //
                    StringUtils.equalsIgnoreCase(normalized, "maxDatagramSize");
            boolean transportPipeline = StringUtils.equalsIgnoreCase(normalized, "transportPipeline");
            if (!transportSecurity && !transportPipeline) {
                target.put(name, option.getValue());
            }
        }
    }

    private static <T> void put(Map<String, T> registry, String name, T factory, String kind) {
        if (StringUtils.isBlank(name)) {
            throw new IllegalArgumentException("Missing " + kind + " SPI name");
        }
        if (registry.putIfAbsent(name.toLowerCase(Locale.ROOT), factory) != null) {
            throw new IllegalStateException("Duplicate " + kind + " SPI name: " + name);
        }
    }

    PlanConnector<?> compile(ConnectorConfig config) {
        NetworkConnectorFactory<?> transport = this.transports.get(config.listenType());
        if (transport == null) {
            throw new IllegalArgumentException("Unknown transport: " + config.listenType());
        }
        return this.compile(config, transport);
    }

    private <M> PlanConnector<M> compile(ConnectorConfig config, NetworkConnectorFactory<M> transport) {
        // 将协议挂载编译成运行期协议与路由值。
        Map<String, RouteProtocol<M>> protocols = new LinkedHashMap<>();
        Map<String, Map<String, String>> routeOptions = new LinkedHashMap<>();
        Map<String, RouteEntry<M>> routeEntries = new LinkedHashMap<>();
        for (ProtocolConfig mount : config.protocols()) {
            ProtocolFactory<M> protocol = this.resolve(mount, transport);
            Function<M, RouteMatch> probe = message -> protocol.probe(mount, message);
            RouteEntry<M> route = new RouteEntry<>(mount.options(), mount.contexts(), probe);
            protocols.put(mount.mountId(), new RouteProtocol<>(mount, protocol));
            routeEntries.put(mount.mountId(), route);
            routeOptions.put(mount.mountId(), mount.options());
        }

        // 传输计划只保留运行时创建连接器所需的不可变装配结果。
        PlanRoutes<M> routePlan = new PlanRoutes<>(routeEntries);
        TransportConfig transportConfig = new TransportConfig(//
                config.name(), config.address(), config.bindEnabled(), config.options(), routeOptions);
        TransportPlan<M> transportPlan = transport.prepare(transportConfig, this.loader);
        return new PlanConnector<>(config, transportPlan, transport.sharedRoutes(), protocols, routePlan);
    }

    @SuppressWarnings("unchecked")
    private <M> ProtocolFactory<M> resolve(ProtocolConfig config, NetworkConnectorFactory<M> transport) {
        ProtocolFactory<?> protocol = this.protocols.get(config.protocol());
        if (protocol == null) {
            throw new IllegalArgumentException("Unknown protocol: " + config.protocol());
        }
        if (!StringUtils.equalsIgnoreCase(transport.name(), protocol.transport())) {
            String message = "Protocol " + protocol.name() + " requires transport " + protocol.transport();
            message += ", endpoint uses " + transport.name();
            throw new IllegalArgumentException(message);
        }
        if (protocol.messageType() != transport.messageType()) {
            throw new IllegalArgumentException("Incompatible transport message type for protocol: " + protocol.name());
        }

        // SPI 消息类型已经在跨越泛型注册表之前完成校验。
        return (ProtocolFactory<M>) protocol;
    }
}

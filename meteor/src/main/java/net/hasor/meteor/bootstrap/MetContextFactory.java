/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.bootstrap;
import java.io.IOException;
import java.net.InetAddress;
import java.util.*;
import net.hasor.cobble.NetworkUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetExtensionFactory;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.bootstrap.MetConfiguration.ProtocolSettings;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.ProtocolConfig;

/** Resolves configuration inputs and transfers each initialized context to its caller. */
final class MetContextFactory {
    private MetContextFactory() {
    }

    static MetContext create(MetConfiguration configuration) throws IOException {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return create(configuration, loader == null ? MetContextFactory.class.getClassLoader() : loader);
    }

    static MetContext create(MetConfiguration configuration, ClassLoader classLoader) throws IOException {
        Objects.requireNonNull(classLoader, "classLoader");
        String bindAddress = resolveBindAddress(configuration.getBindAddress());
        List<ConnectorConfig> connectors = createConnectorConfigs(configuration, bindAddress);
        String defaultProtocol = resolveDefaultProtocol(configuration.getDefaultProtocol(), connectors);
        MetSettingsImpl settings = new MetSettingsImpl(configuration, bindAddress, defaultProtocol, connectors);
        List<MetExtensionFactory> extensions = List.copyOf(configuration.getExtensionFactories());

        // Context 持有核心组件和扩展，并负责初始化失败与正常关闭时的资源回收。
        MetContextImpl context = new MetContextImpl(settings, classLoader);
        context.init(extensions);
        return context;
    }

    private static String resolveBindAddress(String bindAddress) throws IOException {
        if (StringUtils.isBlank(bindAddress)) {
            throw new IllegalArgumentException("Bind address must not be blank");
        }
        if (StringUtils.equalsIgnoreCase("local", bindAddress)) {
            List<String> addresses = NetworkUtils.localAddrForIPv4();
            Collections.sort(addresses);
            return addresses.isEmpty() ? InetAddress.getLocalHost().getHostAddress() : addresses.get(0);
        }
        return InetAddress.getByName(bindAddress).getHostAddress();
    }

    private static List<ConnectorConfig> createConnectorConfigs(MetConfiguration configuration, String bindAddress) {
        List<ConnectorSettings> endpoints = configuration.getConnectors();
        String connectTimeout = Integer.toString(configuration.getConnectTimeout());
        if (endpoints.isEmpty()) {
            // 默认端点仅属于本次构建，不写回用户配置。
            InterAddress address = InterAddress.forBinding("tcp", bindAddress, 2181, configuration.getUnitName());
            Map<String, String> options = new LinkedHashMap<>();
            options.put("workerThread", "2");
            options.put("taskThread", "2");
            options.put("connectTimeout", connectTimeout);
            ProtocolConfig protocol = ProtocolConfig.newConfig("rsf", null, Map.of());
            return List.of(new ConnectorConfig("tcp-0", address, options, List.of(protocol), true));
        }

        List<ConnectorConfig> configured = new ArrayList<>();
        for (ConnectorSettings endpoint : endpoints) {
            Map<String, String> options = new LinkedHashMap<>(endpoint.getOptions());
            options.putIfAbsent("connectTimeout", connectTimeout);

            List<ProtocolConfig> protocols = new ArrayList<>();
            for (ProtocolSettings selection : endpoint.getProtocols()) {
                ProtocolConfig protocol = ProtocolConfig.newConfig(selection.getName(), selection.getAddressScheme(), //
                        selection.getOptions());
                protocols.add(protocol);
            }

            // 只构建端点快照；SPI 发现、协议参数准备与启动计划编译仍由连接管理器负责。
            boolean bindEnabled = endpoint.getHost() != null;
            String host = bindEnabled ? endpoint.getHost() : bindAddress;
            InterAddress address = InterAddress.forBinding(endpoint.getType(), host, endpoint.getPort(), //
                    configuration.getUnitName());
            String id = endpoint.getType() + "-" + configured.size();
            configured.add(new ConnectorConfig(id, address, options, protocols, bindEnabled));
        }
        return configured;
    }

    private static String resolveDefaultProtocol(String defaultProtocol, List<ConnectorConfig> configured) {
        if (defaultProtocol == null) {
            return configured.get(0).protocols().iterator().next().name();
        }

        // 逻辑协议名优先于地址 scheme，避免两类标识碰撞时受端点顺序影响。
        for (ConnectorConfig endpoint : configured) {
            for (ProtocolConfig protocol : endpoint.protocols()) {
                if (StringUtils.equalsIgnoreCase(defaultProtocol, protocol.name())) {
                    return protocol.name();
                }
            }
        }

        // 没有逻辑名匹配时，再按公开地址别名选择唯一的逻辑协议。
        String selected = null;
        for (ConnectorConfig endpoint : configured) {
            for (ProtocolConfig protocol : endpoint.protocols()) {
                if (StringUtils.equalsIgnoreCase(defaultProtocol, protocol.scheme())) {
                    if (selected != null && !StringUtils.equalsIgnoreCase(selected, protocol.name())) {
                        throw new IllegalArgumentException("Default protocol alias is ambiguous: " + defaultProtocol);
                    }
                    selected = protocol.name();
                }
            }
        }
        if (selected != null) {
            return selected;
        }
        throw new IllegalArgumentException("Default protocol is not configured: " + defaultProtocol);
    }
}

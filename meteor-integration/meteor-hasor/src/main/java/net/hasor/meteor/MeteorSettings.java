/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import net.hasor.cobble.BeanUtils;
import net.hasor.cobble.ClassUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.setting.SettingNode;
import net.hasor.cobble.setting.Settings;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ProtocolProvider;

/** Reads the Hasor configuration tree without flattening or rewriting its keys. */
final class MeteorSettings {
    static MetConfiguration configuration(Settings settings, ClassLoader loader) {
        MetConfiguration config = new MetConfiguration();
        config.setAddressBook(settings.getString("meteor.address-book"));
        config.setApplicationName(settings.getString("meteor.application-name", config.getApplicationName()));
        config.setUnitName(settings.getString("meteor.unit", config.getUnitName()));
        config.setDefaultProtocol(settings.getString("meteor.default-protocol", config.getDefaultProtocol()));
        config.setDefaultGroup(settings.getString("meteor.default-group", config.getDefaultGroup()));
        config.setDefaultVersion(settings.getString("meteor.default-version", config.getDefaultVersion()));
        config.setDefaultTimeout(settings.getInteger("meteor.default-timeout", config.getDefaultTimeout()));
        config.setRequestTimeout(settings.getInteger("meteor.request-timeout", config.getRequestTimeout()));
        config.setConnectTimeout(settings.getInteger("meteor.connect-timeout", config.getConnectTimeout()));
        config.setBindAddress(settings.getString("meteor.bind-address", config.getBindAddress()));

        Map<String, MetConfiguration.ConnectorSettings> connectors = new LinkedHashMap<>();
        for (ProtocolProvider<?> provider : ServiceLoader.load(ProtocolProvider.class, loader)) {
            String prefix = "meteor.connectors." + provider.name() + ".";
            String host = settings.getString(prefix + "host");
            Integer port = settings.getInteger(prefix + "port");
            if (StringUtils.isBlank(host) || port == null) {
                continue;
            }
            String key = provider.transportType() + "://" + host + ":" + port;
            MetConfiguration.ConnectorSettings connector = connectors.computeIfAbsent(key, ignored -> {
                return config.connector(provider.transportType()).bind(host, port);
            });
            MetConfiguration.ProtocolSettings protocol = connector.protocol(provider.name());
            String scheme = settings.getString(prefix + "address-scheme");
            if (scheme != null) {
                protocol.addressScheme(scheme);
            }
            String path = settings.getString(prefix + "context");
            if (path != null) {
                protocol.contextPath(path);
            }
            options(settings, settings.getNode(prefix + "options"), protocol::option);
        }
        if (connectors.isEmpty()) {
            throw new IllegalArgumentException("No Meteor protocol enabled; configure meteor.connectors.<protocol>.host and port");
        }
        return config;
    }

    /** 仅显式配置时加载可选注册中心；已有配置 Bean 优先，应用资源仍由容器持有。 */
    static MetExtensionFactory registry(Settings settings, ClassLoader loader, //
            Collection<MetExtensionFactory> extensions, BiFunction<String, Class<?>, Object> beans) {
        boolean configured = settings.getNode("meteor.registry") != null;
        if (!configured || !settings.getBoolean("meteor.registry.enabled", true)) {
            return null;
        }

        Class<?> registryType;
        try {
            registryType = loader.loadClass("net.hasor.meteor.registry.RegistryConfiguration");
        } catch (ClassNotFoundException | LinkageError missing) {
            throw new IllegalStateException("meteor.registry requires the meteor-registry dependency", missing);
        }
        for (MetExtensionFactory extension : extensions) {
            if (registryType.isInstance(extension)) {
                return null;
            }
        }

        String provider = settings.getString("meteor.registry.provider");
        String className = registryType.getName();
        String module = "meteor-registry";
        if (settings.getBoolean("meteor.registry.online", true)) {
            if (StringUtils.isBlank(provider)) {
                throw new IllegalArgumentException("meteor.registry.provider is required in online mode");
            }
            switch (provider.trim()) {
                case "memory":
                    className = "net.hasor.meteor.registry.memory.MemoryRegistryConfiguration";
                    break;
                case "jdbc":
                    className = "net.hasor.meteor.registry.jdbc.JdbcRegistryConfiguration";
                    module = "meteor-registry-jdbc";
                    break;
                case "nacos":
                    className = "net.hasor.meteor.registry.nacos.NacosRegistryConfiguration";
                    module = "meteor-registry-nacos";
                    break;
                case "zookeeper":
                    className = "net.hasor.meteor.registry.zookeeper.ZookeeperRegistryConfiguration";
                    module = "meteor-registry-zookeeper";
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported meteor.registry.provider: " + provider);
            }
        }

        MetExtensionFactory registry;
        try {
            registry = ClassUtils.newInstance(loader.loadClass(className).asSubclass(MetExtensionFactory.class));
        } catch (ClassNotFoundException | LinkageError missing) {
            throw new IllegalStateException("meteor.registry requires the " + module + " dependency", missing);
        }
        String fixedToken = settings.getString("meteor.registry.management-token");
        String tokenProvider = settings.getString("meteor.registry.token-provider");
        if (fixedToken != null && tokenProvider != null) {
            throw new IllegalArgumentException("Configure only one of meteor.registry.management-token and token-provider");
        }

        // 只读取所选配置类型公开的 setter，不加载其他注册中心实现或改写原生配置源。
        for (String property : BeanUtils.getProperties(registry.getClass())) {
            if (!BeanUtils.canWriteProperty(registry.getClass(), property)) {
                continue;
            }
            String key = "meteor.registry." + StringUtils.humpToLine(property).replace('_', '-');
            Class<?> type = BeanUtils.getWriteMethod(registry.getClass(), property).getParameterTypes()[0];
            String text = settings.getString(key);
            Object value = text;
            boolean beanType = !type.isPrimitive() && type != String.class;
            if (type == String[].class) {
                String[] values = settings.getStringArray(key);
                value = Arrays.stream(values).flatMap(item -> Arrays.stream(item.split(",", -1))) // 保留空项交给配置校验。
                        .map(String::trim).toArray(String[]::new);
                if (text == null && ((String[]) value).length == 0) {
                    continue;
                }
            } else if (StringUtils.equals(property, "dataSource") || text != null && beanType) {
                value = beans.apply(text, type);
                if (value == null || !type.isInstance(value)) {
                    throw new IllegalArgumentException(key + " requires a matching container Bean");
                }
            } else if (text == null) {
                continue;
            }
            if (!BeanUtils.writeProperty(registry, property, value)) {
                throw new IllegalArgumentException("Invalid registry setting: " + key);
            }
        }
        return registry;
    }

    static <T> void configureService(Settings settings, Class<T> type, MetPublisher.ConfigurationBuilder<T> binding) {
        String prefix = "meteor.services." + type.getName();
        String[] protocols = Arrays.stream(settings.getStringArray(prefix + ".protocols")).flatMap(value -> Arrays.stream(value.split(","))).map(String::trim).filter(value -> !value.isEmpty()).toArray(String[]::new);
        if (protocols.length > 0) {
            binding.protocol(protocols[0], Arrays.copyOfRange(protocols, 1, protocols.length));
        }
        options(settings, settings.getNode(prefix + ".aliases"), binding::aliasName);
    }

    private static void options(Settings settings, SettingNode node, BiConsumer<String, String> setter) {
        if (node == null) {
            return;
        }

        int prefixLength = node.getFullName().length() + 1;
        node.visitNodes(option -> {
            if (option != node && option.getValue() != null) {
                String name = option.getFullName().substring(prefixLength);
                setter.accept(name, settings.evalSetting(option.getValue()));
            }
        });
    }
}

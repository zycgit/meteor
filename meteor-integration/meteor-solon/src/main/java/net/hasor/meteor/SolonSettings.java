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
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ProtocolProvider;
import org.noear.solon.core.Props;

/** Uses Solon's native grouped/list properties instead of normalizing a configuration Map. */
final class SolonSettings {
    private SolonSettings() {
    }

    static MetConfiguration configuration(Props properties, ClassLoader loader) {
        MetConfiguration config = new MetConfiguration();
        config.setAddressBook(properties.get("meteor.address-book"));
        config.setApplicationName(properties.get("meteor.application", config.getApplicationName()));
        config.setUnitName(properties.get("meteor.unit", config.getUnitName()));
        config.setDefaultProtocol(properties.get("meteor.default-protocol", config.getDefaultProtocol()));
        config.setDefaultGroup(properties.get("meteor.default-group", config.getDefaultGroup()));
        config.setDefaultVersion(properties.get("meteor.default-version", config.getDefaultVersion()));
        config.setDefaultTimeout(properties.getInt("meteor.default-timeout", config.getDefaultTimeout()));
        config.setRequestTimeout(properties.getInt("meteor.request-timeout", config.getRequestTimeout()));
        config.setConnectTimeout(properties.getInt("meteor.connect-timeout", config.getConnectTimeout()));
        config.setBindAddress(properties.get("meteor.bind-address", config.getBindAddress()));

        Map<String, MetConfiguration.ConnectorSettings> connectors = new LinkedHashMap<>();
        for (ProtocolProvider<?> provider : ServiceLoader.load(ProtocolProvider.class, loader)) {
            String prefix = "meteor.connectors." + provider.name() + ".";
            String host = properties.get(prefix + "host");
            String configuredPort = properties.get(prefix + "port");
            if (StringUtils.isBlank(host) || StringUtils.isBlank(configuredPort)) {
                continue;
            }

            int port = properties.getInt(prefix + "port", 0);
            String key = provider.transportType() + "://" + host + ":" + port;
            MetConfiguration.ConnectorSettings connector = connectors.computeIfAbsent(key, ignored -> {
                return config.connector(provider.transportType()).bind(host, port);
            });

            MetConfiguration.ProtocolSettings protocol = connector.protocol(provider.name());
            String scheme = properties.get(prefix + "address-scheme");
            if (scheme != null) {
                protocol.addressScheme(scheme);
            }
            String path = properties.get(prefix + "context");
            if (path != null) {
                protocol.contextPath(path);
            }
            options(properties, prefix + "options.", protocol::option);
        }

        if (connectors.isEmpty()) {
            throw new IllegalArgumentException("No Meteor protocol enabled; configure meteor.connectors.<protocol>.host and port");
        }
        return config;
    }

    /** 仅显式配置时加载可选注册中心；已有配置 Bean 优先，应用资源仍由容器持有。 */
    static MetExtensionFactory registry(Props properties, ClassLoader loader, //
            Collection<MetExtensionFactory> extensions, BiFunction<String, Class<?>, Object> beans) {
        boolean configured = properties.stringPropertyNames().stream() // 原生属性及索引列表都保留原名。
                .anyMatch(name -> name.startsWith("meteor.registry."));
        if (!configured || !properties.getBool("meteor.registry.enabled", true)) {
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

        String provider = properties.get("meteor.registry.provider");
        String className = registryType.getName();
        String module = "meteor-registry";
        if (properties.getBool("meteor.registry.online", true)) {
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
        String fixedToken = properties.get("meteor.registry.management-token");
        String tokenProvider = properties.get("meteor.registry.token-provider");
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
            String text = properties.get(key);
            Object value = text;
            boolean beanType = !type.isPrimitive() && type != String.class;
            if (type == String[].class) {
                String[] values;
                if (text != null) {
                    values = text.split(",", -1);
                } else {
                    values = properties.getList(key).toArray(String[]::new);
                }
                value = Arrays.stream(values).map(String::trim).toArray(String[]::new);
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

    private static void options(Props properties, String prefix, BiConsumer<String, String> setter) {
        // Protocol option names are opaque keys; keep hyphens instead of applying Bean-name conversion.
        for (String name : properties.stringPropertyNames()) {
            if (name.startsWith(prefix)) {
                setter.accept(name.substring(prefix.length()), properties.get(name));
            }
        }
    }
}
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
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

/** Reads directly from Spring Environment, preserving its precedence, placeholders and type conversion. */
final class SpringSettings {
    private SpringSettings() {
    }

    static MetConfiguration configuration(Environment environment, ClassLoader loader) {
        MetConfiguration config = new MetConfiguration();
        config.setAddressBook(environment.getProperty("meteor.address-book"));
        config.setApplicationName(environment.getProperty("meteor.application", config.getApplicationName()));
        config.setUnitName(environment.getProperty("meteor.unit", config.getUnitName()));
        config.setDefaultProtocol(environment.getProperty("meteor.default-protocol", config.getDefaultProtocol()));
        config.setDefaultGroup(environment.getProperty("meteor.default-group", config.getDefaultGroup()));
        config.setDefaultVersion(environment.getProperty("meteor.default-version", config.getDefaultVersion()));
        int defaultTimeout = environment.getProperty("meteor.default-timeout", Integer.class, config.getDefaultTimeout());
        int requestTimeout = environment.getProperty("meteor.request-timeout", Integer.class, config.getRequestTimeout());
        int connectTimeout = environment.getProperty("meteor.connect-timeout", Integer.class, config.getConnectTimeout());
        config.setDefaultTimeout(defaultTimeout);
        config.setRequestTimeout(requestTimeout);
        config.setConnectTimeout(connectTimeout);
        config.setBindAddress(environment.getProperty("meteor.bind-address", config.getBindAddress()));

        Map<String, MetConfiguration.ConnectorSettings> connectors = new LinkedHashMap<>();
        for (ProtocolProvider<?> provider : ServiceLoader.load(ProtocolProvider.class, loader)) {
            String prefix = "meteor.connectors." + provider.name() + ".";
            String host = environment.getProperty(prefix + "host");
            Integer port = environment.getProperty(prefix + "port", Integer.class);
            if (StringUtils.isBlank(host) || port == null) {
                continue;
            }
            String key = provider.transportType() + "://" + host + ":" + port;
            MetConfiguration.ConnectorSettings connector = connectors.computeIfAbsent(key, ignored -> {
                return config.connector(provider.transportType()).bind(host, port);
            });
            MetConfiguration.ProtocolSettings protocol = connector.protocol(provider.name());
            String scheme = environment.getProperty(prefix + "address-scheme");
            if (scheme != null) {
                protocol.addressScheme(scheme);
            }
            String path = environment.getProperty(prefix + "context");
            if (path != null) {
                protocol.contextPath(path);
            }
            options(environment, prefix + "options.", protocol::option);
        }
        if (connectors.isEmpty()) {
            throw new IllegalArgumentException("No Meteor protocol enabled; configure meteor.connectors.<protocol>.host and port");
        }
        return config;
    }

    /** 仅显式配置时加载可选注册中心；已有配置 Bean 优先，应用资源仍由容器持有。 */
    static MetExtensionFactory registry(Environment environment, ClassLoader loader, //
            Collection<MetExtensionFactory> extensions, BiFunction<String, Class<?>, Object> beans) {
        boolean configured = environment.containsProperty("meteor.registry.provider") || environment.containsProperty("meteor.registry.online") || environment.containsProperty("meteor.registry.enabled") || propertyNames(environment).stream().anyMatch(name -> name.startsWith("meteor.registry."));
        if (!configured || !environment.getProperty("meteor.registry.enabled", Boolean.class, true)) {
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

        String provider = environment.getProperty("meteor.registry.provider");
        String className = registryType.getName();
        String module = "meteor-registry";
        if (environment.getProperty("meteor.registry.online", Boolean.class, true)) {
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
        String fixedToken = environment.getProperty("meteor.registry.management-token");
        String tokenProvider = environment.getProperty("meteor.registry.token-provider");
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
            String text = environment.getProperty(key);
            Object value = text;
            boolean beanType = !type.isPrimitive() && type != String.class;
            if (type == String[].class) {
                String[] values = environment.getProperty(key, String[].class);
                if (values == null) {
                    List<String> indexed = new ArrayList<>();
                    for (int index = 0; environment.containsProperty(key + "[" + index + "]"); index++) {
                        indexed.add(environment.getRequiredProperty(key + "[" + index + "]"));
                    }
                    values = indexed.toArray(String[]::new);
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

    private static Set<String> propertyNames(Environment environment) {
        Set<String> names = new LinkedHashSet<>();
        if (environment instanceof ConfigurableEnvironment configurable) {
            for (PropertySource<?> source : configurable.getPropertySources()) {
                if (source instanceof EnumerablePropertySource<?> enumerable) {
                    Collections.addAll(names, enumerable.getPropertyNames());
                }
            }
        }
        return names;
    }

    private static void options(Environment environment, String prefix, BiConsumer<String, String> setter) {
        // Only discover opaque map keys; Environment still resolves every value and its priority.
        for (String name : propertyNames(environment)) {
            if (name.startsWith(prefix)) {
                setter.accept(name.substring(prefix.length()), environment.getRequiredProperty(name));
            }
        }
    }
}
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
        config.setApplicationName(environment.getProperty("meteor.application-name", config.getApplicationName()));
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

    static <T> void configureService(Environment environment, Class<T> type, MetPublisher.ConfigurationBuilder<T> binding) {
        String prefix = "meteor.services." + type.getName() + ".";
        String[] protocols = environment.getProperty(prefix + "protocols", String[].class);
        if (protocols == null) {
            List<String> values = new ArrayList<>();
            for (int index = 0; environment.containsProperty(prefix + "protocols[" + index + "]"); index++) {
                values.add(environment.getRequiredProperty(prefix + "protocols[" + index + "]"));
            }
            protocols = values.toArray(String[]::new);
        }

        if (protocols.length > 0) {
            binding.protocol(protocols[0], Arrays.copyOfRange(protocols, 1, protocols.length));
        }
        options(environment, prefix + "aliases.", binding::aliasName);
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
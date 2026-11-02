/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.BiConsumer;
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
        config.setApplicationName(properties.get("meteor.application-name", config.getApplicationName()));
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

    static <T> void configureService(Props properties, Class<T> type, MetPublisher.ConfigurationBuilder<T> binding) {
        String prefix = "meteor.services." + type.getName() + ".";
        String scalar = properties.get(prefix + "protocols");
        String[] protocols;
        if (StringUtils.isNotBlank(scalar)) {
            protocols = Arrays.stream(scalar.split(",")).map(String::trim) // 单值配置支持逗号分隔的协议名。
                    .filter(value -> !value.isEmpty()).toArray(String[]::new);
        } else {
            protocols = properties.getList(prefix + "protocols").toArray(String[]::new);
        }

        if (protocols.length > 0) {
            binding.protocol(protocols[0], Arrays.copyOfRange(protocols, 1, protocols.length));
        }
        options(properties, prefix + "aliases.", binding::aliasName);
    }

    private static void options(Props properties, String prefix, BiConsumer<String, String> setter) {
        // Protocol option/alias names are opaque keys; keep hyphens instead of applying Bean-name conversion.
        for (String name : properties.stringPropertyNames()) {
            if (name.startsWith(prefix)) {
                setter.accept(name.substring(prefix.length()), properties.get(name));
            }
        }
    }
}
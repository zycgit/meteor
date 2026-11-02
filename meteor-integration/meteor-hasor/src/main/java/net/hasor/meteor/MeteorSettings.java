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

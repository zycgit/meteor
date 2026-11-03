/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.integration;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import net.hasor.cobble.StringUtils;
import net.hasor.example.integration.FrameworkOrderService;
import net.hasor.realmeteor.environment.discovery.RegistryDiscoveryEnvironment;
import net.hasor.realmeteor.environment.storage.ZookeeperTestServer;

/** 只借用现有 Docker 存储环境；每个场景独占数据库或注册空间。 */
public final class FrameworkRegistry implements AutoCloseable {
    private final AutoCloseable environment;
    private final Properties settings;

    private FrameworkRegistry(AutoCloseable environment, Properties settings) {
        this.environment = environment;
        this.settings = settings;
    }

    public static FrameworkRegistry open(String backend, Path directory) throws Exception {
        Properties settings = new Properties();
        settings.setProperty("test.backend", backend);
        if (StringUtils.equals(backend, "dubbo")) {
            ZookeeperTestServer zookeeper = ZookeeperTestServer.dedicated();
            settings.setProperty("test.dubbo.address", "zookeeper://" + zookeeper.getConnectString());
            settings.setProperty("test.dubbo.group", "/meteor-framework-" + UUID.randomUUID());
            return new FrameworkRegistry(zookeeper, settings);
        }

        RegistryDiscoveryEnvironment registry = RegistryDiscoveryEnvironment.open(backend, directory);
        try {
            Properties connection = registry.registryProperties();
            settings.setProperty("meteor.registry.provider", backend);
            if (StringUtils.equals(backend, "jdbc")) {
                settings.setProperty("meteor.registry.data-source", "registryDb");
                settings.setProperty("test.jdbc.url", connection.getProperty("jdbcUrl"));
            } else {
                settings.setProperty("meteor.registry.address", connection.getProperty("address"));
                if (StringUtils.equals(backend, "zookeeper")) {
                    settings.setProperty("meteor.registry.root", connection.getProperty("root"));
                } else {
                    settings.setProperty("meteor.registry.group", connection.getProperty("group"));
                }
            }
            return new FrameworkRegistry(registry, settings);
        } catch (Exception failure) {
            try {
                registry.close();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public Properties settings(String framework, String role) {
        Properties properties = new Properties();
        properties.putAll(this.settings);
        properties.setProperty("meteor.application", framework + "-" + role);
        properties.setProperty("meteor.unit", "framework");
        String servicePackage = FrameworkOrderService.class.getPackageName();
        properties.setProperty("hasor.loadPackages", servicePackage);
        properties.setProperty("meteor.scan-packages", servicePackage);
        String host = System.getenv("METEOR_TEST_HOST_JAVA");
        if (StringUtils.isBlank(host)) {
            throw new IllegalStateException("METEOR_TEST_HOST_JAVA is required for Docker framework peers");
        }
        properties.setProperty("test.publish-host", host);
        String protocol = "rsf";
        if (StringUtils.equals(this.settings.getProperty("test.backend"), "dubbo")) {
            protocol = "dubbo";
        } else {
            properties.setProperty("meteor.registry.publish-host", host);
        }
        properties.setProperty("meteor.default-protocol", protocol);
        properties.setProperty("meteor.connectors." + protocol + ".host", "0.0.0.0");
        properties.setProperty("meteor.connectors." + protocol + ".port", "0");
        return properties;
    }

    @Override
    public void close() throws Exception {
        this.environment.close();
    }
}

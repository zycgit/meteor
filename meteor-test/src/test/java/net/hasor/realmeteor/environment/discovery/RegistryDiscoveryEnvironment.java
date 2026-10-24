/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.environment.discovery;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.jdbc.JdbcRegistryConfiguration;
import net.hasor.meteor.registry.nacos.NacosRegistryConfiguration;
import net.hasor.meteor.registry.zookeeper.ZookeeperRegistryConfiguration;
import net.hasor.realmeteor.environment.storage.NacosTestServer;
import net.hasor.realmeteor.environment.storage.JdbcTestServer;
import net.hasor.realmeteor.environment.storage.ZookeeperTestServer;
import org.h2.jdbcx.JdbcDataSource;

/** 场景独占注册库，沿用接入层默认订阅与周期核对；应用和适配器管理各自的存储会话。 */
public final class RegistryDiscoveryEnvironment implements AutoCloseable {
    private final RegistryConfiguration configuration;
    private final AutoCloseable         resource;
    private final Properties            properties;

    private RegistryDiscoveryEnvironment(RegistryConfiguration configuration, AutoCloseable resource, Properties properties) {
        this.configuration = configuration;
        this.resource = resource;
        this.properties = properties;
    }

    public static RegistryDiscoveryEnvironment open(String backend, Path workDirectory) throws Exception {
        return switch (backend) {
            case "jdbc" -> jdbc(workDirectory);
            case "zookeeper" -> zookeeper();
            case "nacos" -> nacos(workDirectory);
            default -> throw new IllegalArgumentException("Unknown registry backend: " + backend);
        };
    }

    private static RegistryDiscoveryEnvironment jdbc(Path workDirectory) throws Exception {
        // H2 单独运行，描述中的同一个 TCP URL 也可交给其他应用容器。
        JdbcTestServer server = new JdbcTestServer(workDirectory.resolve("h2"));
        try {
            JdbcDataSource source = server.createDatabase("foreign_orders");
            JdbcRegistryConfiguration configuration = new JdbcRegistryConfiguration().setDataSource(source);
            Properties properties = new Properties();
            properties.setProperty("backend", "jdbc");
            properties.setProperty("jdbcUrl", source.getURL());
            properties.setProperty("user", "sa");
            properties.setProperty("password", "");
            return new RegistryDiscoveryEnvironment(configuration, server, properties);
        } catch (Exception failure) {
            server.close();
            throw failure;
        }
    }

    private static RegistryDiscoveryEnvironment zookeeper() throws Exception {
        ZookeeperTestServer server = new ZookeeperTestServer();
        ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
        configuration.setAddress(server.getConnectString());
        String root = "/meteor-test/foreign-orders-" + UUID.randomUUID();
        configuration.setRoot(root);
        Properties properties = new Properties();
        properties.setProperty("backend", "zookeeper");
        properties.setProperty("address", server.getConnectString());
        properties.setProperty("root", root);
        return new RegistryDiscoveryEnvironment(configuration, server, properties);
    }

    private static RegistryDiscoveryEnvironment nacos(Path workDirectory) throws Exception {
        NacosTestServer server = new NacosTestServer(workDirectory.resolve("nacos"));
        try {
            server.start();
            NacosRegistryConfiguration configuration = new NacosRegistryConfiguration();
            configuration.setAddress(server.address());
            String group = "foreign-orders-" + UUID.randomUUID();
            configuration.setGroup(group);
            Properties properties = new Properties();
            properties.setProperty("backend", "nacos");
            properties.setProperty("address", server.address());
            properties.setProperty("group", group);
            return new RegistryDiscoveryEnvironment(configuration, server, properties);
        } catch (Exception failure) {
            server.close();
            throw failure;
        }
    }

    public RegistryConfiguration configuration() {
        return this.configuration;
    }

    public Properties registryProperties() {
        Properties result = new Properties();
        result.putAll(this.properties);
        return result;
    }

    @Override
    public void close() throws Exception {
        this.resource.close();
    }
}

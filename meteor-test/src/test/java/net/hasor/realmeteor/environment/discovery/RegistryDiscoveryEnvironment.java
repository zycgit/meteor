/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.environment.discovery;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.UUID;
import net.hasor.dbvisitor.jdbc.core.JdbcTemplate;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.jdbc.JdbcRegistryConfiguration;
import net.hasor.meteor.registry.nacos.NacosRegistryConfiguration;
import net.hasor.meteor.registry.zookeeper.ZookeeperRegistryConfiguration;
import net.hasor.realmeteor.environment.storage.NacosTestServer;
import org.apache.curator.test.TestingServer;
import org.h2.jdbcx.JdbcDataSource;

/** 场景独占注册库，沿用接入层默认订阅与周期核对；应用和适配器管理各自的存储会话。 */
public final class RegistryDiscoveryEnvironment implements AutoCloseable {
    private final RegistryConfiguration configuration;
    private final AutoCloseable         resource;

    private RegistryDiscoveryEnvironment(RegistryConfiguration configuration, AutoCloseable resource) {
        this.configuration = configuration;
        this.resource = resource;
    }

    public static RegistryDiscoveryEnvironment open(String backend, Path workDirectory) throws Exception {
        return switch (backend) {
            case "jdbc" -> jdbc();
            case "zookeeper" -> zookeeper();
            case "nacos" -> nacos(workDirectory);
            default -> throw new IllegalArgumentException("Unknown registry backend: " + backend);
        };
    }

    private static RegistryDiscoveryEnvironment jdbc() throws Exception {
        // H2 与两个 Java 存储适配会话同 JVM；外部 Python 只通过网络执行真实业务 RPC。
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:foreign-orders-" + UUID.randomUUID());
        source.setUser("sa");
        source.setPassword("");
        Connection database = source.getConnection();
        try {
            var schema = RegistryDiscoveryEnvironment.class.getResourceAsStream("/META-INF/meteor/schema/h2.sql");
            try (InputStreamReader reader = new InputStreamReader(schema, StandardCharsets.UTF_8)) {
                new JdbcTemplate(database).loadSplitSQL(";", reader);
            }
            JdbcRegistryConfiguration configuration = new JdbcRegistryConfiguration().setDataSource(source);
            return new RegistryDiscoveryEnvironment(configuration, database);
        } catch (Exception failure) {
            database.close();
            throw failure;
        }
    }

    private static RegistryDiscoveryEnvironment zookeeper() throws Exception {
        TestingServer server = new TestingServer();
        ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
        configuration.setAddress(server.getConnectString());
        configuration.setRoot("/meteor-test/foreign-orders-" + UUID.randomUUID());
        return new RegistryDiscoveryEnvironment(configuration, server);
    }

    private static RegistryDiscoveryEnvironment nacos(Path workDirectory) throws Exception {
        NacosTestServer server = new NacosTestServer(workDirectory.resolve("nacos"));
        try {
            server.start();
            NacosRegistryConfiguration configuration = new NacosRegistryConfiguration();
            configuration.setAddress(server.address());
            configuration.setGroup("foreign-orders-" + UUID.randomUUID());
            return new RegistryDiscoveryEnvironment(configuration, server);
        } catch (Exception failure) {
            server.close();
            throw failure;
        }
    }

    public RegistryConfiguration configuration() {
        return this.configuration;
    }

    @Override
    public void close() throws Exception {
        this.resource.close();
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.matrix;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import net.hasor.dbvisitor.jdbc.core.JdbcTemplate;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.jdbc.JdbcRegistryConfiguration;
import net.hasor.meteor.registry.nacos.NacosRegistryConfiguration;
import net.hasor.meteor.registry.zookeeper.ZookeeperRegistryConfiguration;
import net.hasor.realmeteor.environment.storage.NacosTestServer;
import org.apache.curator.test.TestingServer;
import org.h2.jdbcx.JdbcDataSource;

/** 一个矩阵测试类持有外部存储生命周期，各用例的配置、服务分组和本地缓存仍相互隔离。 */
public final class OrderRegistryEnvironment implements AutoCloseable {
    private final Path workDirectory;
    private final Map<String, Connection> databases = new LinkedHashMap<>();
    private TestingServer zookeeper;
    private NacosTestServer nacos;

    public OrderRegistryEnvironment(Path workDirectory) {
        this.workDirectory = workDirectory;
    }

    public RegistryConfiguration configuration(OrderDiscovery discovery, String scope) throws Exception {
        // 仅启动当前矩阵选择的存储；过滤直连执行时不会下载或启动其他存储。
        RegistryConfiguration registry;
        switch (discovery) {
            case JDBC -> registry = this.jdbc(scope);
            case ZOOKEEPER -> registry = this.zookeeper(scope);
            case NACOS -> registry = this.nacos(scope);
            default -> throw new IllegalArgumentException("Direct connections do not require registry configuration");
        }
        registry.setManagementToken("matrix-admin");
        registry.setRefreshMillis(60000);
        return registry;
    }

    private JdbcRegistryConfiguration jdbc(String scope) throws Exception {
        // 保持同一条数据库连接，让两个应用各自的存储会话共享真实 H2 数据库。
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:order-matrix-" + scope);
        source.setUser("sa");
        source.setPassword("");
        if (!this.databases.containsKey(scope)) {
            Connection database = source.getConnection();
            this.databases.put(scope, database);

            // 应用准备正式表结构，注册扩展不承担建表或隐式迁移。
            var schema = this.getClass().getResourceAsStream("/META-INF/meteor/schema/h2.sql");
            try (InputStreamReader reader = new InputStreamReader(schema, StandardCharsets.UTF_8)) {
                new JdbcTemplate(database).loadSplitSQL(";", reader);
            }
        }
        return new JdbcRegistryConfiguration().setDataSource(source);
    }

    private ZookeeperRegistryConfiguration zookeeper(String scope) throws Exception {
        if (this.zookeeper == null) {
            this.zookeeper = new TestingServer();
        }
        ZookeeperRegistryConfiguration registry = new ZookeeperRegistryConfiguration();
        registry.setAddress(this.zookeeper.getConnectString());
        registry.setRoot("/meteor-matrix/" + scope);
        return registry;
    }

    private NacosRegistryConfiguration nacos(String scope) throws Exception {
        if (this.nacos == null) {
            this.nacos = new NacosTestServer(this.workDirectory.resolve("nacos"));
            this.nacos.start();
        }
        NacosRegistryConfiguration registry = new NacosRegistryConfiguration();
        registry.setAddress(this.nacos.address());
        registry.setGroup("meteor-matrix-" + scope);
        return registry;
    }

    public void release(String scope) throws Exception {
        // 应用先退出，再关闭本用例的保活连接，独立内存数据库随之释放。
        Connection database = this.databases.remove(scope);
        if (database != null) {
            database.close();
        }
    }

    @Override
    public void close() throws Exception {
        // 关闭本类创建的进程和保活连接；某项失败时仍释放其余资源。
        try (TestingServer zookeeper = this.zookeeper; //
             NacosTestServer nacos = this.nacos) {
            // 正常路径每个用例已经 release；这里也覆盖准备阶段失败留下的连接。
            for (Connection database : this.databases.values()) {
                database.close();
            }
            this.databases.clear();
        }
    }
}

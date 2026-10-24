/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.matrix;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.jdbc.JdbcRegistryConfiguration;
import net.hasor.meteor.registry.nacos.NacosRegistryConfiguration;
import net.hasor.meteor.registry.zookeeper.ZookeeperRegistryConfiguration;
import net.hasor.realmeteor.environment.storage.NacosTestServer;
import net.hasor.realmeteor.environment.storage.JdbcTestServer;
import net.hasor.realmeteor.environment.storage.ZookeeperTestServer;
import org.h2.jdbcx.JdbcDataSource;

/** 一个矩阵测试类持有外部存储生命周期，各用例的配置、服务分组和本地缓存仍相互隔离。 */
public final class OrderRegistryEnvironment implements AutoCloseable {
    private final Path                        workDirectory;
    private final Map<String, JdbcDataSource> databases = new LinkedHashMap<>();
    private       JdbcTestServer              jdbc;
    private       ZookeeperTestServer         zookeeper;
    private       NacosTestServer             nacos;

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
        // 两个应用的连接都指向独立数据库容器，各场景仍使用自己的数据库。
        if (this.jdbc == null) {
            this.jdbc = new JdbcTestServer(this.workDirectory.resolve("h2"));
        }
        JdbcDataSource source = this.databases.get(scope);
        if (source == null) {
            source = this.jdbc.createDatabase("order_matrix_" + scope);
            this.databases.put(scope, source);
        }
        return new JdbcRegistryConfiguration().setDataSource(source);
    }

    private ZookeeperRegistryConfiguration zookeeper(String scope) throws Exception {
        if (this.zookeeper == null) {
            this.zookeeper = new ZookeeperTestServer();
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
        // 应用先退出，再释放数据库；不依赖客户端 JVM 保活连接。
        JdbcDataSource database = this.databases.remove(scope);
        if (database != null) {
            try (Connection connection = database.getConnection()) {
                connection.createStatement().execute("SHUTDOWN");
            }
        }
    }

    @Override
    public void close() throws Exception {
        // 关闭本类创建的注册库容器；某项失败时仍释放其余资源。
        try (ZookeeperTestServer zookeeper = this.zookeeper; //
             NacosTestServer nacos = this.nacos; //
             JdbcTestServer jdbc = this.jdbc) {
            this.databases.clear();
        }
    }
}

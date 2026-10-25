/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.matrix;
import java.nio.file.Path;
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

/** 矩阵类借用 worker 的存储服务，各场景独占数据库、根路径或分组。 */
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
            this.zookeeper = ZookeeperTestServer.shared();
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
        registry.setGroup(this.nacos.group(scope));
        return registry;
    }

    public void release(String scope) throws Exception {
        // 应用先退出，再清理当前范围；一个存储清理失败仍要释放其他存储。
        Exception failure = null;
        if (this.databases.containsKey(scope)) {
            try {
                this.jdbc.releaseDatabase("order_matrix_" + scope);
                this.databases.remove(scope);
            } catch (Exception cleanup) {
                failure = cleanup;
            }
        }
        try {
            if (this.zookeeper != null) {
                this.zookeeper.release("/meteor-matrix/" + scope);
            }
        } catch (Exception cleanup) {
            if (failure == null) {
                failure = cleanup;
            } else {
                failure.addSuppressed(cleanup);
            }
        }
        try {
            if (this.nacos != null) {
                this.nacos.release(scope);
            }
        } catch (Exception cleanup) {
            if (failure == null) {
                failure = cleanup;
            } else {
                failure.addSuppressed(cleanup);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void close() throws Exception {
        // 清理本类持有的全部数据范围，借用的服务仍由 worker 管理。
        try (ZookeeperTestServer zookeeper = this.zookeeper; //
             NacosTestServer nacos = this.nacos; //
             JdbcTestServer jdbc = this.jdbc) {
            this.databases.clear();
        }
    }
}

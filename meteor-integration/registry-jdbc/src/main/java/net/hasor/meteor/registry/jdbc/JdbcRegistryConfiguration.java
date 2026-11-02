/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.jdbc;
import java.util.concurrent.Callable;
import javax.sql.DataSource;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.shared.storage.StorageAccess;

/** JDBC 注册中心配置，使用应用提供的数据源。 */
public final class JdbcRegistryConfiguration extends RegistryConfiguration {
    private DataSource dataSource;
    private long       leaseMillis = 15000;

    /** 使用应用管理的数据源；操作结束时关闭借用的连接，不关闭数据源。 */
    public JdbcRegistryConfiguration setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
        return this;
    }

    /** 设置实例租约，单位毫秒，默认 15000，至少 300。 */
    public JdbcRegistryConfiguration setLeaseMillis(long value) {
        this.leaseMillis = value;
        return this;
    }

    @Override
    public String getProvider() {
        return "jdbc";
    }

    @Override
    protected Callable<StorageAccess> createStorageOpener() {
        if (this.dataSource == null) {
            throw new IllegalArgumentException("JDBC DataSource is required");
        }
        if (this.leaseMillis < 300) {
            throw new IllegalArgumentException("leaseMillis must be at least 300");
        }

        DataSource dataSource = this.dataSource;
        long lease = this.leaseMillis;
        return () -> new JdbcStorage(dataSource, lease);
    }

}

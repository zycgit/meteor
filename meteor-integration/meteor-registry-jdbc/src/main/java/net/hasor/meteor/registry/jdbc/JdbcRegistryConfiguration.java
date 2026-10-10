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
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.admin.RegistryInfo;
import net.hasor.meteor.registry.shared.storage.StorageAccess;

/** JDBC 注册中心配置，直接创建独立存储会话。 */
public final class JdbcRegistryConfiguration extends RegistryConfiguration {
    private DataSource dataSource;
    private String     group       = "METEOR";
    private long       leaseMillis = 15000;

    /** 使用应用管理的数据源；操作结束时关闭借用的连接，不关闭数据源。 */
    public JdbcRegistryConfiguration setDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
        return this;
    }

    /** 设置同一数据库中的注册数据分组，默认 METEOR。 */
    public JdbcRegistryConfiguration setGroup(String value) {
        this.group = value;
        return this;
    }

    /** 设置临时记录租约，单位毫秒，默认 15000，至少 300。 */
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
        if (StringUtils.isBlank(this.group) || this.group.length() > 128) {
            throw new IllegalArgumentException("JDBC group must contain 1 to 128 characters");
        }
        if (this.leaseMillis < 300) {
            throw new IllegalArgumentException("leaseMillis must be at least 300");
        }

        DataSource dataSource = this.dataSource;
        String group = this.group;
        long lease = this.leaseMillis;
        return () -> new JdbcStorage(dataSource, group, lease);
    }

    @Override
    protected RegistryInfo describeStorage() {
        RegistryInfo info = super.describeStorage();
        info.getScope().put("group", this.group);
        return info;
    }
}

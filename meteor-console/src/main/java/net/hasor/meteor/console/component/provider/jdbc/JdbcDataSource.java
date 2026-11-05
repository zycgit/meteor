/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.component.provider.jdbc;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;
import javax.sql.DataSource;
import net.hasor.meteor.console.model.fo.ClusterProfile;

/** 控制台拥有的简单数据源，JDBC 提供者只借用连接；不建立控制台数据库。 */
final class JdbcDataSource implements DataSource {
    private final ClusterProfile profile;
    private       int            timeout = 5;
    private       PrintWriter    writer;

    JdbcDataSource(ClusterProfile profile) {
        this.profile = profile;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return this.getConnection(this.profile.getUsername(), this.profile.getPassword());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Properties properties = new Properties();
        if (username != null) {
            properties.setProperty("user", username);
        }
        if (password != null) {
            properties.setProperty("password", password);
        }

        // 驱动单位不同，连接超时在各驱动支持的参数中设置。
        switch (this.profile.getDatabase()) {
            case "mysql" -> {
                properties.setProperty("connectTimeout", Integer.toString(this.timeout * 1000));
                properties.setProperty("socketTimeout", "10000");
            }
            case "postgresql" -> {
                properties.setProperty("connectTimeout", Integer.toString(this.timeout));
                properties.setProperty("socketTimeout", "10");
            }
            case "sqlserver" -> {
                properties.setProperty("loginTimeout", Integer.toString(this.timeout));
                properties.setProperty("socketTimeout", "10000");
            }
            case "oracle" -> {
                properties.setProperty("oracle.net.CONNECT_TIMEOUT", Integer.toString(this.timeout * 1000));
                properties.setProperty("oracle.jdbc.ReadTimeout", "10000");
            }
            default -> {
            }
        }
        return DriverManager.getConnection(this.profile.getAddress(), properties);
    }

    @Override
    public PrintWriter getLogWriter() {
        return this.writer;
    }

    @Override
    public void setLogWriter(PrintWriter value) {
        this.writer = value;
    }

    @Override
    public void setLoginTimeout(int value) {
        this.timeout = value;
    }

    @Override
    public int getLoginTimeout() {
        return this.timeout;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        if (type.isInstance(this)) {
            return type.cast(this);
        }
        throw new SQLException("Not a wrapper for " + type.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
        return type.isInstance(this);
    }
}
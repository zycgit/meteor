/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.jdbc;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import javax.sql.DataSource;
import net.hasor.cobble.StringUtils;
import net.hasor.dbvisitor.jdbc.core.JdbcTemplate;

final class JdbcTestSupport {
    static String createDatabase() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "", "")) {
            initialize(connection);
        }
        return url;
    }

    static JdbcRegistryConfiguration configuration(String url) {
        JdbcRegistryConfiguration configuration = new JdbcRegistryConfiguration();
        configuration.setDataSource(driverDataSource(url, "", ""));
        return configuration;
    }

    /** 测试数据源直接访问实际 JDBC 驱动，兼容外部数据库和故障探针驱动。 */
    static DataSource driverDataSource(String url, String username, String password) {
        Class<?>[] interfaces = { DataSource.class };
        InvocationHandler handler = (proxy, method, arguments) -> {
            if (StringUtils.equals("getConnection", method.getName())) {
                if (arguments != null && arguments.length == 2) {
                    return DriverManager.getConnection(url, (String) arguments[0], (String) arguments[1]);
                }
                return DriverManager.getConnection(url, username, password);
            }
            throw new UnsupportedOperationException(method.getName());
        };
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), interfaces, handler);
    }

    static void initialize(Connection connection) throws Exception {
        InputStream schema = JdbcTestSupport.class.getResourceAsStream("/META-INF/meteor/schema/h2.sql");
        try (InputStreamReader script = new InputStreamReader(schema, StandardCharsets.UTF_8)) {
            new JdbcTemplate(connection).loadSplitSQL(";", script);
        }
    }

}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.jdbc.scenario.example;
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
import net.hasor.meteor.registry.jdbc.JdbcRegistryConfiguration;

/** 为独立场景准备 H2 表结构及数据源；上下文和会话仍由每个测试自己管理。 */
public final class JdbcTestSupport {
    public static String createDatabase() throws Exception {
        // 每个场景使用独立的内存库，避免并行测试共享持久目录。
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "", "")) {
            initialize(connection);
        }
        return url;
    }

    public static JdbcRegistryConfiguration configuration(String url) {
        JdbcRegistryConfiguration configuration = new JdbcRegistryConfiguration();
        configuration.setDataSource(driverDataSource(url, "", ""));
        return configuration;
    }

    /** 测试数据源直接访问实际 JDBC 驱动，兼容外部数据库和故障探针驱动。 */
    public static DataSource driverDataSource(String url, String username, String password) {
        // 委托给实际驱动，包括测试注入的驱动，不模拟存储行为。
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

    public static void initialize(Connection connection) throws Exception {
        // 使用提供者正式发布的 H2 建表脚本，测试不维护第二份表结构。
        InputStream schema = JdbcTestSupport.class.getResourceAsStream("/META-INF/meteor/schema/h2.sql");
        try (InputStreamReader script = new InputStreamReader(schema, StandardCharsets.UTF_8)) {
            new JdbcTemplate(connection).loadSplitSQL(";", script);
        }
    }

}

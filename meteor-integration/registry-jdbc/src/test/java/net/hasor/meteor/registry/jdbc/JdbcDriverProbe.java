/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.jdbc;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.Properties;
import java.util.UUID;
import net.hasor.cobble.ObjectUtils;
import net.hasor.cobble.StringUtils;

/** 在真实 JDBC 执行前协调并发或注入错误，不替代存储实现。 */
final class JdbcDriverProbe implements AutoCloseable {
    private final    String       url           = "jdbc:meteor-test:" + UUID.randomUUID();
    private final    Driver       driver;
    private volatile Action       beforeExecute = sql -> {
    };
    private volatile SQLException connectionFailure;

    JdbcDriverProbe(String databaseUrl) throws Exception {
        // 保留真实驱动作为对端，只拦截当前测试的专用连接地址。
        Driver delegate = DriverManager.getDriver(databaseUrl);
        Class<?>[] interfaces = { Driver.class };
        InvocationHandler handler = (proxy, method, arguments) -> {
            if (StringUtils.equals("acceptsURL", method.getName())) {
                return ObjectUtils.equals(this.url, arguments[0]);
            }
            if (StringUtils.equals("connect", method.getName())) {
                if (!ObjectUtils.equals(this.url, arguments[0])) {
                    return null;
                }
                // 在申请真实连接之前注入错误，未注入时仍交由实际 JDBC 执行。
                SQLException failure = this.connectionFailure;
                if (failure != null) {
                    throw failure;
                }
                Connection connection = delegate.connect(databaseUrl, (Properties) arguments[1]);
                return this.observe(connection);
            }
            return invoke(delegate, method, arguments);
        };

        // 注册独立驱动，close 时仅解除本测试的注册。
        this.driver = (Driver) Proxy.newProxyInstance(Driver.class.getClassLoader(), interfaces, handler);
        DriverManager.registerDriver(this.driver);
    }

    String url() {
        return this.url;
    }

    void connectionFailure(SQLException failure) {
        this.connectionFailure = failure;
    }

    void beforeExecute(Action action) {
        this.beforeExecute = action;
    }

    private Connection observe(Connection connection) {
        Class<?>[] interfaces = { Connection.class };
        InvocationHandler handler = (proxy, method, arguments) -> {
            // 标准 SQL 存储不能依赖数据库产品识别能力。
            if (StringUtils.equals("getMetaData", method.getName())) {
                throw new SQLFeatureNotSupportedException("Database metadata is unavailable");
            }

            // 只包裹预编译语句，连接的提交、回滚和关闭均保持真实行为。
            Object result = invoke(connection, method, arguments);
            if (StringUtils.equals("prepareStatement", method.getName())) {
                return this.observe((PreparedStatement) result, (String) arguments[0]);
            }
            return result;
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), interfaces, handler);
    }

    private PreparedStatement observe(PreparedStatement statement, String sql) {
        Class<?>[] interfaces = { PreparedStatement.class };
        InvocationHandler handler = (proxy, method, arguments) -> {
            // 并发协调或故障发生在真实语句执行之前，不代替 SQL 执行结果。
            boolean query = StringUtils.equals("executeQuery", method.getName());
            boolean update = StringUtils.equals("executeUpdate", method.getName());
            if (query || update) {
                this.beforeExecute.execute(sql);
            }
            return invoke(statement, method, arguments);
        };
        ClassLoader loader = PreparedStatement.class.getClassLoader();
        return (PreparedStatement) Proxy.newProxyInstance(loader, interfaces, handler);
    }

    private static Object invoke(Object delegate, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(delegate, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    @Override
    public void close() throws Exception {
        DriverManager.deregisterDriver(this.driver);
    }

    @FunctionalInterface
    interface Action {
        void execute(String sql) throws Exception;
    }
}

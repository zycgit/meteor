/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.environment.storage;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.hasor.dbvisitor.jdbc.core.JdbcTemplate;
import org.h2.jdbcx.JdbcDataSource;

/** 借用 Compose 的 H2 TCP 服务，每个场景句柄拥有独立数据库。 */
public final class JdbcTestServer implements AutoCloseable {
    private static final Set<String> ACTIVE_DATABASES = ConcurrentHashMap.newKeySet();
    private static Path sharedHome;
    private static String sharedAddress;
    private static boolean cleanupFailed;

    private final String prefix = "meteor_" + UUID.randomUUID().toString().replace("-", "") + "_";
    private final Set<String> databases = new LinkedHashSet<>();

    public JdbcTestServer(Path workDirectory) throws Exception {
        // 路径只保存场景连接记录，数据库服务由外层 Compose 管理。
        Files.createDirectories(workDirectory);
        startShared();
        Files.writeString(workDirectory.resolve("database-prefix.txt"), this.prefix);
    }

    private static synchronized void startShared() throws Exception {
        if (sharedAddress != null) {
            return;
        }

        // URL 前缀由 Compose 注入；每个句柄补上自己的数据库名称。
        String address = System.getenv("METEOR_TEST_JDBC_ADDRESS");
        if (address == null || !address.startsWith("jdbc:h2:tcp://") || !address.endsWith("/mem:")) {
            throw new IllegalStateException("METEOR_TEST_JDBC_ADDRESS must be an H2 TCP /mem: URL prefix");
        }
        String directory = System.getProperty("meteor.test.container.workDirectory", "build/container-scenarios");
        Path home = Files.createDirectories(Path.of(directory).resolve("registry-services/h2"));
        Files.writeString(home.resolve("lifecycle.properties"), "state=borrowed\naddress=" + address + "\n");
        sharedHome = home;
        Runtime.getRuntime().addShutdownHook(new Thread(JdbcTestServer::releaseShared, "h2-client-release"));
        sharedAddress = address;
    }

    public String url(String database) {
        return source(sharedAddress, this.prefix + database).getURL();
    }

    public JdbcDataSource createDatabase(String database) throws Exception {
        // 正式表结构按场景数据库初始化，两端拿到的是相同独立 JDBC URL。
        JdbcDataSource source = source(sharedAddress, this.prefix + database);
        this.databases.add(database);
        ACTIVE_DATABASES.add(source.getURL());
        try (Connection connection = source.getConnection()) {
            var schema = this.getClass().getResourceAsStream("/META-INF/meteor/schema/h2.sql");
            try (InputStreamReader reader = new InputStreamReader(schema, StandardCharsets.UTF_8)) {
                new JdbcTemplate(connection).loadSplitSQL(";", reader);
            }
        }
        return source;
    }

    public void releaseDatabase(String database) throws Exception {
        if (!this.databases.contains(database)) {
            return;
        }
        // SHUTDOWN 只释放本数据库，不会停止共享 TCP 服务或其他场景的数据。
        try (Connection connection = source(sharedAddress, this.prefix + database).getConnection()) {
            connection.createStatement().execute("SHUTDOWN");
        }
        this.databases.remove(database);
        ACTIVE_DATABASES.remove(this.url(database));
    }

    private static JdbcDataSource source(String address, String database) {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL(address + database + ";DB_CLOSE_DELAY=-1");
        source.setUser("sa");
        source.setPassword("");
        return source;
    }

    @Override
    public void close() throws Exception {
        // 每个数据库都尝试释放，首个失败保留其余清理失败，不能误关借用的服务。
        Exception failure = null;
        for (String database : new ArrayList<>(this.databases)) {
            try {
                this.releaseDatabase(database);
            } catch (Exception cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        if (failure != null) {
            recordCleanup(failure);
            throw failure;
        }
    }

    private static void releaseShared() {
        Exception failure = null;
        if (!ACTIVE_DATABASES.isEmpty()) {
            failure = new IOException("H2 databases were not released: " + ACTIVE_DATABASES);
        }
        recordCleanup(failure);
    }

    private static synchronized void recordCleanup(Exception failure) {
        // 聚合器在 worker 退出后核对状态，不能把 shutdown hook 的异常当作通过。
        try {
            cleanupFailed |= failure != null;
            String state = "state=released\n";
            if (cleanupFailed) {
                state = "state=failed\n";
            }
            if (failure != null) {
                try (PrintWriter error = new PrintWriter( //
                        Files.newBufferedWriter(sharedHome.resolve("cleanup-error.log")))) {
                    failure.printStackTrace(error);
                }
            }
            Files.writeString(sharedHome.resolve("lifecycle.properties"), state);
        } catch (IOException unavailable) {
            throw new IllegalStateException("Cannot record registry service cleanup", unavailable);
        }
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.environment.storage;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import net.hasor.dbvisitor.jdbc.core.JdbcTemplate;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import org.h2.jdbcx.JdbcDataSource;

/** 测试独占的 H2 TCP 容器，数据库和应用处于不同进程及网络端点。 */
public final class JdbcTestServer implements AutoCloseable {
    private final Process process;

    public JdbcTestServer(Path workDirectory) throws Exception {
        // H2 数据只属于本场景的容器，不使用宿主内存数据库或数据库服务。
        Path home = Files.createDirectories(workDirectory);
        Path output = home.resolve("startup.log");
        ProcessBuilder command = new ProcessBuilder( //
                "java", "-cp", "/opt/meteor-peer/h2/h2.jar", "org.h2.tools.Server", //
                "-tcp", "-tcpPort", "9092", "-tcpAllowOthers", "-ifNotExists");
        command.directory(home.toFile());
        command.redirectErrorStream(true).redirectOutput(output.toFile());
        this.process = ContainerProcess.start("h2", command);

        try {
            // 以 JDBC 握手确认数据库就绪，TCP 端口可达不代表服务已经完成初始化。
            JdbcDataSource readiness = new JdbcDataSource();
            readiness.setURL(this.url("meteor_readiness"));
            readiness.setUser("sa");
            readiness.setPassword("");
            readiness.setLoginTimeout(1);
            SQLException pending = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (this.process.isAlive() && System.nanoTime() < deadline) {
                try (Connection connection = readiness.getConnection()) {
                    if (connection.isValid(1)) {
                        return;
                    }
                } catch (SQLException notReady) {
                    pending = notReady;
                }
                TimeUnit.MILLISECONDS.sleep(50);
            }
            throw new IOException("H2 container did not start:\n" + Files.readString(output), pending);
        } catch (Exception failure) {
            try {
                this.close();
            } catch (Exception cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public String url(String database) {
        return "jdbc:h2:tcp://" + ContainerProcess.host(this.process) + ":9092/mem:" + database + ";DB_CLOSE_DELAY=-1";
    }

    public JdbcDataSource createDatabase(String database) throws Exception {
        // 每个调用创建一份独立正式表结构；连接关闭后数据库仍在容器中存活。
        JdbcDataSource source = new JdbcDataSource();
        source.setURL(this.url(database));
        source.setUser("sa");
        source.setPassword("");
        try (Connection connection = source.getConnection()) {
            var schema = this.getClass().getResourceAsStream("/META-INF/meteor/schema/h2.sql");
            try (InputStreamReader reader = new InputStreamReader(schema, StandardCharsets.UTF_8)) {
                new JdbcTemplate(connection).loadSplitSQL(";", reader);
            }
        }
        return source;
    }

    @Override
    public void close() throws Exception {
        this.process.destroy();
        if (!this.process.waitFor(10, TimeUnit.SECONDS)) {
            this.process.destroyForcibly();
            if (!this.process.waitFor(5, TimeUnit.SECONDS)) {
                throw new IOException("H2 container did not stop");
            }
        }
    }
}

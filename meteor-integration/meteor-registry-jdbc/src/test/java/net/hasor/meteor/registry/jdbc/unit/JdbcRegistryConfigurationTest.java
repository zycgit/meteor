/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.jdbc.unit;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ConcurrentModificationException;
import java.util.UUID;
import net.hasor.dbvisitor.jdbc.core.JdbcTemplate;
import net.hasor.meteor.registry.jdbc.JdbcRegistryConfiguration;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.EntMetadataData;
import net.hasor.meteor.registry.shared.storage.domain.EntityRecord;
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.Test;
import static net.hasor.meteor.registry.jdbc.scenario.example.JdbcTestSupport.*;
import static org.junit.Assert.*;

public class JdbcRegistryConfigurationTest {
    @Test
    public void dataSourceIsRequired() {
        // 未配置数据源时，不尝试连接任何默认数据库。
        JdbcRegistryConfiguration configuration = new JdbcRegistryConfiguration();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, configuration::openStorage);
        assertEquals("JDBC DataSource is required", failure.getMessage());
    }

    @Test
    public void invalidLeaseFailsBeforeAcquiringConnection() {
        // 无效租约应先于数据库连接失败被检查出来。
        JdbcRegistryConfiguration configuration = new JdbcRegistryConfiguration();
        configuration.setDataSource(driverDataSource("jdbc:missing-driver:test", "", ""));
        configuration.setLeaseMillis(299);
        assertThrows(IllegalArgumentException.class, configuration::openStorage);
    }

    @Test
    public void singleConnectionPoolSupportsStorageOperations() throws Exception {
        // 池中只有一个连接，存储不能额外占用保活连接。
        JdbcConnectionPool pool = JdbcConnectionPool.create(createDatabase(), "", "");
        pool.setMaxConnections(1);
        pool.setLoginTimeout(1);
        JdbcRegistryConfiguration configuration = new JdbcRegistryConfiguration();
        configuration.setDataSource(pool);
        try {
            try (StorageAccess storage = configuration.openStorage()) {
                assertEquals(0, pool.getActiveConnections());

                // 连续写入和读取均归还借用的连接，持久记录的版本不变。
                String serviceId = "pooled";
                storage.saveMetadata(null, serviceId, metadata());
                EntityRecord<EntMetadataData> saved = storage.getMetadata(null, serviceId);
                assertEquals(0, pool.getActiveConnections());
                EntityRecord<EntMetadataData> fetched = storage.getMetadata(null, serviceId);
                assertEquals(saved.getRevision(), fetched.getRevision());
            }
            assertEquals(0, pool.getActiveConnections());
        } finally {
            pool.dispose();
        }
    }

    @Test
    public void casConflictReturnsConnectionToPool() throws Exception {
        // 用单连接池准备一条已有版本的元信息。
        JdbcConnectionPool pool = JdbcConnectionPool.create(createDatabase(), "", "");
        pool.setMaxConnections(1);
        pool.setLoginTimeout(1);
        JdbcRegistryConfiguration configuration = new JdbcRegistryConfiguration();
        configuration.setDataSource(pool);
        try {
            try (StorageAccess storage = configuration.openStorage()) {
                String serviceId = "pooled";
                storage.saveMetadata(null, serviceId, metadata());

                // 无版本写入产生 CAS 冲突，回滚后同一个连接仍能读取原记录。
                EntityRecord<EntMetadataData> withoutRevision = metadata();
                assertThrows(ConcurrentModificationException.class, () -> {
                    storage.saveMetadata(null, serviceId, withoutRevision);
                });
                assertEquals(0, pool.getActiveConnections());
                assertNotNull(storage.getMetadata(null, serviceId));
            }
            assertEquals(0, pool.getActiveConnections());
        } finally {
            pool.dispose();
        }
    }

    @Test
    public void closingStorageLeavesDataSourceApplicationOwned() throws Exception {
        // 存储只借用应用管理的数据源。
        JdbcConnectionPool pool = JdbcConnectionPool.create(createDatabase(), "", "");
        pool.setMaxConnections(1);
        pool.setLoginTimeout(1);
        JdbcRegistryConfiguration configuration = new JdbcRegistryConfiguration();
        configuration.setDataSource(pool);
        String serviceId = "pooled";
        try {
            try (StorageAccess storage = configuration.openStorage()) {
                storage.saveMetadata(null, serviceId, metadata());
            }

            // 会话退出归还连接但不销毁数据源，新会话仍能读取持久记录。
            assertEquals(0, pool.getActiveConnections());
            try (StorageAccess reopened = configuration.openStorage()) {
                assertNotNull(reopened.getMetadata(null, serviceId));
            }
            assertEquals(0, pool.getActiveConnections());
        } finally {
            pool.dispose();
        }
    }

    @Test
    public void startupSqlFailureReturnsConnectionAndAllowsRecovery() throws Exception {
        // 使用尚未建表的数据库，初始化存储应报告原始 SQL 错误。
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        JdbcConnectionPool pool = JdbcConnectionPool.create(url, "", "");
        pool.setMaxConnections(1);
        pool.setLoginTimeout(1);
        JdbcRegistryConfiguration configuration = new JdbcRegistryConfiguration();
        configuration.setDataSource(pool);
        try {
            assertThrows(SQLException.class, configuration::openStorage);
            assertEquals(0, pool.getActiveConnections());

            // 失败连接已归还，补齐表结构后可以重新打开并使用存储。
            try (Connection connection = pool.getConnection()) {
                initialize(connection);
            }
            try (StorageAccess storage = configuration.openStorage()) {
                storage.saveMetadata(null, "after-initialize", metadata());
                assertNotNull(storage.getMetadata(null, "after-initialize"));
            }
            assertEquals(0, pool.getActiveConnections());
        } finally {
            pool.dispose();
        }
    }

    @Test
    public void readSqlFailureReturnsConnectionAndAllowsRecovery() throws Exception {
        // 会话启动后隐藏治理表，模拟读取期间的数据库结构故障。
        JdbcConnectionPool pool = JdbcConnectionPool.create(createDatabase(), "", "");
        pool.setMaxConnections(1);
        pool.setLoginTimeout(1);
        JdbcRegistryConfiguration configuration = new JdbcRegistryConfiguration();
        configuration.setDataSource(pool);
        try {
            try (StorageAccess storage = configuration.openStorage()) {
                String serviceId = "recoverable";
                storage.saveMetadata(null, serviceId, metadata());
                try (Connection connection = pool.getConnection()) {
                    new JdbcTemplate(connection).execute("ALTER TABLE meteor_governance RENAME TO hidden_records");
                }

                // 读取错误可感知，借用连接必须释放，随后恢复表结构。
                try {
                    assertThrows(SQLException.class, () -> storage.getMetadata(null, serviceId));
                    assertEquals(0, pool.getActiveConnections());
                } finally {
                    try (Connection connection = pool.getConnection()) {
                        new JdbcTemplate(connection).execute("ALTER TABLE hidden_records RENAME TO meteor_governance");
                    }
                }

                // 原会话可再次读取，错误没有污染连接或持久记录。
                assertNotNull(storage.getMetadata(null, serviceId));
            }
            assertEquals(0, pool.getActiveConnections());
        } finally {
            pool.dispose();
        }
    }

    @Test
    public void writeSqlFailureReturnsConnectionAndAllowsRecovery() throws Exception {
        // 会话启动后隐藏治理表，模拟写入期间的数据库结构故障。
        JdbcConnectionPool pool = JdbcConnectionPool.create(createDatabase(), "", "");
        pool.setMaxConnections(1);
        pool.setLoginTimeout(1);
        JdbcRegistryConfiguration configuration = new JdbcRegistryConfiguration();
        configuration.setDataSource(pool);
        try {
            try (StorageAccess storage = configuration.openStorage()) {
                String serviceId = "recoverable";
                try (Connection connection = pool.getConnection()) {
                    new JdbcTemplate(connection).execute("ALTER TABLE meteor_governance RENAME TO hidden_records");
                }

                // 写入错误可感知，失败事务归还连接，随后恢复表结构。
                try {
                    assertThrows(SQLException.class, () -> storage.saveMetadata(null, serviceId, metadata()));
                    assertEquals(0, pool.getActiveConnections());
                } finally {
                    try (Connection connection = pool.getConnection()) {
                        new JdbcTemplate(connection).execute("ALTER TABLE hidden_records RENAME TO meteor_governance");
                    }
                }

                // 同一会话可再次写入并读到结果。
                storage.saveMetadata(null, serviceId, metadata());
                assertNotNull(storage.getMetadata(null, serviceId));
            }
            assertEquals(0, pool.getActiveConnections());
        } finally {
            pool.dispose();
        }
    }

    @Test
    public void startupConnectionTimeoutIsPreservedAndAllowsRecovery() throws Exception {
        // 数据源获取连接时的超时直接上抛，不被改写或吞掉。
        String databaseUrl = createDatabase();
        try (JdbcDriverProbe probe = new JdbcDriverProbe(databaseUrl)) {
            JdbcRegistryConfiguration configuration = configuration(probe.url());
            SQLTimeoutException timeout = new SQLTimeoutException("Pool exhausted");
            probe.connectionFailure(timeout);
            try {
                assertSame(timeout, assertThrows(SQLTimeoutException.class, configuration::openStorage));
            } finally {
                probe.connectionFailure(null);
            }

            // 连接恢复后重新打开会话，正常执行读写。
            try (StorageAccess storage = configuration.openStorage()) {
                storage.saveMetadata(null, "after-timeout", metadata());
                assertNotNull(storage.getMetadata(null, "after-timeout"));
            }
        }
    }

    @Test
    public void runtimeConnectionFailureIsPreservedAndAllowsRecovery() throws Exception {
        // 先打开可用会话，再注入普通连接错误。
        String databaseUrl = createDatabase();
        try (JdbcDriverProbe probe = new JdbcDriverProbe(databaseUrl)) {
            JdbcRegistryConfiguration configuration = configuration(probe.url());
            try (StorageAccess storage = configuration.openStorage()) {
                String serviceId = "data-source";
                SQLException failure = new SQLException("Connection unavailable", "08006");
                probe.connectionFailure(failure);
                try {
                    SQLException actual = assertThrows(SQLException.class, () -> storage.getMetadata(null, serviceId));
                    assertSame(failure, actual);
                } finally {
                    probe.connectionFailure(null);
                }

                // 连接恢复后原会话仍能写入和读取。
                storage.saveMetadata(null, serviceId, metadata());
                assertNotNull(storage.getMetadata(null, serviceId));
            }
        }
    }

    @Test
    public void failedCloseReturnsBorrowedConnectionWithoutClosingDataSource() throws Exception {
        // 用单连接池启动会话，隐藏租约表以模拟关闭时的 SQL 错误。
        JdbcConnectionPool pool = JdbcConnectionPool.create(createDatabase(), "", "");
        pool.setMaxConnections(1);
        pool.setLoginTimeout(1);
        JdbcRegistryConfiguration configuration = new JdbcRegistryConfiguration();
        configuration.setDataSource(pool);
        try {
            try (StorageAccess storage = configuration.openStorage()) {
                try (Connection connection = pool.getConnection()) {
                    new JdbcTemplate(connection).execute("ALTER TABLE meteor_sessions RENAME TO hidden_sessions");
                }

                // 关闭失败仍归还连接，并在退出测试会话前恢复表结构。
                try {
                    assertThrows(SQLException.class, storage::close);
                    assertEquals(0, pool.getActiveConnections());
                } finally {
                    try (Connection connection = pool.getConnection()) {
                        new JdbcTemplate(connection).execute("ALTER TABLE hidden_sessions RENAME TO meteor_sessions");
                    }
                }
            }

            // 新会话仍可借用该单连接池，正常退出也不遗留占用。
            try (StorageAccess reopened = configuration.openStorage()) {
                reopened.saveMetadata(null, "after-failed-close", metadata());
                assertNotNull(reopened.getMetadata(null, "after-failed-close"));
            }
            assertEquals(0, pool.getActiveConnections());
        } finally {
            pool.dispose();
        }
    }

    private static EntityRecord<EntMetadataData> metadata() {
        EntityRecord<EntMetadataData> result = new EntityRecord<>();
        result.setData(new EntMetadataData());
        return result;
    }
}

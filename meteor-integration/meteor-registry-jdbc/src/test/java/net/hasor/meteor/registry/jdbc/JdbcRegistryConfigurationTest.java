/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.jdbc;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.Arrays;
import java.util.ConcurrentModificationException;
import java.util.UUID;
import net.hasor.dbvisitor.jdbc.core.JdbcTemplate;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.EntityRecord;
import net.hasor.meteor.registry.shared.storage.domain.EntityRef;
import net.hasor.meteor.registry.shared.storage.domain.EntityType;
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.Test;
import static net.hasor.meteor.registry.jdbc.JdbcTestSupport.*;
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
    public void invalidScopeAndLeaseFailBeforeAcquiringConnection() {
        // 无效配置在申请连接前报错。
        JdbcRegistryConfiguration configuration = new JdbcRegistryConfiguration();
        configuration.setDataSource(driverDataSource("jdbc:missing-driver:test", "", ""));
        for (String group : Arrays.asList(null, "", " ", "a".repeat(129))) {
            configuration.setGroup(group);
            assertThrows(IllegalArgumentException.class, configuration::openStorage);
        }
        configuration.setGroup("METEOR").setLeaseMillis(299);
        assertThrows(IllegalArgumentException.class, configuration::openStorage);
    }

    @Test
    public void singleConnectionPoolSupportsStorageAndRemainsApplicationOwned() throws Exception {
        // 池中只有一个连接，存储不能额外占用保活连接。
        JdbcConnectionPool pool = JdbcConnectionPool.create(createDatabase(), "", "");
        pool.setMaxConnections(1);
        pool.setLoginTimeout(1);
        JdbcRegistryConfiguration configuration = new JdbcRegistryConfiguration();
        configuration.setDataSource(pool);
        EntityRef ref = new EntityRef(EntityType.SERVICE, "pooled");
        try {
            try (StorageAccess storage = configuration.openStorage()) {
                assertEquals(0, pool.getActiveConnections());
                EntityRecord saved = storage.saveEntity(new EntityRecord(ref));
                assertEquals(0, pool.getActiveConnections());
                assertEquals(saved.getRevision(), storage.getEntity(ref).getRevision());

                // CAS 冲突完成回滚并归还连接，下一次读取仍可使用同一个池。
                assertThrows(ConcurrentModificationException.class, () -> storage.saveEntity(new EntityRecord(ref)));
                assertEquals(0, pool.getActiveConnections());
                assertNotNull(storage.getEntity(ref));
            }

            // 存储退出不销毁应用数据源，后续会话继续读取持久记录。
            assertEquals(0, pool.getActiveConnections());
            try (StorageAccess reopened = configuration.openStorage()) {
                assertNotNull(reopened.getEntity(ref));
            }
            assertEquals(0, pool.getActiveConnections());
        } finally {
            pool.dispose();
        }
    }

    @Test
    public void sqlFailuresReleaseConnectionsDuringStartupAndOperations() throws Exception {
        // 未建表时初始化失败，已借到的连接必须归还。
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        JdbcConnectionPool pool = JdbcConnectionPool.create(url, "", "");
        pool.setMaxConnections(1);
        pool.setLoginTimeout(1);
        JdbcRegistryConfiguration configuration = new JdbcRegistryConfiguration();
        configuration.setDataSource(pool);
        try {
            assertThrows(SQLException.class, configuration::openStorage);
            assertEquals(0, pool.getActiveConnections());
            try (Connection connection = pool.getConnection()) {
                initialize(connection);
            }

            // 建表后可重新打开；读写失败也归还连接，恢复后继续工作。
            try (StorageAccess storage = configuration.openStorage()) {
                EntityRef ref = new EntityRef(EntityType.SERVICE, "recoverable");
                try (Connection connection = pool.getConnection()) {
                    new JdbcTemplate(connection).execute("ALTER TABLE meteor_records RENAME TO hidden_records");
                }
                try {
                    assertThrows(SQLException.class, () -> storage.getEntity(ref));
                    assertEquals(0, pool.getActiveConnections());
                    assertThrows(SQLException.class, () -> storage.saveEntity(new EntityRecord(ref)));
                    assertEquals(0, pool.getActiveConnections());
                } finally {
                    try (Connection connection = pool.getConnection()) {
                        new JdbcTemplate(connection).execute("ALTER TABLE hidden_records RENAME TO meteor_records");
                    }
                }
                storage.saveEntity(new EntityRecord(ref));
                assertNotNull(storage.getEntity(ref));
            }
            assertEquals(0, pool.getActiveConnections());
        } finally {
            pool.dispose();
        }
    }

    @Test
    public void dataSourcePreservesConnectionFailuresAndRecovers() throws Exception {
        // 数据源获取连接时的超时直接上抛，不被改写或吞掉。
        try (JdbcDriverProbe probe = new JdbcDriverProbe(createDatabase())) {
            JdbcRegistryConfiguration configuration = configuration(probe.url());
            SQLTimeoutException timeout = new SQLTimeoutException("Pool exhausted");
            probe.connectionFailure(timeout);
            assertSame(timeout, assertThrows(SQLTimeoutException.class, configuration::openStorage));
            probe.connectionFailure(null);

            try (StorageAccess storage = configuration.openStorage()) {
                // 已打开会话后发生的普通连接错误同样可感知，恢复后仍可使用。
                EntityRef ref = new EntityRef(EntityType.SERVICE, "data-source");
                SQLException failure = new SQLException("Connection unavailable", "08006");
                probe.connectionFailure(failure);
                try {
                    SQLException actual = assertThrows(SQLException.class, () -> storage.getEntity(ref));
                    assertSame(failure, actual);
                } finally {
                    probe.connectionFailure(null);
                }
                storage.saveEntity(new EntityRecord(ref));
                assertNotNull(storage.getEntity(ref));
            }
        }
    }

    @Test
    public void failedCloseReturnsBorrowedConnectionWithoutClosingDataSource() throws Exception {
        // 关闭期间发生 SQL 错误，仍应归还连接；数据源仍属于应用。
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
                assertNotNull(reopened.saveEntity(new EntityRecord(EntityType.SERVICE, "after-failed-close")));
            }
            assertEquals(0, pool.getActiveConnections());
        } finally {
            pool.dispose();
        }
    }

}

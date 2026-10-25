/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.environment.storage;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import com.alibaba.nacos.maintainer.client.config.ConfigMaintainerFactory;
import com.alibaba.nacos.maintainer.client.config.ConfigMaintainerService;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 测试编排复用真实服务，场景句柄只释放自己的数据范围。 */
public class RegistryServiceReuseTest {
    @Rule
    public final TemporaryFolder data = new TemporaryFolder();

    @Test(timeout = 180000)
    public void nacosHandlesShareServiceAndKeepOtherGroupAfterOneCloses() throws Exception {
        // 两个句柄共享真实 Nacos，相同配置名分别写入各自分组。
        try (NacosTestServer first = new NacosTestServer(this.data.newFolder("nacos-first").toPath()); //
                NacosTestServer second = new NacosTestServer(this.data.newFolder("nacos-second").toPath())) {
            first.start();
            second.start();
            assertEquals(first.address(), second.address());
            String firstGroup = first.group();
            String secondGroup = second.group();
            assertNotEquals(firstGroup, secondGroup);
            ConfigMaintainerService configs = ConfigMaintainerFactory.createConfigMaintainerService(first.address());
            try {
                assertTrue(configs.publishConfig("reuse-probe", firstGroup, "first"));
                assertTrue(configs.publishConfig("reuse-probe", secondGroup, "second"));
                assertEquals("first", configs.getConfig("reuse-probe", firstGroup).getContent());
                assertEquals("second", configs.getConfig("reuse-probe", secondGroup).getContent());

                // 关闭第一个句柄只清理它的分组，第二个分组仍可读写。
                first.close();
                long removedConfigurations = configs.listConfigs("", firstGroup, "public").getTotalCount();
                assertEquals(0, removedConfigurations);
                assertEquals("second", configs.getConfig("reuse-probe", secondGroup).getContent());
                assertTrue(configs.publishConfig("reuse-probe", secondGroup, "still-open"));
                assertEquals("still-open", configs.getConfig("reuse-probe", secondGroup).getContent());
            } finally {
                configs.shutdown();
            }
        }
    }

    @Test(timeout = 120000)
    public void zookeeperHandlesShareServiceAndKeepOtherRootAfterOneCloses() throws Exception {
        // 相同 ZooKeeper 地址使用不同 chroot，同名节点互不覆盖。
        try (ZookeeperTestServer first = ZookeeperTestServer.shared(); //
                ZookeeperTestServer second = ZookeeperTestServer.shared()) {
            String firstConnection = first.getConnectString();
            String secondConnection = second.getConnectString();
            String firstAddress = firstConnection.substring(0, firstConnection.indexOf('/'));
            String secondAddress = secondConnection.substring(0, secondConnection.indexOf('/'));
            assertEquals(firstAddress, secondAddress);
            assertNotEquals(firstConnection, secondConnection);
            try (CuratorFramework firstClient = CuratorFrameworkFactory //
                    .newClient(firstConnection, new RetryOneTime(100)); //
                    CuratorFramework secondClient = CuratorFrameworkFactory //
                            .newClient(secondConnection, new RetryOneTime(100))) {
                firstClient.start();
                secondClient.start();
                assertTrue(firstClient.blockUntilConnected(10, TimeUnit.SECONDS));
                assertTrue(secondClient.blockUntilConnected(10, TimeUnit.SECONDS));
                byte[] firstData = "first".getBytes(StandardCharsets.UTF_8);
                byte[] secondData = "second".getBytes(StandardCharsets.UTF_8);
                firstClient.create().forPath("/reuse-probe", firstData);
                secondClient.create().forPath("/reuse-probe", secondData);
                assertArrayEquals(firstData, firstClient.getData().forPath("/reuse-probe"));
                assertArrayEquals(secondData, secondClient.getData().forPath("/reuse-probe"));

                // 清理第一个 chroot 不关闭借用服务，也不删除第二个 chroot。
                first.close();
                assertNull(firstClient.checkExists().forPath("/reuse-probe"));
                assertArrayEquals(secondData, secondClient.getData().forPath("/reuse-probe"));
                byte[] updatedData = "still-open".getBytes(StandardCharsets.UTF_8);
                secondClient.setData().forPath("/reuse-probe", updatedData);
                assertArrayEquals(updatedData, secondClient.getData().forPath("/reuse-probe"));
            }
        }
    }

    @Test(timeout = 120000)
    public void jdbcHandlesShareServiceAndKeepOtherDatabaseAfterOneCloses() throws Exception {
        // 相同数据库名按句柄划分前缀，真实 H2 TCP 服务地址保持一致。
        try (JdbcTestServer first = new JdbcTestServer(this.data.newFolder("jdbc-first").toPath()); //
                JdbcTestServer second = new JdbcTestServer(this.data.newFolder("jdbc-second").toPath())) {
            JdbcDataSource firstSource = first.createDatabase("reuse_probe");
            JdbcDataSource secondSource = second.createDatabase("reuse_probe");
            String firstUrl = firstSource.getURL();
            String secondUrl = secondSource.getURL();
            String firstAddress = firstUrl.substring(0, firstUrl.indexOf("/mem:"));
            String secondAddress = secondUrl.substring(0, secondUrl.indexOf("/mem:"));
            assertEquals(firstAddress, secondAddress);
            assertNotEquals(firstUrl, secondUrl);
            try (Connection firstConnection = firstSource.getConnection(); //
                    Statement firstStatement = firstConnection.createStatement(); //
                    Connection secondConnection = secondSource.getConnection(); //
                    Statement secondStatement = secondConnection.createStatement()) {
                firstStatement.execute("CREATE TABLE reuse_probe (marker VARCHAR(64))");
                secondStatement.execute("CREATE TABLE reuse_probe (marker VARCHAR(64))");
                firstStatement.executeUpdate("INSERT INTO reuse_probe VALUES ('first')");
                secondStatement.executeUpdate("INSERT INTO reuse_probe VALUES ('second')");
            }
            assertEquals("first", marker(firstSource));
            assertEquals("second", marker(secondSource));

            // 关闭只销毁第一个数据库，第二个数据库仍保留数据且能继续写入。
            first.close();
            assertThrows(SQLException.class, () -> {
                try (Connection connection = DriverManager.getConnection(firstUrl + ";IFEXISTS=TRUE", "sa", "")) {
                    connection.isValid(1);
                }
            });
            assertEquals("second", marker(secondSource));
            try (Connection connection = secondSource.getConnection(); //
                    Statement statement = connection.createStatement()) {
                assertEquals(1, statement.executeUpdate("UPDATE reuse_probe SET marker = 'still-open'"));
            }
            assertEquals("still-open", marker(secondSource));
        }
    }

    @Test(timeout = 120000)
    public void nativeZookeeperLeaseRemovesBusinessDataAndKeepsBorrowedServiceAvailable() throws Exception {
        // 原生 SDK 场景通过租约独占固定服务，业务数据属于当前租约。
        try (ZookeeperTestServer first = ZookeeperTestServer.dedicated(); //
                CuratorFramework observer = CuratorFrameworkFactory //
                        .newClient(first.getConnectString(), new RetryOneTime(100))) {
            observer.start();
            assertTrue(observer.blockUntilConnected(10, TimeUnit.SECONDS));
            observer.create().forPath("/native-reuse-probe", "first".getBytes(StandardCharsets.UTF_8));
            String address = first.getConnectString();

            // 释放租约删除业务数据，下一场景仍借用同一服务，协调锁节点保留。
            first.close();
            try (ZookeeperTestServer second = ZookeeperTestServer.dedicated()) {
                assertEquals(address, second.getConnectString());
                assertNull(observer.checkExists().forPath("/native-reuse-probe"));
                assertTrue(observer.checkExists().forPath("/meteor-test-locks/native-registry") != null);
                observer.create().forPath("/native-reuse-probe", "second".getBytes(StandardCharsets.UTF_8));
            }
            assertNull(observer.checkExists().forPath("/native-reuse-probe"));
        }
    }

    private static String marker(JdbcDataSource source) throws SQLException {
        try (Connection connection = source.getConnection(); //
                Statement statement = connection.createStatement(); //
                ResultSet result = statement.executeQuery("SELECT marker FROM reuse_probe")) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }
}

/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.zookeeper.unit;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.EntServiceCatalogData;
import net.hasor.meteor.registry.zookeeper.ZookeeperRegistryConfiguration;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingServer;
import org.junit.Test;
import static net.hasor.meteor.registry.zookeeper.unit.ZookeeperRecords.instance;
import static net.hasor.meteor.registry.zookeeper.unit.ZookeeperRecords.service;
import static org.junit.Assert.*;

/** 配置校验和连接生命周期；每个无效选项独立报告。 */
public class ZookeeperRegistryConfigurationTest {
    @Test
    public void missingPasswordFailsBeforeConnecting() {
        // 只配置本场景的不完整凭据，不建立任何外部连接。
        ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
        configuration.setAddress("zk.example:2181");
        configuration.setUsername("user");

        // 正式存储入口必须在连接前拒绝该凭据。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, configuration::openStorage);
        assertEquals("Registry authentication requires both username and password", failure.getMessage());
    }

    @Test
    public void missingUsernameFailsBeforeConnecting() {
        // 只配置本场景的不完整凭据，不建立任何外部连接。
        ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
        configuration.setAddress("zk.example:2181");
        configuration.setPassword("secret");

        // 正式存储入口必须在连接前拒绝该凭据。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, configuration::openStorage);
        assertEquals("Registry authentication requires both username and password", failure.getMessage());
    }

    @Test
    public void emptyUsernameFailsBeforeConnecting() {
        // 只配置本场景的不完整凭据，不建立任何外部连接。
        ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
        configuration.setAddress("zk.example:2181");
        configuration.setUsername("");
        configuration.setPassword("secret");

        // 正式存储入口必须在连接前拒绝该凭据。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, configuration::openStorage);
        assertEquals("Registry authentication requires both username and password", failure.getMessage());
    }

    @Test
    public void blankUsernameFailsBeforeConnecting() {
        // 只配置本场景的不完整凭据，不建立任何外部连接。
        ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
        configuration.setAddress("zk.example:2181");
        configuration.setUsername(" ");
        configuration.setPassword("secret");

        // 正式存储入口必须在连接前拒绝该凭据。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, configuration::openStorage);
        assertEquals("Registry authentication requires both username and password", failure.getMessage());
    }

    @Test
    public void missingAddressFailsBeforeConnecting() {
        // 地址不能退回默认本机地址。
        ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
        configuration.setAddress(null);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, configuration::openStorage);
        assertEquals("Registry address is required", failure.getMessage());
    }

    @Test
    public void emptyAddressFailsBeforeConnecting() {
        // 地址不能退回默认本机地址。
        ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
        configuration.setAddress("");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, configuration::openStorage);
        assertEquals("Registry address is required", failure.getMessage());
    }

    @Test
    public void blankAddressFailsBeforeConnecting() {
        // 地址不能退回默认本机地址。
        ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
        configuration.setAddress(" ");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, configuration::openStorage);
        assertEquals("Registry address is required", failure.getMessage());
    }

    @Test
    public void whitespaceAddressFailsBeforeConnecting() {
        // 地址不能退回默认本机地址。
        ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
        configuration.setAddress("\t\n");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, configuration::openStorage);
        assertEquals("Registry address is required", failure.getMessage());
    }

    @Test
    public void relativeRootFailsBeforeConnecting() {
        // 除本场景校验的字段外，其余连接选项保持合法。
        ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
        configuration.setAddress("zk.example:2181");
        configuration.setRoot("relative");

        assertThrows(IllegalArgumentException.class, configuration::openStorage);
    }

    @Test
    public void zeroConnectionTimeoutFailsBeforeConnecting() {
        // 除本场景校验的字段外，其余连接选项保持合法。
        ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
        configuration.setAddress("zk.example:2181");
        configuration.setTimeoutMillis(0);

        assertThrows(IllegalArgumentException.class, configuration::openStorage);
    }

    @Test
    public void zeroSessionTimeoutFailsBeforeConnecting() {
        // 除本场景校验的字段外，其余连接选项保持合法。
        ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
        configuration.setAddress("zk.example:2181");
        configuration.setSessionTimeoutMillis(0);

        assertThrows(IllegalArgumentException.class, configuration::openStorage);
    }

    @Test
    public void colonInDigestUsernameFailsBeforeConnecting() {
        // 除本场景校验的字段外，其余连接选项保持合法。
        ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
        configuration.setAddress("zk.example:2181");
        configuration.setUsername("bad:user");
        configuration.setPassword("secret");

        assertThrows(IllegalArgumentException.class, configuration::openStorage);
    }

    @Test
    public void customRootContainsBusinessDirectoriesDirectly() throws Exception {
        // 显式配置的根路径就是实际业务目录的前缀。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            configuration.setRoot("/custom/registry");
            ExponentialBackoffRetry retry = new ExponentialBackoffRetry(50, 3);
            try (StorageAccess storage = configuration.openStorage(); //
                 CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), retry)) {
                client.start();
                assertTrue(client.blockUntilConnected(5, TimeUnit.SECONDS));

                // 服务落到自定义前缀，既不创建默认根，也不增加版本目录。
                EntServiceCatalogData service = new EntServiceCatalogData();
                service.setServiceId("echo");
                storage.saveService(service);
                assertNotNull(storage.getService("echo"));
                assertNotNull(client.checkExists().forPath("/custom/registry/services/ZWNobw/info"));
                Set<String> directories = Set.copyOf(client.getChildren().forPath("/custom/registry"));
                assertEquals(Set.of("applications", "instances", "services"), directories);
                assertNull(client.checkExists().forPath("/meteor"));
            }
        }
    }

    @Test
    public void slashRootIsUsable() throws Exception {
        // 根路径也必须能通过正式配置创建目录并正常登记应用实例。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            configuration.setRoot("/");
            ExponentialBackoffRetry retry = new ExponentialBackoffRetry(50, 3);
            try (StorageAccess storage = configuration.openStorage(); //
                 CuratorFramework client = CuratorFrameworkFactory.newClient(server.getConnectString(), retry)) {
                client.start();
                assertTrue(client.blockUntilConnected(5, TimeUnit.SECONDS));

                // 实例与服务使用单斜线绝对路径，登记正文和反向目录都可读取。
                storage.registerInstance(instance("one", "orders", "east"));
                storage.registerService("one", service("echo", MetServiceType.Provider));
                assertNotNull(storage.getInstance("one").getRuntime());
                assertNotNull(storage.getService("echo"));
                assertNotNull(storage.getMember("echo", MetServiceType.Provider, "one"));
                assertNotNull(client.checkExists().forPath("/applications/b3JkZXJz/info"));
                assertNotNull(client.checkExists().forPath("/instances/b25l/runtime"));
                assertNotNull(client.checkExists().forPath("/services/ZWNobw/info"));
                assertNull(client.checkExists().forPath("/registry-v2"));
            }
        }
    }

    @Test
    public void configuredRootsKeepDirectoriesIsolated() throws Exception {
        // 同一服务端上的两个根路径表示独立注册中心。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration root = new ZookeeperRegistryConfiguration();
            root.setAddress(server.getConnectString());
            root.setRoot("/");
            ZookeeperRegistryConfiguration nested = new ZookeeperRegistryConfiguration();
            nested.setAddress(server.getConnectString());
            try (StorageAccess first = root.openStorage(); //
                 StorageAccess other = nested.openStorage()) {
                EntServiceCatalogData service = new EntServiceCatalogData();
                service.setServiceId("echo");
                first.saveService(service);

                // 在一个根路径写入的服务不能泄漏到另一个根路径。
                assertNotNull(first.getService("echo"));
                assertNull(other.getService("echo"));
            }
        }
    }

    @Test
    public void sessionsOpenedFromOneConfigurationHaveIndependentLifecycles() throws Exception {
        // 配置可复用，实际存储会话各自创建和关闭。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            try (StorageAccess first = configuration.openStorage(); //
                 StorageAccess other = configuration.openStorage()) {
                EntServiceCatalogData service = new EntServiceCatalogData();
                service.setServiceId("echo");
                first.saveService(service);
                assertNotNull(other.getService("echo"));

                // 关闭第一个会话不影响另一个会话读取同一持久记录。
                first.close();
                assertNotNull(other.getService("echo"));
            }
        }
    }

    @Test(timeout = 15000)
    public void unavailableServerFailsWithinConfiguredTimeoutAndCanRecover() throws Exception {
        // 关闭实际服务端，超时必须通过正式存储入口反馈。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            configuration.setTimeoutMillis(300);
            server.stop();

            IllegalStateException failure = assertThrows(IllegalStateException.class, configuration::openStorage);
            assertEquals("ZooKeeper connection timed out", failure.getMessage());

            // 恢复后复用同一配置，验证失败的连接不污染后续会话。
            server.restart();
            configuration.setTimeoutMillis(5000);
            try (StorageAccess storage = configuration.openStorage()) {
                EntServiceCatalogData service = new EntServiceCatalogData();
                service.setServiceId("recovered");
                storage.saveService(service);
                assertNotNull(storage.getService("recovered"));
            }
        }
    }

}

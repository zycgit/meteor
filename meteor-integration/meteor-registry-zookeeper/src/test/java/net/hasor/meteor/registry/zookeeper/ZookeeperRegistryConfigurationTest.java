/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.zookeeper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.EntityRecord;
import net.hasor.meteor.registry.shared.storage.domain.EntityRef;
import net.hasor.meteor.registry.shared.storage.domain.EntityType;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingServer;
import org.apache.zookeeper.ZooDefs;
import org.junit.Test;
import static org.junit.Assert.*;

public class ZookeeperRegistryConfigurationTest {
    @Test
    public void incompleteStorageCredentialsFailBeforeConnecting() {
        // 配置不完整时直接拒绝，不尝试建立外部连接。
        ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
        configuration.setAddress("zk.example:2181");
        String[][] invalid = { { "user", null }, { null, "secret" }, { "", "secret" }, { " ", "secret" } };
        for (String[] credentials : invalid) {
            configuration.setUsername(credentials[0]);
            configuration.setPassword(credentials[1]);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, configuration::openStorage);
            assertEquals("Registry authentication requires both username and password", failure.getMessage());
        }
    }

    @Test
    public void addressIsRequiredBeforeConnecting() {
        // 地址没有本机默认值，空值与空白均在连接前报错。
        ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
        for (String address : Arrays.asList(null, "", " ", "\t\n")) {
            configuration.setAddress(address);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, configuration::openStorage);
            assertEquals("Registry address is required", failure.getMessage());
        }
    }

    @Test
    public void configurationAuthenticatesAgainstProtectedRoot() throws Exception {
        // 先建立需要认证的根路径，再通过扩展验证管理及存储访问。
        byte[] credentials = "registry-user:storage-secret".getBytes(StandardCharsets.UTF_8);
        CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder();
        builder.retryPolicy(new ExponentialBackoffRetry(100, 3));
        builder.authorization("digest", credentials);
        try (TestingServer server = new TestingServer(); //
             CuratorFramework seed = builder.connectString(server.getConnectString()).build()) {
            seed.start();
            assertTrue(seed.blockUntilConnected(5, TimeUnit.SECONDS));
            seed.create().withACL(ZooDefs.Ids.CREATOR_ALL_ACL).forPath("/protected");

            ZookeeperRegistryConfiguration bootstrap = new ZookeeperRegistryConfiguration();
            bootstrap.setAddress(server.getConnectString());
            bootstrap.setUsername("registry-user");
            bootstrap.setPassword("storage-secret");
            bootstrap.setManagementToken("api-secret");
            bootstrap.setRoot("/protected");
            bootstrap.setTimeoutMillis(5000);
            bootstrap.setSessionTimeoutMillis(15000);
            try (MetContext management = new MetConfiguration().addExtension(bootstrap).buildContext()) {
                RegistryAdmin admin = management.getExtension(RegistryAdmin.class);
                assertEquals("zookeeper", admin.getClusterAdmin().info().getProvider());
                assertTrue(admin.getClusterAdmin().status().isReachable());
                admin.getClusterAdmin().putMeta("owner", "platform");
            }
            // 同一配置打开独立存储会话，共享根路径但不共享生命周期。
            EntityRef service = new EntityRef(EntityType.SERVICE, "echo");
            try (StorageAccess storage = bootstrap.openStorage(); //
                 StorageAccess other = bootstrap.openStorage()) {
                storage.saveEntity(new EntityRecord(service));
                assertNotNull(other.getEntity(service));
                storage.close();
                assertNotNull(other.getEntity(service));
            }
            assertTrue(seed.getChildren().forPath("/protected").size() > 0);
        }
    }

    @Test
    public void validatesTimeoutPathAndDigestBeforeConnecting() {
        // 路径、超时和 digest 用户名都必须在连接前完成校验。
        ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
        configuration.setAddress("zk.example:2181");
        configuration.setRoot("relative");
        assertThrows(IllegalArgumentException.class, configuration::openStorage);
        configuration.setRoot("/meteor").setTimeoutMillis(0);
        assertThrows(IllegalArgumentException.class, configuration::openStorage);
        configuration.setTimeoutMillis(5000).setSessionTimeoutMillis(0);
        assertThrows(IllegalArgumentException.class, configuration::openStorage);
        configuration.setSessionTimeoutMillis(15000);
        configuration.setUsername("bad:user").setPassword("secret");
        assertThrows(IllegalArgumentException.class, configuration::openStorage);
    }

    @Test
    public void slashRootIsUsableAndNamespacesStayIsolated() throws Exception {
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration root = new ZookeeperRegistryConfiguration();
            root.setAddress(server.getConnectString());
            root.setRoot("/");
            ZookeeperRegistryConfiguration nested = new ZookeeperRegistryConfiguration();
            nested.setAddress(server.getConnectString());

            // 根路径配置合法，不能在连接成功后又被内部实现拒绝。
            try (StorageAccess first = root.openStorage(); //
                 StorageAccess other = nested.openStorage()) {
                EntityRef ref = new EntityRef(EntityType.SERVICE, "echo");
                first.saveEntity(new EntityRecord(ref));
                assertNotNull(first.getEntity(ref));
                assertNull(other.getEntity(ref));
            }
        }
    }

    @Test(timeout = 15000)
    public void unavailableServerFailsWithinConfiguredTimeoutAndCanRecover() throws Exception {
        // 关闭实际服务端，超时通过正式存储入口反馈，不能无限等待连接。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
            configuration.setAddress(server.getConnectString());
            configuration.setTimeoutMillis(300);
            server.stop();
            IllegalStateException failure = assertThrows(IllegalStateException.class, configuration::openStorage);
            assertEquals("ZooKeeper connection timed out", failure.getMessage());

            // 服务恢复后使用同一配置创建新会话，失败的连接不妨碍后续访问。
            server.restart();
            configuration.setTimeoutMillis(5000);
            try (StorageAccess storage = configuration.openStorage()) {
                EntityRef service = new EntityRef(EntityType.SERVICE, "recovered");
                storage.saveEntity(new EntityRecord(service));
                assertNotNull(storage.getEntity(service));
            }
        }
    }

}

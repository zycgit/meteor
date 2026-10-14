/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.zookeeper.scenario;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.EntMetadataData;
import net.hasor.meteor.registry.shared.storage.domain.EntityRecord;
import net.hasor.meteor.registry.zookeeper.ZookeeperRegistryConfiguration;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingServer;
import org.apache.zookeeper.ZooDefs;
import org.junit.Test;
import static org.junit.Assert.*;

/** 用户配置 digest 认证，通过扩展读取和治理受保护的注册中心。 */
public class ZookeeperAuthenticationTest {
    @Test
    public void extensionAuthenticatesAgainstProtectedRoot() throws Exception {
        // 外部管理员先建立需要认证的根路径，作为本场景的服务端条件。
        byte[] credentials = "registry-user:storage-secret".getBytes(StandardCharsets.UTF_8);
        CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder();
        builder.retryPolicy(new ExponentialBackoffRetry(100, 3));
        builder.authorization("digest", credentials);
        try (TestingServer server = new TestingServer(); //
             CuratorFramework seed = builder.connectString(server.getConnectString()).build()) {
            seed.start();
            assertTrue(seed.blockUntilConnected(5, TimeUnit.SECONDS));
            seed.create().withACL(ZooDefs.Ids.CREATOR_ALL_ACL).forPath("/protected");

            // Meteor 只需声明具体存储配置和管理凭证，再从上下文取得管理能力。
            ZookeeperRegistryConfiguration registry = new ZookeeperRegistryConfiguration();
            registry.setAddress(server.getConnectString());
            registry.setUsername("registry-user");
            registry.setPassword("storage-secret");
            registry.setManagementToken("api-secret");
            registry.setRoot("/protected");
            registry.setTimeoutMillis(5000);
            registry.setSessionTimeoutMillis(15000);
            MetConfiguration configuration = new MetConfiguration().addExtension(registry);
            try (MetContext context = configuration.buildContext()) {
                RegistryAdmin admin = context.getExtension(RegistryAdmin.class);
                assertEquals("zookeeper", admin.getClusterAdmin().info().getProvider());
                assertTrue(admin.getClusterAdmin().status().isReachable());
                admin.getClusterAdmin().putMeta("owner", "platform");
            }

            // 重新打开已认证存储，确认管理操作确实写入受保护的根路径。
            try (StorageAccess storage = registry.openStorage()) {
                EntityRecord<EntMetadataData> metadata = storage.getMetadata(null, null);
                assertEquals("platform", metadata.getData().getMetadata().get("owner"));
            }
            assertFalse(seed.getChildren().forPath("/protected").isEmpty());
        }
    }
}

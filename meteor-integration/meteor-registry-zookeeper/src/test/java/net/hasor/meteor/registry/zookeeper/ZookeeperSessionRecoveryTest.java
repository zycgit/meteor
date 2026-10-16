/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.zookeeper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.EntInstanceRuntimeData;
import net.hasor.meteor.registry.shared.storage.domain.EntServiceRuntimeData;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.KillSession;
import org.apache.curator.test.TestingServer;
import org.junit.Test;
import static net.hasor.meteor.registry.zookeeper.ZookeeperRecords.instance;
import static net.hasor.meteor.registry.zookeeper.ZookeeperRecords.service;
import static org.junit.Assert.*;

/** 原生会话过期后的临时登记恢复边界。 */
public class ZookeeperSessionRecoveryTest {
    @Test
    public void expiredNativeSessionRepublishesOnlyCurrentRegistrations() throws Exception {
        // 使用真实 SDK 会话和较短租期，失效时由 ZooKeeper 删除临时节点。
        CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder();
        builder.sessionTimeoutMs(3000);
        builder.connectionTimeoutMs(3000);
        builder.retryPolicy(new ExponentialBackoffRetry(50, 3));
        try (TestingServer server = new TestingServer(); //
             CuratorFramework client = builder.connectString(server.getConnectString()).build()) {
            client.start();
            assertTrue(client.blockUntilConnected(5, TimeUnit.SECONDS));
            try (StorageAccess storage = new ZookeeperRegistry(client, "/meteor")) {
                EntInstanceRuntimeData instance = instance("one", "orders", "east");
                storage.registerInstance(instance);
                EntServiceRuntimeData service = service("echo", MetServiceType.Provider);
                storage.registerService("one", service);
                CountDownLatch reconnected = new CountDownLatch(1);
                client.getConnectionStateListenable().addListener((connection, state) -> {
                    if (state == ConnectionState.RECONNECTED) {
                        reconnected.countDown();
                    }
                });

                // 注入失效事件后，既等待 SDK 重连，也等待服务端旧会话真正到期。
                KillSession.kill(client.getZookeeperClient().getZooKeeper());
                assertTrue(reconnected.await(10, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (storage.getInstanceService("one", "echo") != null && System.nanoTime() < deadline) {
                    TimeUnit.MILLISECONDS.sleep(25);
                }
                assertNull(storage.getInstanceService("one", "echo"));
                assertNotNull(storage.getService("echo"));

                // 仅重报实例不能从持久服务目录复活旧成员，必须显式重报当前服务。
                storage.registerInstance(instance);
                assertNotNull(storage.getInstance("one").getRuntime());
                assertNull(storage.getMember("echo", MetServiceType.Provider, "one"));
                storage.registerService("one", service);
                assertNotNull(storage.getMember("echo", MetServiceType.Provider, "one"));
            }
        }
    }

}

/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.zookeeper.unit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import net.hasor.meteor.registry.zookeeper.ZookeeperRegistryConfiguration;
import net.hasor.meteor.registry.zookeeper.ZookeeperTestAccess;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.test.TestingServer;
import org.junit.Test;
import static net.hasor.meteor.registry.zookeeper.unit.ZookeeperRecords.instance;
import static net.hasor.meteor.registry.zookeeper.unit.ZookeeperRecords.service;
import static org.junit.Assert.*;

/** 原生订阅的作用范围、取消、会话退出和失败恢复。 */
public class ZookeeperStorageWatchTest {
    @Test
    public void ruleCreationNotifiesSelectedScope() throws Exception {
        // 订阅尚未配置规则的实例服务范围，并等待初始快照。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = configuration(server.getConnectString());
            try (StorageAccess writer = configuration.openStorage(); //
                 StorageAccess reader = configuration.openStorage()) {
                writer.registerInstance(instance("one", "orders", "east"));
                writer.registerService("one", service("echo", MetServiceType.Provider));
                AtomicInteger events = new AtomicInteger();
                reader.watchConfiguration("one", "echo", events::incrementAndGet);
                await(() -> events.get() == 1);

                // 创建该范围的规则应产生一次变更通知。
                writer.saveRule("one", "echo", RuleKind.SERVICE_ROUTE, rule());
                await(() -> events.get() == 2);
                assertNotNull(reader.getRule("one", "echo", RuleKind.SERVICE_ROUTE));
            }
        }
    }

    @Test
    public void ruleDeletionNotifiesSelectedScope() throws Exception {
        // 先保存规则，初次订阅读到的是有规则的快照。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = configuration(server.getConnectString());
            try (StorageAccess writer = configuration.openStorage(); //
                 StorageAccess reader = configuration.openStorage()) {
                writer.registerInstance(instance("one", "orders", "east"));
                writer.registerService("one", service("echo", MetServiceType.Provider));
                writer.saveRule("one", "echo", RuleKind.SERVICE_ROUTE, rule());
                AtomicInteger events = new AtomicInteger();
                reader.watchConfiguration("one", "echo", events::incrementAndGet);
                await(() -> events.get() == 1);

                // 删除使用已保存版本，订阅者随后看到空规则。
                EntityRecord<EntRuleConfigurationData> saved = writer.getRule("one", "echo", RuleKind.SERVICE_ROUTE);
                writer.deleteRule("one", "echo", RuleKind.SERVICE_ROUTE, saved.getRevision());
                await(() -> events.get() == 2);
                assertNull(reader.getRule("one", "echo", RuleKind.SERVICE_ROUTE));
            }
        }
    }

    @Test
    public void otherInstanceRuleDoesNotNotifySelectedScope() throws Exception {
        // 两个实例的同名服务有各自独立的治理范围。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = configuration(server.getConnectString());
            try (StorageAccess writer = configuration.openStorage(); //
                 StorageAccess reader = configuration.openStorage()) {
                writer.registerInstance(instance("one", "orders", "east"));
                writer.registerService("one", service("echo", MetServiceType.Provider));
                writer.registerInstance(instance("other", "orders", "east"));
                writer.registerService("other", service("echo", MetServiceType.Provider));
                AtomicInteger events = new AtomicInteger();
                reader.watchConfiguration("one", "echo", events::incrementAndGet);
                await(() -> events.get() == 1);

                writer.saveRule("other", "echo", RuleKind.SERVICE_ROUTE, rule());

                // 这里观察一段时间内没有通知，不把固定休眠当成完成信号。
                TimeUnit.MILLISECONDS.sleep(350);
                assertEquals(1, events.get());
                assertNull(reader.getRule("one", "echo", RuleKind.SERVICE_ROUTE));
                assertNotNull(reader.getRule("other", "echo", RuleKind.SERVICE_ROUTE));
            }
        }
    }

    @Test
    public void cancelledSubscriptionStopsDeliveringChanges() throws Exception {
        // 先接收到初始快照，再取消同一个订阅。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = configuration(server.getConnectString());
            try (StorageAccess writer = configuration.openStorage(); //
                 StorageAccess reader = configuration.openStorage()) {
                AtomicInteger events = new AtomicInteger();
                Cancellable watch = reader.watchConfiguration(null, null, events::incrementAndGet);
                await(() -> events.get() == 1);
                assertTrue(watch.cancel());
                assertFalse(watch.cancel());

                // 取消后保存新正文，完整观察窗口内不再投递事件。
                EntityRecord<EntConfigurationData> value = new EntityRecord<>();
                value.setData(new EntConfigurationData());
                writer.saveConfiguration(null, null, value);
                TimeUnit.MILLISECONDS.sleep(200);
                assertEquals(1, events.get());
            }
        }
    }

    @Test
    public void closedStorageRejectsNewSubscription() throws Exception {
        // 已关闭的存储不能创建新 watch 或悄悄恢复连接。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = configuration(server.getConnectString());
            try (StorageAccess storage = configuration.openStorage()) {
                storage.close();

                assertThrows(IllegalStateException.class, () -> storage.watchProviders("echo", () -> {
                }));
            }
        }
    }

    @Test
    public void providerSubscriptionObservesSessionClose() throws Exception {
        // 查询会话持续订阅，登记会话负责发布并退出。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = configuration(server.getConnectString());
            try (StorageAccess writer = configuration.openStorage(); //
                 StorageAccess reader = configuration.openStorage()) {
                AtomicInteger events = new AtomicInteger();
                reader.watchProviders("echo", events::incrementAndGet);
                await(() -> events.get() == 1);
                writer.registerInstance(instance("one", "orders", "east"));
                writer.registerService("one", service("echo", MetServiceType.Provider));
                await(() -> events.get() == 2);

                // 会话退出导致原生临时节点消失，持久实例档案仍可查询。
                writer.close();
                await(() -> events.get() == 3);
                assertNull(reader.getMember("echo", MetServiceType.Provider, "one"));
                assertNotNull(reader.getInstance("one").getInstance());
            }
        }
    }

    @Test
    public void identicalRegistrationDoesNotRepeatProviderNotification() throws Exception {
        // 订阅已有提供者，初次通知之后保持同一份正文。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = configuration(server.getConnectString());
            try (StorageAccess writer = configuration.openStorage(); //
                 StorageAccess reader = configuration.openStorage()) {
                writer.registerInstance(instance("one", "orders", "east"));
                EntServiceRuntimeData service = service("A", MetServiceType.Provider, "rsf://127.0.0.1:8000");
                writer.registerService("one", service);
                AtomicInteger notices = new AtomicInteger();
                CountDownLatch first = new CountDownLatch(1);
                reader.watchProviders("A", () -> {
                    notices.incrementAndGet();
                    first.countDown();
                });
                assertTrue(first.await(3, TimeUnit.SECONDS));

                // 重报相同实例和服务，观察多个检查周期内没有自身刷新循环。
                writer.registerInstance(instance("one", "orders", "east"));
                writer.registerService("one", service);
                TimeUnit.MILLISECONDS.sleep(350);
                assertEquals(1, notices.get());
            }
        }
    }

    @Test
    public void callbackFailureIsRetried() throws Exception {
        // 第一次处理初始通知失败，故障只来自订阅回调。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = configuration(server.getConnectString());
            try (StorageAccess reader = configuration.openStorage()) {
                AtomicInteger attempts = new AtomicInteger();
                CountDownLatch delivered = new CountDownLatch(1);
                reader.watchProviders("echo", () -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("callback failed");
                    }
                    delivered.countDown();
                });

                // 无需新增数据变更，失败的回调也应得到再次投递。
                assertTrue(delivered.await(5, TimeUnit.SECONDS));
                assertEquals(2, attempts.get());
            }
        }
    }

    @Test
    public void reconnectNotifiesEvenWhenDataDidNotChange() throws Exception {
        // 持有真实 SDK 连接只为观察断连，存储行为仍交给正式提供者实现。
        CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder();
        builder.retryPolicy(new ExponentialBackoffRetry(50, 3));
        builder.sessionTimeoutMs(10000);
        builder.connectionTimeoutMs(2000);
        try (TestingServer server = new TestingServer(); //
             CuratorFramework client = builder.connectString(server.getConnectString()).build()) {
            client.start();
            assertTrue(client.blockUntilConnected(5, TimeUnit.SECONDS));
            try (StorageAccess reader = ZookeeperTestAccess.open(client, "/reconnect")) {
                AtomicInteger events = new AtomicInteger();
                reader.watchProviders("echo", events::incrementAndGet);
                await(() -> events.get() == 1);
                CountDownLatch disconnected = new CountDownLatch(1);
                client.getConnectionStateListenable().addListener((connection, state) -> {
                    boolean suspended = state == ConnectionState.SUSPENDED;
                    boolean lost = state == ConnectionState.LOST;
                    if (suspended || lost) {
                        disconnected.countDown();
                    }
                });

                // 停止服务端并等待可观察的断连事件，不能靠固定休眠猜测状态。
                server.stop();
                assertTrue(disconnected.await(10, TimeUnit.SECONDS));
                assertEquals(1, events.get());

                // 数据未变，重新连接本身仍须通知订阅者恢复读取。
                server.restart();
                await(() -> events.get() >= 2);
            }
        }
    }

    @Test
    public void registrationFieldChangeDoesNotNotifyGovernanceSubscription() throws Exception {
        // 同一详情同时包含登记字段和规则控制，治理订阅只比较控制字段。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = configuration(server.getConnectString());
            try (StorageAccess storage = configuration.openStorage()) {
                EntInstanceRuntimeData instance = instance("one", "orders", "east");
                storage.registerInstance(instance);
                AtomicInteger events = new AtomicInteger();
                storage.watchConfiguration("one", null, events::incrementAndGet);
                await(() -> events.get() == 1);

                // 更新普通登记字段后，在观察窗口内不能产生治理刷新循环。
                instance.setUnitName("west");
                storage.registerInstance(instance);
                TimeUnit.MILLISECONDS.sleep(350);
                assertEquals(1, events.get());
            }
        }
    }

    @Test
    public void providerReferenceNotifiesWhenInstanceIdentityChanges() throws Exception {
        // 提供者目录只保存实例 ID 引用，订阅仍须感知关联实例身份变化。
        try (TestingServer server = new TestingServer()) {
            ZookeeperRegistryConfiguration configuration = configuration(server.getConnectString());
            try (StorageAccess storage = configuration.openStorage()) {
                EntInstanceRuntimeData instance = instance("one", "orders", "east");
                storage.registerInstance(instance);
                storage.registerService("one", service("echo", MetServiceType.Provider));
                AtomicInteger events = new AtomicInteger();
                storage.watchProviders("echo", events::incrementAndGet);
                await(() -> events.get() == 1);

                // 身份更新推进引用版本，查询在主详情处组装最新字段。
                instance.setUnitName("west");
                storage.registerInstance(instance);
                await(() -> events.get() == 2);
                String unit = storage.getMember("echo", MetServiceType.Provider, "one").getInstance().getUnitName();
                assertEquals("west", unit);
            }
        }
    }

    private static ZookeeperRegistryConfiguration configuration(String address) {
        ZookeeperRegistryConfiguration configuration = new ZookeeperRegistryConfiguration();
        configuration.setAddress(address);
        return configuration;
    }

    private static EntityRecord<EntRuleConfigurationData> rule() {
        EntRuleConfigurationData body = new EntRuleConfigurationData();
        body.setRule("<route><return value='addresses'/></route>");
        EntityRecord<EntRuleConfigurationData> record = new EntityRecord<>();
        record.setData(body);
        return record;
    }

    private static void await(BooleanSupplier condition) throws Exception {
        // 每次轮询重新读取可观察结果，限定订阅收敛时间。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertTrue("ZooKeeper watch did not converge", condition.getAsBoolean());
    }

}

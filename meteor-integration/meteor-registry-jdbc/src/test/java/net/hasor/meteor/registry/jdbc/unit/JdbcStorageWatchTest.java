/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.jdbc.unit;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.jdbc.JdbcRegistryConfiguration;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.*;
import org.junit.Test;
import static net.hasor.meteor.registry.jdbc.scenario.example.JdbcTestSupport.configuration;
import static net.hasor.meteor.registry.jdbc.scenario.example.JdbcTestSupport.createDatabase;
import static net.hasor.meteor.registry.jdbc.unit.JdbcStorageData.*;
import static org.junit.Assert.*;

public class JdbcStorageWatchTest {
    @Test
    public void configurationWatchIgnoresOtherInstanceChanges() throws Exception {
        // 只订阅 one 实例的 echo 配置。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess writer = configuration.openStorage(); //
             StorageAccess reader = configuration.openStorage()) {
            writer.registerInstance(instance());
            writer.registerService("one", service());
            writer.registerInstance(instance("other", "orders", "east"));
            writer.registerService("other", service());
            AtomicInteger notifications = new AtomicInteger();
            reader.watchConfiguration("one", "echo", notifications::incrementAndGet);
            await(() -> notifications.get() == 1);

            // 其他实例不产生通知，目标实例的修改仍正常到达。
            writer.saveMetadata("other", "echo", metadata());
            Thread.sleep(450);
            assertEquals(1, notifications.get());
            writer.saveMetadata("one", "echo", metadata());
            await(() -> notifications.get() == 2);
        }
    }

    @Test
    public void deletingWatchedConfigurationNotifiesSubscriber() throws Exception {
        // 先保存配置，再监听该实例与服务范围。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess writer = configuration.openStorage(); //
             StorageAccess reader = configuration.openStorage()) {
            writer.registerInstance(instance());
            writer.registerService("one", service());
            writer.saveMetadata("one", "echo", metadata());
            AtomicInteger notifications = new AtomicInteger();
            reader.watchConfiguration("one", "echo", notifications::incrementAndGet);
            await(() -> notifications.get() == 1);

            // 删除同样属于配置变化，不能因结果为空而遗漏通知。
            EntityRecord<EntMetadataData> saved = writer.getMetadata("one", "echo");
            writer.deleteMetadata("one", "echo", saved.getRevision());
            await(() -> notifications.get() == 2);
        }
    }

    @Test
    public void cancelledConfigurationWatchStopsNotifications() throws Exception {
        // 建立订阅并等待初次回调，避免把初始化回调误判为取消后通知。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess writer = configuration.openStorage(); //
             StorageAccess reader = configuration.openStorage()) {
            writer.registerInstance(instance());
            writer.registerService("one", service());
            AtomicInteger notifications = new AtomicInteger();
            Cancellable watch = reader.watchConfiguration("one", "echo", notifications::incrementAndGet);
            await(() -> notifications.get() == 1);

            // 取消幂等，后续真实写入不再发送回调。
            assertTrue(watch.cancel());
            assertFalse(watch.cancel());
            writer.saveMetadata("one", "echo", metadata());
            Thread.sleep(450);
            assertEquals(1, notifications.get());
        }
    }

    @Test
    public void providerDepartureNotifiesWithoutConfigurationWrite() throws Exception {
        // 只监听提供者目录，不修改任何治理配置。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess writer = configuration.openStorage(); //
             StorageAccess reader = configuration.openStorage()) {
            writer.registerInstance(instance());
            writer.registerService("one", service());
            AtomicInteger notifications = new AtomicInteger();
            reader.watchProviders("echo", notifications::incrementAndGet);
            await(() -> notifications.get() == 1);

            // 发布会话退出时收到成员变化，服务档案仍可查询。
            writer.close();
            await(() -> notifications.get() == 2);
            assertNull(reader.getInstance("one").getRuntime());
            assertNotNull(reader.getService("echo"));
        }
    }

    @Test
    public void identicalRegistrationDoesNotRepeatProviderNotification() throws Exception {
        // 先完成登记，再观察完全相同的实例与服务重报。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess writer = configuration.openStorage(); //
             StorageAccess reader = configuration.openStorage()) {
            writer.registerInstance(instance());
            EntServiceRuntimeData service = service();
            writer.registerService("one", service);
            AtomicInteger notices = new AtomicInteger();
            CountDownLatch initial = new CountDownLatch(1);
            Cancellable subscription = reader.watchProviders("echo", () -> {
                notices.incrementAndGet();
                initial.countDown();
            });
            assertTrue(initial.await(3, TimeUnit.SECONDS));

            // 等待多个检查周期，确认无变化重报不会形成自身刷新循环。
            writer.registerInstance(instance());
            writer.registerService("one", service);
            Thread.sleep(350);
            assertEquals(1, notices.get());
            assertTrue(subscription.cancel());
            assertFalse(subscription.cancel());
        }
    }

    @Test
    public void instanceRefreshNotifiesMembersWithoutRegisteringServicesAgain() throws Exception {
        // 先登记服务，订阅端只观察后续实例正文变化。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess writer = configuration.openStorage(); //
             StorageAccess reader = configuration.openStorage()) {
            writer.registerInstance(instance("one", "orders", "east"));
            writer.registerService("one", service());
            AtomicInteger notifications = new AtomicInteger();
            reader.watchProviders("echo", notifications::incrementAndGet);
            await(() -> notifications.get() == 1);

            // 仅刷新实例身份、单元与监听地址，不重新上报服务关系。
            EntInstanceRuntimeData refreshed = instance("one", "payments", "west");
            refreshed.setIpAddress("127.0.0.2");
            refreshed.setProcessId(456L);
            refreshed.setListenAddresses(Map.of("rsf", "rsf://127.0.0.2:9000"));
            writer.registerInstance(refreshed);
            await(() -> notifications.get() == 2);

            // 点查及分页都立即关联新身份，服务自己的端点声明继续保留。
            RefServiceRelationData member = reader.getMember("echo", MetServiceType.Provider, "one");
            EntInstanceRuntimeData identity = member.getInstance();
            assertEquals("payments", identity.getApplicationName());
            assertEquals("west", identity.getUnitName());
            assertEquals("127.0.0.2", identity.getIpAddress());
            assertEquals(Long.valueOf(456), identity.getProcessId());
            assertEquals(refreshed.getListenAddresses(), identity.getListenAddresses());
            assertArrayEquals(new String[] { "rsf://127.0.0.1:8000" }, member.getService().getAddresses());
            DirectoryQuery query = new DirectoryQuery();
            query.setUnitName("west");
            query.setField("application");
            query.setSearch("PAYMENTS");
            RecordPage<RefServiceRelationData> selected = reader.members("echo", MetServiceType.Provider, query);
            assertEquals(1, selected.getItems().size());
            assertEquals("127.0.0.2", selected.getItems().get(0).getInstance().getIpAddress());
            assertArrayEquals(new String[] { "west" }, reader.serviceUnits("echo"));
            query.setUnitName("east");
            assertTrue(reader.members("echo", MetServiceType.Provider, query).getItems().isEmpty());
        }
    }

    @Test
    public void runtimeAddressRefreshNotifiesWithUnchangedInstanceIdentity() throws Exception {
        // 目录身份保持不变，仅运行时监听地址发生变化。
        JdbcRegistryConfiguration configuration = configuration(createDatabase());
        try (StorageAccess writer = configuration.openStorage(); //
             StorageAccess reader = configuration.openStorage()) {
            EntInstanceRuntimeData registered = instance();
            registered.setListenAddresses(Map.of("rsf", "rsf://127.0.0.1:8000"));
            writer.registerInstance(registered);
            writer.registerService("one", service());
            AtomicInteger notifications = new AtomicInteger();
            reader.watchProviders("echo", notifications::incrementAndGet);
            await(() -> notifications.get() == 1);

            // 不触碰服务登记，运行正文的新版本也必须驱动提供者订阅。
            registered.setListenAddresses(Map.of("rsf", "rsf://127.0.0.1:9000"));
            writer.registerInstance(registered);
            await(() -> notifications.get() == 2);
            RefServiceRelationData member = reader.getMember("echo", MetServiceType.Provider, "one");
            assertEquals(registered.getListenAddresses(), member.getInstance().getListenAddresses());
            assertEquals("127.0.0.1", member.getInstance().getIpAddress());
            assertEquals(Long.valueOf(123), member.getInstance().getProcessId());
        }
    }

    @Test
    public void readFailureIsNotAnEmptySnapshotAndRecoveryNotifies() throws Exception {
        // 真实 JDBC 连接作为委托，只在读取期间注入连接故障。
        try (JdbcDriverProbe probe = new JdbcDriverProbe(createDatabase())) {
            JdbcRegistryConfiguration configuration = configuration(probe.url());
            try (StorageAccess reader = configuration.openStorage()) {
                AtomicInteger notifications = new AtomicInteger();
                reader.watchProviders("echo", notifications::incrementAndGet);
                await(() -> notifications.get() == 1);

                // 错误不能变成空快照事件，连接恢复后订阅继续通知。
                probe.connectionFailure(new SQLException("connection lost"));
                try {
                    Thread.sleep(450);
                    assertEquals(1, notifications.get());
                } finally {
                    probe.connectionFailure(null);
                }
                await(() -> notifications.get() == 2);
            }
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        // 每轮重新查询可观察条件，超时报告具体的订阅失败。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue("Storage notification did not arrive", condition.getAsBoolean());
    }
}

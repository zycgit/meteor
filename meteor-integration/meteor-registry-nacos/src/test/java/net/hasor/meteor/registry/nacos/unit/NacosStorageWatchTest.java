/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.nacos.unit;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.EntConfigurationData;
import net.hasor.meteor.registry.shared.storage.domain.EntInstanceRuntimeData;
import net.hasor.meteor.registry.shared.storage.domain.EntServiceRuntimeData;
import net.hasor.meteor.registry.shared.storage.domain.EntityRecord;
import org.junit.Test;
import static org.junit.Assert.*;

public class NacosStorageWatchTest {
    private static void await(BooleanSupplier condition) throws Exception {
        // 按可观察状态有界等待，不把固定休眠当作订阅完成信号。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue("Nacos subscription did not converge", condition.getAsBoolean());
    }

    @Test
    public void configNotificationOnlyRefreshesSelectedGovernanceScope() throws Exception {
        // 订阅单个实例服务的治理范围，并等待初始快照。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess writer = sdk.configuration().openStorage(); //
             StorageAccess reader = sdk.configuration().openStorage()) {
            for (String id : new String[] { "one", "other" }) {
                EntInstanceRuntimeData instance = new EntInstanceRuntimeData();
                instance.setInstanceId(id);
                instance.setApplicationName("orders");
                instance.setIpAddress("127.0.0.1");
                writer.registerInstance(instance);
                EntServiceRuntimeData service = new EntServiceRuntimeData();
                service.setServiceId("echo");
                service.setRole(MetServiceType.Provider);
                writer.registerService(id, service);
            }
            AtomicInteger events = new AtomicInteger();
            reader.watchConfiguration("one", "echo", events::incrementAndGet);
            await(() -> events.get() == 1);

            // 相邻范围的写入不产生通知，目标范围只产生一次变化通知。
            EntityRecord<EntConfigurationData> value = new EntityRecord<>();
            value.setData(new EntConfigurationData());
            writer.saveConfiguration("other", "echo", value);
            writer.saveConfiguration("one", "echo", value);
            await(() -> events.get() == 2);
            Thread.sleep(150);
            assertEquals(2, events.get());
        }
    }

    @Test
    public void cancelledWatchUnsubscribesEveryNativeSource() throws Exception {
        // 一个治理范围由多种原生配置监听器共同组成。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess reader = sdk.configuration().openStorage()) {
            AtomicInteger events = new AtomicInteger();
            Cancellable watch = reader.watchConfiguration(null, "echo", events::incrementAndGet);
            await(() -> events.get() == 1);
            assertEquals(7, sdk.listenerCount());

            // 取消释放所有来源，重复取消不会重复清理。
            assertTrue(watch.cancel());
            assertFalse(watch.cancel());
            assertEquals(0, sdk.listenerCount());
        }
    }

    @Test
    public void failedListenerInstallationPropagatesAndRemovesEarlierListeners() throws Exception {
        // 在配置监听器安装时注入 SDK 错误。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess reader = sdk.configuration().openStorage()) {
            sdk.rejectConfigListener = true;

            // 错误上抛，并回收先前已经安装的原生监听器。
            Runnable callback = () -> {
            };
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> {
                reader.watchConfiguration(null, "echo", callback);
            });
            assertNotNull(failure.getCause());
            assertEquals(0, sdk.listenerCount());
        }
    }

    @Test
    public void callbackFailureRetriesWithoutLosingTheSnapshot() throws Exception {
        // 首次快照交付失败，回调在下一次交付时恢复。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess reader = sdk.configuration().openStorage()) {
            AtomicInteger attempts = new AtomicInteger();
            reader.watchProviders("echo", () -> {
                if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("callback failed");
                }
            });

            // 同一快照仍会再次交付，不会因为上次回调失败被标记为完成。
            await(() -> attempts.get() == 2);
        }
    }

    @Test
    public void configOnlyReconnectReappliesUnchangedSnapshot() throws Exception {
        // 先完成配置订阅的初始交付。
        try (MockNacosSdk sdk = new MockNacosSdk(); //
             StorageAccess reader = sdk.configuration().openStorage()) {
            AtomicInteger events = new AtomicInteger();
            reader.watchConfiguration(null, null, events::incrementAndGet);
            await(() -> events.get() == 1);

            // 等待订阅线程实际探测到 SDK 断连，再恢复 SDK 可用性。
            sdk.available = false;
            await(() -> sdk.disconnectedChecks.get() > 0);
            assertEquals(1, events.get());

            // 重连时即使正文未变，也要重新交付一次完整快照。
            sdk.available = true;
            await(() -> events.get() == 2);
        }
    }
}

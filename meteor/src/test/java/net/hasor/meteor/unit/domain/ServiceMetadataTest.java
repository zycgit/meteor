/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.domain;
import net.hasor.meteor.domain.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.domain.warp.MetBindInfoWrap;
import org.junit.Test;
import static org.junit.Assert.*;

public class ServiceMetadataTest {
    @Test
    public void metadataKeysAreReadOnlySnapshotsThroughTheBindingWrapper() {
        // 绑定包装器暴露只读快照，后续元信息更新不改变旧快照。
        ServiceDomain<Runnable> domain = new ServiceDomain<>(Runnable.class);
        MetBindInfo<Runnable> service = new MetBindInfoWrap<>(domain);
        assertTrue(service.getMetaKeys().isEmpty());
        domain.setMetaData(Map.of("first", 1, "second", 2));
        Set<String> keys = service.getMetaKeys();
        assertEquals(Set.of("first", "second"), keys);
        assertThrows(UnsupportedOperationException.class, () -> keys.remove("first"));
        assertEquals(1, domain.getMetaData("first"));

        // 修改底层元信息后，只有新的查询能看到变化。
        domain.removeMetaData("first");
        domain.setMetaData("third", 3);
        assertEquals(Set.of("first", "second"), keys);
        assertEquals(Set.of("second", "third"), service.getMetaKeys());
    }

    @Test
    public void existingKeyCanBeUpdatedAtCapacity() {
        // 容量已满时更新现有键不增加条目。
        MetBindInfo<Runnable> service = new MetBindInfoWrap<>(new ServiceDomain<>(Runnable.class));
        service.setMetaData(entries(1000));
        service.setMetaData("k0", "updated");
        assertEquals("updated", service.getMetaData("k0"));
        assertEquals(1000, service.getMetaKeys().size());
    }

    @Test
    public void newKeyIsRejectedAtCapacity() {
        // 单项新增超出容量时不产生额外键。
        MetBindInfo<Runnable> service = new MetBindInfoWrap<>(new ServiceDomain<>(Runnable.class));
        service.setMetaData(entries(1000));
        assertThrows(IllegalArgumentException.class, () -> service.setMetaData("extra", 1));
        assertNull(service.getMetaData("extra"));
        assertEquals(1000, service.getMetaKeys().size());
    }

    @Test
    public void rejectedBatchPreservesDataAndAllowsLaterWrites() {
        // 超容量批次包含现有键更新，验证失败必须发生在写入之前。
        MetBindInfo<Runnable> service = new MetBindInfoWrap<>(new ServiceDomain<>(Runnable.class));
        service.setMetaData(entries(1000));
        service.setMetaData("k0", "updated");
        Map<String, Object> rejected = new LinkedHashMap<>();
        rejected.put("k0", "should not change");
        rejected.put("extra", 1);

        assertThrows(IllegalArgumentException.class, () -> service.setMetaData(rejected));
        assertEquals("updated", service.getMetaData("k0"));
        assertNull(service.getMetaData("extra"));

        // 同一个服务遭拒后仍可释放容量，再通过单项和批量入口继续写入。
        service.removeMetaData("k1");
        service.setMetaData("extra", 1);
        assertEquals(1, service.getMetaData("extra"));
        service.setMetaData("extra", null);
        service.setMetaData(Map.of("new", 2));
        assertEquals(2, service.getMetaData("new"));
    }

    @Test
    public void removingKeyReleasesCapacityForSingleWrite() {
        // 显式删除释放一个名额，后续单项写入即可使用。
        MetBindInfo<Runnable> service = new MetBindInfoWrap<>(new ServiceDomain<>(Runnable.class));
        service.setMetaData(entries(1000));
        service.removeMetaData("k1");
        service.setMetaData("extra", 1);
        assertEquals(1, service.getMetaData("extra"));
        assertEquals(1000, service.getMetaKeys().size());
    }

    @Test
    public void nullValueReleasesCapacityForBatchWrite() {
        // 将值设为 null 等同删除，批量写入能复用释放的容量。
        MetBindInfo<Runnable> service = new MetBindInfoWrap<>(new ServiceDomain<>(Runnable.class));
        service.setMetaData(entries(1000));
        service.setMetaData("k1", null);
        service.setMetaData(Map.of("new", 2));
        assertEquals(2, service.getMetaData("new"));
        assertNull(service.getMetaData("k1"));
        assertEquals(1000, service.getMetaKeys().size());
    }

    @Test
    public void batchCanSwapKeysAtCapacityRegardlessOfEntryOrder() {
        // 批次里先新增后删除，仍应按合并后的容量进行校验。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setMetaData(entries(1000));
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("replacement", true);
        changes.put("k0", null);

        service.setMetaData(changes);
        assertEquals(true, service.getMetaData("replacement"));
        assertNull(service.getMetaData("k0"));
        assertEquals(999, service.getMetaData("k999"));
        assertEquals(1000, service.getMetaKeys().size());
    }

    @Test
    public void batchWithNullKeyLeavesExistingDataUntouched() {
        // 非法键与有效更新同批提交，整批失败不能留下部分更新。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setMetaData(entries(1000));
        Map<String, Object> invalid = new LinkedHashMap<>();
        invalid.put("k1", "should not change");
        invalid.put(null, 1);

        assertThrows(NullPointerException.class, () -> service.setMetaData(invalid));
        assertEquals(1, service.getMetaData("k1"));
        assertEquals(1000, service.getMetaKeys().size());
    }

    @Test
    public void oversizedInitialBatchLeavesServiceEmpty() {
        // 初次写入同样遵守容量约束，失败不产生任何元信息。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        Map<String, Object> oversized = entries(1001);
        assertThrows(IllegalArgumentException.class, () -> service.setMetaData(oversized));
        assertTrue(service.getMetaKeys().isEmpty());
    }

    @Test
    public void concurrentWritersKeepAcceptedUpdatesWithBestEffortCapacity() throws Exception {
        // 并发写入允许象征性超限，但已接受的独立键不能丢失。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setMetaData(entries(999));
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String key = "concurrent" + i;
                calls.add(executor.submit(() -> {
                    try {
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        service.setMetaData(Map.of(key, true));
                        accepted.incrementAndGet();
                    } catch (IllegalArgumentException expected) {
                        assertTrue(expected.getMessage().contains("1000"));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                }));
            }
            start.countDown();
            for (Future<?> call : calls) {
                call.get(5, TimeUnit.SECONDS);
            }
            // 所有请求结束后逐键核对成功数，而非强制并发总量恰好为上限。
            assertTrue(accepted.get() >= 1);
            int stored = 0;
            for (int i = 0; i < 8; i++) {
                if (Boolean.TRUE.equals(service.getMetaData("concurrent" + i))) {
                    stored++;
                }
            }
            assertEquals(accepted.get(), stored);
            assertThrows(IllegalArgumentException.class, () -> service.setMetaData("after-writes", true));
            service.setMetaData("k0", "updated");
            assertEquals("updated", service.getMetaData("k0"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void concurrentSingleAndBulkWritesPreserveUnrelatedKeys() throws Exception {
        // 不同线程用不同键，同时执行单项和批量更新。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String prefix = "writer-" + i + "-";
                calls.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    for (int item = 0; item < 50; item++) {
                        service.setMetaData(prefix + "single-" + item, item);
                        service.setMetaData(Map.of(prefix + "batch-" + item, item));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> call : calls) {
                call.get(10, TimeUnit.SECONDS);
            }

            // 全部写入完成后，每个线程的独立键都必须保留。
            for (int writer = 0; writer < 8; writer++) {
                String prefix = "writer-" + writer + "-";
                for (int item = 0; item < 50; item++) {
                    assertEquals(item, service.getMetaData(prefix + "single-" + item));
                    assertEquals(item, service.getMetaData(prefix + "batch-" + item));
                }
            }
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static Map<String, Object> entries(int size) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            values.put("k" + i, i);
        }
        return values;
    }
}
